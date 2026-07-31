package cloud.charging.v2g.evcc

import cloud.charging.v2g.tp.MessageSet
import cloud.charging.v2g.tp.V2GTP
import cloud.charging.v2g.tp.V2GTPDecodeResult
import cloud.charging.v2g.tp.V2GTPDispatcher
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes single V2GTP frames on a stream pair — the one place in this module that touches
 * the transport octets directly. Works unchanged over a socket's streams or over the trace-replay
 * pair the tests substitute; everything above only ever sees a [MessageSet] and a decoded message.
 *
 * A port of the C# `V2GTPStream`. It takes an [InputStream]/[OutputStream] pair where C# takes one
 * bidirectional `Stream`, because that is what a JVM socket actually offers. Calls block; the caller
 * decides which thread that happens on (on Android, a `Dispatchers.IO` coroutine).
 */
class V2GTPStream(private val input: InputStream, private val output: OutputStream) {

    /**
     * Reads one frame — the 8-byte header, then exactly as many payload bytes as it declares — and
     * hands the whole frame to [V2GTPDispatcher]. Throws on a malformed header, a peer that closes
     * mid-frame, or an unrecognised payload type: there is no "try" variant because a broken frame
     * is not a recoverable condition for a session peer.
     */
    fun readFrame(): Pair<MessageSet, Any> =
        when (val decoded = V2GTPDispatcher.decode(readRawFrame().first)) {
            is V2GTPDecodeResult.Decoded -> decoded.set to decoded.message
            is V2GTPDecodeResult.Failed  -> throw IOException("V2GTP frame: ${decoded.error}")
        }

    /**
     * Reads one frame at the transport level and returns it whole, together with its payload type,
     * WITHOUT resolving it to a message set. Used by the SupportedAppProtocol handshake, which
     * shares payload id 0x8001 with the -2 messages and so cannot be routed by payload type alone.
     */
    fun readRawFrame(): Pair<ByteArray, UShort> {

        val header = ByteArray(V2GTP.HEADER_SIZE)
        readExactly(header, 0, header.size,
                    "connection closed before a full 8-byte header arrived")

        val parsed = V2GTP.tryReadHeader(header)
            ?: throw IOException("V2GTP frame: bad version/type bytes in the 8-byte header.")

        val payloadLength = parsed.payloadLength.toInt()
        val frame = header.copyOf(V2GTP.HEADER_SIZE + payloadLength)

        if (payloadLength > 0)
            readExactly(frame, V2GTP.HEADER_SIZE, payloadLength,
                        "connection closed inside a frame declaring $payloadLength payload byte(s)")

        return frame to parsed.payloadType
    }

    /** Wraps an already-EXI-encoded payload with the header for [set] and writes it in one call. */
    fun writeFrame(set: MessageSet, exiPayload: ByteArray) =
        writeAll(V2GTPDispatcher.encode(set, exiPayload))

    /** As [writeFrame], but with the payload type given directly — the SAP path, which has no
     *  [MessageSet] of its own on the wire. */
    fun writeRawFrame(payloadType: UShort, exiPayload: ByteArray) {
        val frame = ByteArray(V2GTP.HEADER_SIZE + exiPayload.size)
        V2GTP.writeHeader(frame, payloadType, exiPayload.size.toUInt())
        exiPayload.copyInto(frame, V2GTP.HEADER_SIZE)
        writeAll(frame)
    }

    private fun writeAll(frame: ByteArray) {
        output.write(frame)
        output.flush()
    }

    private fun readExactly(into: ByteArray, offset: Int, length: Int, whatWentWrong: String) {
        var read = 0
        while (read < length) {
            val n = input.read(into, offset + read, length - read)
            if (n < 0)
                throw EOFException("V2GTP frame: $whatWentWrong (got $read of $length).")
            read += n
        }
    }
}
