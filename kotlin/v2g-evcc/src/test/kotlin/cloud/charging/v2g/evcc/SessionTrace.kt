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
 * frame is **not** byte-comparable as it stands — ECDSA's nonce is random — and the C# side compares
 * it by substituting the recorded value and verifying the produced one separately (`SignedFrame.cs`).
 * This harness does not do that yet, and refuses such a frame rather than pretending.
 */
class TraceFrame(val message: String, val bytes: ByteArray, val signature: String?) {
    val isSigned: Boolean get() = signature != null
}

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
                   val exchanges: List<TraceExchange>) {

    companion object {

        // 2 since 2026-07-31, when frames gained an optional signature. The bump is deliberate even
        // though the change is additive: a reader that silently ignored the new field would compare a
        // signed frame as though its bytes were deterministic and fail for the wrong reason.
        private const val SCHEMA_VERSION = 2

        fun load(name: String): SessionTrace {

            var dir = File(".").absoluteFile
            while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory)
                dir = dir.parentFile ?: error("repository root not found")

            val file = File(dir, "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Simulation.Tests/" +
                                 "Vectors/Session.$name.trace.json")
            require(file.isFile) { "session trace not found at $file" }

            val root = JsonParser.parseString(file.readText()).asJsonObject

            val version = root.get("schemaVersion").asInt
            require(version == SCHEMA_VERSION) {
                "trace schema version $version, this build understands $SCHEMA_VERSION"
            }

            val exchanges = root.getAsJsonArray("exchanges").map { it.asJsonObject }.map { e ->
                fun frame(side: String) = e.getAsJsonObject(side).let {
                    val signature = it.get("signature")
                    TraceFrame(it.get("message").asString, hex(it.get("frame").asString),
                               if (signature == null || signature.isJsonNull) null else signature.asString)
                }
                TraceExchange(e.get("index").asInt, frame("request"), frame("response"))
            }

            return SessionTrace(root.get("name").asString, root.get("protocol").asString,
                                root.get("mode").asString, exchanges)
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

            // The C# harness compares a signed frame by substituting the recorded signature and
            // verifying the produced one on its own. Doing that here needs a decode/re-encode path
            // and an ECDSA verify; until it exists, refusing is the only honest option — comparing
            // the bytes as they stand would fail on the 64 signature bytes and say nothing.
            if (exchange.request.isSigned)
                throw TraceMismatch(
                    "exchange $replayed (${exchange.request.message}) in trace '${trace.name}' is " +
                    "signed, and this harness cannot compare signed frames yet. The Kotlin EVCC does " +
                    "not do Plug & Charge either, so no test should be loading this trace.")

            if (!frame.contentEquals(exchange.request.bytes))
                throw TraceMismatch(
                    "exchange $replayed (${exchange.request.message}) differs from the trace " +
                    "'${trace.name}':\n" + diff(exchange.request.bytes, frame))

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
