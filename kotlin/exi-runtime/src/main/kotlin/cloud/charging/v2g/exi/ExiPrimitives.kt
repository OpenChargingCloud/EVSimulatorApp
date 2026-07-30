package cloud.charging.v2g.exi

/**
 * EXI primitive type codecs — a faithful port of the C# `ExiPrimitives`.
 *
 * String values are **miss-only** (verbatim value, `length + 2` prefix), matching the ISO 15118
 * wire reality: EVerest's cbexigen/cbV2G never emits value-table hits and its decoder rejects
 * them. The full §7.3.3 value-table codec is only needed to interoperate with stacks that do
 * emit hits (EXIficient / Josev) and is not part of this spike.
 *
 * Float, Decimal and DateTime are deliberately absent — the -2/-20 schemas model physical
 * quantities as multiplier/value integer pairs instead.
 */
object ExiPrimitives {

    /** EXI Unsigned Integer: 7 bits of value per byte, MSB = continuation flag. */
    fun writeUnsignedInteger(w: BitWriter, value: ULong) {
        var v = value
        do {
            var chunk = (v and 0x7FuL).toUInt()
            v = v shr 7
            if (v != 0uL) chunk = chunk or 0x80u
            w.writeBits(chunk, 8)
        } while (v != 0uL)
    }

    fun readUnsignedInteger(r: BitReader): ULong {
        var value = 0uL
        var shift = 0
        while (true) {
            val chunk = r.readBits(8)
            value = value or ((chunk and 0x7Fu).toULong() shl shift)
            if ((chunk and 0x80u) == 0u) return value
            shift += 7
            if (shift > 63) throw IllegalArgumentException("EXI Unsigned Integer overflow (>64 bits).")
        }
    }

    /**
     * EXI Integer: a 1-bit sign (0 = non-negative) followed by the magnitude as an Unsigned
     * Integer. For negative values the magnitude is `|value| - 1`, so -1 maps to 0 and zero has
     * a single representation.
     */
    fun writeSignedInteger(w: BitWriter, value: Long) {
        if (value < 0) {
            w.writeBits(1u, 1)
            writeUnsignedInteger(w, (-(value + 1)).toULong())
        } else {
            w.writeBits(0u, 1)
            writeUnsignedInteger(w, value.toULong())
        }
    }

    fun readSignedInteger(r: BitReader): Long {
        val negative = r.readBits(1) != 0u
        val mag = readUnsignedInteger(r)
        if (mag > Long.MAX_VALUE.toULong())
            throw IllegalArgumentException("EXI Signed Integer magnitude out of 64-bit range.")
        return if (negative) -mag.toLong() - 1 else mag.toLong()
    }

    /**
     * EXI string value, "miss" case: `UnsignedInteger(codePointCount + 2)` followed by each
     * Unicode code point as an `UnsignedInteger`. The +2 leaves codes 0 and 1 for local /
     * global value-table hits.
     *
     * Iteration is per **code point**, not per UTF-16 unit — an astral character such as U+1F600
     * is one value, not two.
     */
    fun writeStringValue(w: BitWriter, s: String) {
        val cps = s.codePoints().toArray()
        writeUnsignedInteger(w, (cps.size + 2).toULong())
        for (cp in cps) writeUnsignedInteger(w, cp.toULong())
    }

    /**
     * Reads a string value at the given slot, resolving value-table hits against the reader's own
     * [BitReader.stringTable].
     *
     * The slot is the QName local part of the element or attribute whose value this is; EXI keeps
     * one local value partition per slot, plus one global partition per stream.
     *
     * There is deliberately no encoding counterpart. cbV2G never emits hits, every checked-in
     * vector is its output, and an encoder that started emitting them would invalidate all of them —
     * so [writeStringValue] stays miss-only while the decoder accepts what a conforming peer may
     * legitimately send.
     */
    fun readStringValue(r: BitReader, slot: String): String =
        r.stringTable.readStringValue(r, slot)

    /**
     * EXI Binary: the byte count as an Unsigned Integer, then the raw octets. hexBinary and
     * base64Binary are identical on the wire — the difference is only lexical, which EXI never sees.
     */
    fun writeBinary(w: BitWriter, data: ByteArray) {
        writeUnsignedInteger(w, data.size.toULong())
        for (b in data) w.writeBits(b.toUByte().toUInt(), 8)
    }

    fun readBinary(r: BitReader): ByteArray {
        val len = readUnsignedInteger(r).toInt()
        return ByteArray(len) { r.readBits(8).toByte() }
    }

    fun writeBoolean(w: BitWriter, value: Boolean) = w.writeBits(if (value) 1u else 0u, 1)

    fun readBoolean(r: BitReader): Boolean = r.readBits(1) != 0u
}
