package cloud.charging.v2g.exi

/**
 * Bit-level writer over a [ByteArray].
 *
 * EXI bit-packed alignment is MSB-first within each byte: the first bit written occupies
 * bit 7 (0x80) of the first byte, the second bit 6 (0x40), and so on.
 *
 * A faithful port of the C# `BitWriter`; the two must agree bit for bit.
 *
 * @param buffer destination. It need NOT be zero-initialised: every byte is cleared as it is
 *   first reached (see [writeBit]).
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
        val bit = bitPos and 7
        // Clear each byte as it is first reached, rather than only overwriting the bits actually
        // written. Both matter for a reused buffer, but for different reasons: stale 1-bits inside
        // the message would corrupt it, and stale bits in the trailing PARTIAL byte — the padding
        // nobody ever writes — travel silently. Up to seven bits of whatever occupied that byte
        // before, which in a session is the previous message.
        //
        // Found in the C# writer on 2026-07-31 by re-recording a session trace and fixed in both;
        // the Swift writer never had it, because it appends a zero byte as it grows. Neither
        // round-trips nor the vector corpus can see this: padding is never read back, and each
        // vector encodes into a fresh — therefore zeroed — array.
        if (bit == 0) buffer[byteIdx] = 0
        if (b) buffer[byteIdx] = (buffer[byteIdx].toInt() or (1 shl (7 - bit))).toByte()
        bitPos++
    }

    /** Pad to the next byte boundary. The skipped bits are already zero: the byte was cleared
     *  when [writeBit] first reached it. */
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

    /**
     * The EXI string value-table partitions for this stream, created on first use.
     *
     * It hangs off the reader so the generated decoders need no extra parameter threaded through
     * every call — a value read only has to name its own slot. There is deliberately no counterpart
     * on [BitWriter]: cbV2G is miss-only, every checked-in vector is its output, and an encoder that
     * started emitting hits would invalidate all of them.
     */
    val stringTable: ExiStringTable by lazy { ExiStringTable() }

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
