package cloud.charging.v2g.exi

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An encoded message must depend on the message and on nothing else — in particular not on what the
 * destination buffer held beforehand.
 *
 * Regression test for a leak found in the C# writer on 2026-07-31 while building the session-trace
 * corpus, and present here identically. [BitWriter] wrote only the bits a message occupies, so the
 * unused bits of the final, partial byte kept whatever was already there. Sessions reuse one send
 * buffer, so that is the previous message: up to seven bits of it travel on the wire, and in a
 * Plug & Charge session the previous message is a contract chain or a signature.
 *
 * Why nothing caught it: a round trip never reads padding, and the vector corpus encodes each
 * message into a fresh — therefore zeroed — array, so the recorded bytes are exactly the ones a
 * clean buffer produces. The Swift writer was never affected; it appends a zero byte as it grows.
 *
 * The mirror of `BitWriterBufferHygieneTests` on the C# side. The two writers are meant to agree bit
 * for bit, which includes the bits neither of them means to say anything with.
 */
class BitWriterBufferHygieneTest {

    /** Two bits into a buffer full of 1s: the six unused bits must be zero, not the 1s. */
    @Test
    fun trailingPartialByteIsCleared() {
        val buffer = ByteArray(4) { 0xFF.toByte() }

        val writer = BitWriter(buffer)
        writer.writeBits(0b01u, 2)

        assertEquals(1, writer.bytesWritten)
        assertEquals(
            0b0100_0000.toByte(), buffer[0],
            "the six bits after the message are padding and must not carry the old contents"
        )
    }

    /** Whole bytes inside the message too — already handled, kept so a rewrite cannot trade one
     *  hazard for the other. */
    @Test
    fun staleBitsInsideTheMessageAreOverwritten() {
        val buffer = ByteArray(4) { 0xFF.toByte() }

        BitWriter(buffer).writeBits(0u, 16)

        assertEquals(0, buffer[0].toInt())
        assertEquals(0, buffer[1].toInt())
    }

    /** The same bits written into a clean and a dirty buffer produce the same bytes. */
    @Test
    fun theSameBitsEncodeIdenticallyIntoACleanAndADirtyBuffer() {
        val clean = ByteArray(8)
        val dirty = ByteArray(8) { 0xFF.toByte() }

        fun write(into: ByteArray): Int {
            val w = BitWriter(into)
            w.writeBits(0b1011u, 4)
            w.writeBits(0x2Au, 8)
            w.writeBits(0b101u, 3)
            return w.bytesWritten
        }

        val cleanLength = write(clean)
        val dirtyLength = write(dirty)

        assertEquals(cleanLength, dirtyLength)
        assertEquals(
            clean.copyOf(cleanLength).toHexString(),
            dirty.copyOf(dirtyLength).toHexString(),
            "the encoding must not depend on the destination buffer's previous contents"
        )
    }

    /** The offset constructor takes the same care — the EXI header sits in front of the bitstream,
     *  so the byte the writer starts on is not byte 0. */
    @Test
    fun theOffsetFormClearsItsOwnBytesToo() {
        val buffer = ByteArray(8) { 0xFF.toByte() }

        BitWriter(buffer, offset = 2).writeBits(0b01u, 2)

        assertEquals(0xFF.toByte(), buffer[1], "bytes before the offset are none of the writer's business")
        assertEquals(0b0100_0000.toByte(), buffer[2])
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}
