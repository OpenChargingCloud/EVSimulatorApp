package cloud.charging.v2g.evcc

import cloud.charging.v2g.tp.V2GTP
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the framing layer will and will not believe.
 *
 * The trace tests hold this class to whole recorded sessions, which is the only thing that proves it
 * reads real frames correctly. What they cannot cover is a peer that lies, because every recorded
 * peer is one of ours — and the peer here is whatever answered the socket.
 */
class V2GTPStreamTest {

    private fun stream(bytes: ByteArray) =
        V2GTPStream(ByteArrayInputStream(bytes), ByteArrayOutputStream())

    private fun header(payloadType: UShort, payloadLength: UInt): ByteArray {
        val header = ByteArray(V2GTP.HEADER_SIZE)
        V2GTP.writeHeader(header, payloadType, payloadLength)
        return header
    }


    /**
     * A declared length nobody could mean is refused before it is allocated.
     *
     * Both values matter, for different reasons. `0x7FFFFFFF` is a 2 GiB allocation that an 8-byte
     * frame can ask for — on a phone, an out-of-memory kill that costs the sender nothing. And
     * `0xFFFFFFFF` is the one that `.toInt()` turns into `-1`: without this check the reader
     * allocates nothing, reads nothing, and returns a **silently truncated 7-byte frame**, which is
     * worse than the crash because nothing about it looks wrong.
     */
    @Test
    fun `an absurd declared length is refused rather than allocated for`() {

        for (declared in listOf(0x7FFFFFFFu, 0xFFFFFFFFu, (V2GTP.MAXIMUM_PAYLOAD_BYTES + 1).toUInt())) {

            val thrown = kotlin.runCatching {
                stream(header(V2GTP.PAYLOAD_TYPE_DIN_ISO2_MAIN, declared)).readRawFrame()
            }.exceptionOrNull()

            assertTrue(thrown is IOException, "0x%08x was accepted".format(declared.toLong()))
            assertTrue(thrown.message!!.contains("accepts at most"), thrown.message!!)
        }
    }


    /** And the limit is far above anything the corpus contains. */
    @Test
    fun `a frame at the largest recorded size is read whole`() {

        val payload = ByteArray(921 - V2GTP.HEADER_SIZE)     // the -20 AuthorizationReq with a chain
        val frame   = header(V2GTP.PAYLOAD_TYPE_ISO20_MAIN, payload.size.toUInt()) + payload

        val (read, payloadType) = stream(frame).readRawFrame()

        assertEquals(921, read.size)
        assertEquals(V2GTP.PAYLOAD_TYPE_ISO20_MAIN, payloadType)
    }
}
