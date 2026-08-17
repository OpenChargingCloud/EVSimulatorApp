package cloud.charging.v2g.evcc

import com.google.gson.JsonParser
import cloud.charging.v2g.tp.V2GTP
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * One recorded frame: the whole thing, header included. [message] is a label for failure text.
 *
 * [signature] is the raw `r‖s` value the frame carried, when it carried one. Its presence means the
 * frame is **not** byte-comparable as it stands — ECDSA's nonce is random — so it is compared by
 * substituting the recorded value and verifying the produced one separately. See [SignedFrame].
 */
class TraceFrame(val message: String, val bytes: ByteArray, val signature: String?,
                 val meterSignature: String? = null) {
    val isSigned: Boolean get() = signature != null
    val signatureBytes: ByteArray? get() = signature?.let(::hexBytes)

    /**
     * The raw `r‖s` the station's **meter** put in `MeterInfo`, when it fitted one. A second
     * randomised signature by a second signer, and one that travels in *responses* — so unlike
     * [signature] it does not make a request incomparable, and the replay never has to substitute it.
     */
    val carriesMeterSignature: Boolean get() = meterSignature != null
    val meterSignatureBytes: ByteArray? get() = meterSignature?.let(::hexBytes)

    private fun hexBytes(s: String) =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}


/** A P-256 public key, as the two field elements — enough to verify a raw `r‖s` signature. */
class TraceSigningKey(val x: String, val y: String)

/** One recorded request/response pair. */
class TraceExchange(val index: Int, val request: TraceFrame, val response: TraceFrame)

/**
 * A session recorded by the C# side, read verbatim out of the submodule's `Vectors/` directory —
 * the same arrangement the meter corpus already uses, and for the same reason: a port checked
 * against its own output can be wrong together with itself.
 *
 * See `Vanaheimr.V2G.Simulation.Tests/Traces/SessionTrace.cs` for what these files do and do not
 * prove. In short: they pin this port to what the C# EVCC does, and cannot catch a bug it has too.
 */
