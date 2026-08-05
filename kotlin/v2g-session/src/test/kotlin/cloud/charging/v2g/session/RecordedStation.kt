package cloud.charging.v2g.session

import cloud.charging.v2g.exi.JsonArray
import cloud.charging.v2g.exi.JsonObject
import cloud.charging.v2g.exi.JsonString
import cloud.charging.v2g.exi.JsonValue
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch

/**
 * A station that answers exactly what one recorded session recorded, over a real socket.
 *
 * ## Why a socket and not a pair of byte arrays
 *
 * The EVCC trace tests already replay these sessions over `ByteArrayInputStream`, and they prove the
 * state machines say the right bytes. What they cannot exercise is the thing that only exists on a
 * network: **a frame does not arrive all at once.** `V2GTPStream.readExactly` loops for exactly that
 * reason, and a `ByteArrayInputStream` — which always returns everything asked for — is the one input
 * that can never take the loop round twice.
 *
 * So this deliberately writes each response in [FRAGMENT] byte pieces, flushing between them. A
 * reader that assumed one `read` per frame passes every existing test in this repository and fails
 * here on the first response.
 *
 * ## It is also still the byte oracle
 *
 * Each request is compared with the recorded one before the matching response goes out. A live runner
 * that reached the station and said something slightly different would otherwise look like a success.
 *
 * Loopback only, and no port is chosen: `ServerSocket(0)` takes whatever is free. Nothing here leaves
 * the machine.
 */
class RecordedStation(private val trace: JsonObject) : AutoCloseable {

    private val server  = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val started = CountDownLatch(1)
    private var worker: Thread? = null

    val port: Int get() = server.localPort

    /** What went wrong on the station side, if anything. Read after the session ends. */
    @Volatile var complaint: String? = null
        private set

    /** How many recorded exchanges the EV actually walked through. */
    @Volatile var served: Int = 0
        private set

    val exchanges: Int get() = (trace["exchanges"] as JsonArray).size


    fun start(): RecordedStation {

        worker = Thread({
            started.countDown()
            try {
                server.accept().use { serve(it) }
            } catch (e: Exception) {
                if (complaint == null) complaint = "the station stopped: ${e.message}"
            }
        }, "recorded-station").apply { isDaemon = true; start() }

        started.await()
        return this
    }


    private fun serve(socket: Socket) {

        val input  = socket.getInputStream()
        val output = socket.getOutputStream()

        for (exchange in (trace["exchanges"] as JsonArray).asList()) {

            val entry    = exchange as JsonObject
            val expected = bytes((entry["request"] as JsonObject)["frame"] as JsonString)

            val actual = ByteArray(expected.size)
            var read = 0
            while (read < actual.size) {
                val n = input.read(actual, read, actual.size - read)
                if (n < 0) return                      // the EV hung up; `served` says how far it got
                read += n
            }

            if (!actual.contentEquals(expected)) {
                complaint = "exchange $served: the EV sent ${hex(actual)}, the recording has ${hex(expected)}"
                return
            }

            val response = entry["response"] as? JsonObject ?: return
            val frame    = bytes(response["frame"] as JsonString)

            // In pieces, on purpose — see the class comment.
            var at = 0
            while (at < frame.size) {
                val n = minOf(FRAGMENT, frame.size - at)
                output.write(frame, at, n)
                output.flush()
                at += n
            }

            served++
        }
    }


    override fun close() {
        server.close()
        worker?.join(2_000)
    }


    companion object {

        /** Small enough that every frame in the corpus is split several times. */
        const val FRAGMENT = 3

        /** One recorded session, from the corpus the C# side generates. */
        fun load(name: String): JsonObject {

            var dir = File(".").absoluteFile
            while (!File(dir, "EVSimulatorApp.slnx").isFile)
                dir = dir.parentFile ?: error("repository root not found")

            val file = File(dir,
                "../ISO15118ConformanceTests.Simulation/Vectors/Session.$name.trace.json")

            require(file.isFile) { "no recorded session at $file" }

            return JsonValue.parse(file.readText()) as JsonObject
        }

        private fun bytes(hex: JsonString) = bytes(hex.value)

        fun bytes(hex: String) =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }
    }
}
