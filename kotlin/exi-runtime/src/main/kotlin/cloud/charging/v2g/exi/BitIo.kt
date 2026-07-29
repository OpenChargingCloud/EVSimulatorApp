package cloud.charging.v2g.exi

/**
 * Bit-level writer over a [ByteArray].
 *
 * EXI bit-packed alignment is MSB-first within each byte: the first bit written occupies
 * bit 7 (0x80) of the first byte, the second bit 6 (0x40), and so on.
 *
 * A faithful port of the C# `BitWriter`; the two must agree bit for bit.
 *
 * @param buffer destination, assumed zero-initialised (a fresh [ByteArray] is).
 * @param offset byte offset the bitstream starts at — the EXI header occupies byte 0.
 */
class BitWriter(private val buffer: ByteArray, private val offset: Int = 0) {

    private var bitPos = 0

    val bitsWritten: Int get() = bitPos
    val bytesWritten: Int get() = (bitPos + 7) shr 3

    /** Write the lowest [numBits] of [value], MSB first. */
    fun writeBits(value: UInt, numBits: Int) {
        require(numBits in 0..32) { "numBits out of range: $numBits" }
        for (i in numBits - 1 downTo 0)
            writeBit(((value shr i) and 1u) != 0u)
    }

    fun writeBit(b: Boolean) {
        val byteIdx = offset + (bitPos shr 3)
        val mask = 1 shl (7 - (bitPos and 7))
        // Overwrite the target bit rather than only OR-ing 1s, so a reused buffer cannot
        // leave stale 1-bits behind.
        buffer[byteIdx] =
            if (b) (buffer[byteIdx].toInt() or mask).toByte()
            else (buffer[byteIdx].toInt() and mask.inv()).toByte()
        bitPos++
    }

    /** Pad to the next byte boundary with zero bits. */
    fun alignToByte() {
        val rem = bitPos and 7
        if (rem != 0) bitPos += 8 - rem
    }
}

/**
 * Bit-level reader over a [ByteArray], MSB-first to match EXI bit-packed alignment.
 * A faithful port of the C# `BitReader`.
 */
class BitReader(private val buffer: ByteArray, private val offset: Int = 0) {

    private var bitPos = 0

    val bitsRead: Int get() = bitPos
    val bytesConsumed: Int get() = (bitPos + 7) shr 3

    fun readBit(): Boolean {
        val byteIdx = offset + (bitPos shr 3)
        val bitInByte = bitPos and 7
        if (byteIdx >= buffer.size) throw IllegalStateException("EXI bitstream exhausted")
        val bit = ((buffer[byteIdx].toInt() shr (7 - bitInByte)) and 1) != 0
        bitPos++
        return bit
    }

    fun readBits(numBits: Int): UInt {
        require(numBits in 0..32) { "numBits out of range: $numBits" }
        var value = 0u
        repeat(numBits) { value = (value shl 1) or (if (readBit()) 1u else 0u) }
        return value
    }
}