class SessionTrace(val name: String, val protocol: String, val mode: String,
                   val exchanges: List<TraceExchange>, val signingKey: TraceSigningKey?,
                   val meterKey: TraceSigningKey?) {

    companion object {

        // 3 since 2026-08-03, when frames gained an optional meterSignature and traces a meterKey —
        // a station whose meter signs its readings. 2 since 2026-07-31, when frames gained an
        // optional signature. Both bumps are deliberate even though the changes are additive: a
        // reader that silently ignored the new field would compare a frame as though its bytes were
        // deterministic and fail for the wrong reason.
        private const val SCHEMA_VERSION = 3

        fun load(name: String): SessionTrace {

            var dir = File(".").absoluteFile
            while (!File(dir, "EVSimulatorApp.slnx").isFile)
                dir = dir.parentFile ?: error("repository root not found")

            val file = File(dir, "vectors/Session.$name.trace.json")
            require(file.isFile) { "session trace not found at $file" }

            val root = JsonParser.parseString(file.readText()).asJsonObject

            val version = root.get("schemaVersion").asInt
            require(version == SCHEMA_VERSION) {
                "trace schema version $version, this build understands $SCHEMA_VERSION"
            }

            val exchanges = root.getAsJsonArray("exchanges").map { it.asJsonObject }.map { e ->
                fun frame(side: String) = e.getAsJsonObject(side).let {
                    TraceFrame(it.get("message").asString, hex(it.get("frame").asString),
                               text(it, "signature"), text(it, "meterSignature"))
                }
                TraceExchange(e.get("index").asInt, frame("request"), frame("response"))
            }

            return SessionTrace(root.get("name").asString, root.get("protocol").asString,
                                root.get("mode").asString, exchanges,
                                key(root, "signingKey"), key(root, "meterKey"))
        }

        /** Absent and null are the same answer — schema 3 omits nulls rather than writing them. */
        private fun text(node: com.google.gson.JsonObject, name: String): String? {
            val value = node.get(name)
            return if (value == null || value.isJsonNull) null else value.asString
        }

        private fun key(node: com.google.gson.JsonObject, name: String): TraceSigningKey? {
            val value = node.get(name)
            return if (value == null || value.isJsonNull) null
                   else value.asJsonObject.let { TraceSigningKey(it.get("x").asString, it.get("y").asString) }
        }

        private fun hex(s: String) = ByteArray(s.length / 2) {
            s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}


/** Raised the moment a replayed session sends something the trace did not record. */
class TraceMismatch(message: String) : Exception(message)


/**
 * A station made of a recorded session: it answers each request with the trace's next recorded
 * response, and requires the request that arrived to be byte-identical to the one recorded in that
 * slot. No peer, no socket, no SECC — only the file.
 *
 * The Kotlin half of the oracle, mirroring `TraceReplayStream.cs`. It fails on the **first**
 * divergent frame: once a request differs, every later one is a consequence of a session that
 * already went wrong, and twelve mismatches would bury the one thing that broke.
 */
class TraceReplay(private val trace: SessionTrace) {

    private val pending  = ByteArrayOutputStream()
    private val readable = ArrayDeque<Byte>()

    /** How many exchanges were replayed. A session that stops early sends no wrong bytes. */
    var replayed: Int = 0
        private set

    val complete: Boolean get() = replayed == trace.exchanges.size

    /** The corpus public key, built once. Verification needs a key from outside the frame — taking
     *  one from the message itself would accept anything a port cared to sign with. */
    private val signingKey by lazy {
        val key = trace.signingKey
            ?: throw TraceMismatch(
                "trace '${trace.name}' carries a signed exchange but no signing key. The C# " +
                "SessionTrace.Build refuses to produce that, so this file was hand-edited.")
        SignedFrame.publicKey(key.x, key.y)
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = accept(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) = accept(b.copyOfRange(off, off + len))
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (readable.isEmpty())
                throw TraceMismatch(
                    "exchange $replayed: the session tried to read a response without having " +
                    "written a complete request first — nothing in the trace answers that.")
            val n = minOf(len, readable.size)
            for (i in 0 until n) b[off + i] = readable.removeFirst()
            return n
        }
    }

    private fun accept(written: ByteArray) {

        pending.write(written)

        while (true) {

            val buffered = pending.toByteArray()
            if (buffered.size < V2GTP.HEADER_SIZE) return

            val header = V2GTP.tryReadHeader(buffered)
                ?: throw TraceMismatch(
                    "exchange $replayed: the bytes written are not a V2GTP frame (bad version/type bytes).")

            val total = V2GTP.HEADER_SIZE + header.payloadLength.toInt()
            if (buffered.size < total) return

            val frame = buffered.copyOfRange(0, total)
            pending.reset()
            pending.write(buffered, total, buffered.size - total)

            if (replayed >= trace.exchanges.size)
                throw TraceMismatch(
                    "the session sent exchange $replayed, but the trace '${trace.name}' records only " +
                    "${trace.exchanges.size}. The port charges on past where the recording ends.")

            val exchange = trace.exchanges[replayed]

            // A meter signature in a *request* would need the same substitution one field along, and
            // this harness does not do it. No recorded request carries one — the EV only ever echoes
            // a reading inside a signed MeteringReceiptReq, which the C# corpus refuses to record for
            // a separate reason (the echoed bytes sit inside the digested fragment). Refusing beats
            // comparing bytes that cannot match.
            if (exchange.request.carriesMeterSignature)
                throw TraceMismatch(
                    "exchange $replayed (${exchange.request.message}) carries a meter signature in a " +
                    "request. This harness can only substitute the header signature, so it would " +
                    "compare 64 random bytes and fail for the wrong reason.")

            // A signed frame cannot be compared as bytes — ECDSA's nonce is random. SignedFrame
            // explains the substitution; the short of it is that the signature value is the only
            // random part, so putting the recorded one back makes everything else comparable
            // exactly, and the produced one is checked on its own below.
            val recordedSignature = exchange.request.signatureBytes
            val comparable = if (recordedSignature != null)
                                 SignedFrame.withSignatureValue(frame, recordedSignature)
                             else frame

            if (!comparable.contentEquals(exchange.request.bytes))
                throw TraceMismatch(
                    "exchange $replayed (${exchange.request.message}) differs from the trace " +
                    "'${trace.name}'" +
                    (if (exchange.request.isSigned) " (compared with the recorded signature substituted)" else "") +
                    ":\n" + diff(exchange.request.bytes, comparable))

            if (exchange.request.isSigned && !SignedFrame.verifiesWith(frame, signingKey))
                throw TraceMismatch(
                    "exchange $replayed (${exchange.request.message}) matches the trace once its " +
                    "signature is substituted, but the signature it actually produced does not verify " +
                    "against the corpus key. The message is right and the signing is not — a wrong " +
                    "key, wrong octets, or a wrong signature encoding.")

            for (b in exchange.response.bytes) readable.addLast(b)
            replayed++
        }
    }

    /** Where two frames first part company. The offset is the useful part: under 8 it is the V2GTP
     *  header, above it the EXI body. */
    private fun diff(expected: ByteArray, actual: ByteArray): String {

        var at = 0
        while (at < expected.size && at < actual.size && expected[at] == actual[at]) at++

        val where = if (at < V2GTP.HEADER_SIZE) "byte $at, inside the 8-byte V2GTP header"
                    else "byte $at (EXI payload offset ${at - V2GTP.HEADER_SIZE})"

        fun window(bytes: ByteArray) =
            if (bytes.size <= at) "<ends here>"
            else bytes.copyOfRange(at, minOf(at + 16, bytes.size)).joinToString("") { "%02x".format(it) }

        return "  first difference at $where\n" +
               "  trace  ${expected.size} bytes, from there: ${window(expected)}\n" +
               "  actual ${actual.size} bytes, from there: ${window(actual)}"
    }
}
