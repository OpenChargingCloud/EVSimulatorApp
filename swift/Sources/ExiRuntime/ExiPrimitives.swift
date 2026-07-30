/// EXI primitive type codecs — a faithful port of the C# `ExiPrimitives`.
///
/// String values are **miss-only** on the encode side (verbatim value, `length + 2` prefix),
/// matching the ISO 15118 wire reality: EVerest's cbexigen/cbV2G never emits value-table hits.
/// The decode side resolves hits, because they are legal EXI and a conforming peer (EXIficient,
/// Josev) may send them — see ``ExiStringTable``.
///
/// Float, Decimal and DateTime are deliberately absent — the -2/-20 schemas model physical
/// quantities as multiplier/value integer pairs instead.
public enum ExiPrimitives {

    /// EXI Unsigned Integer: 7 bits of value per byte, MSB = continuation flag.
    public static func writeUnsignedInteger(_ w: BitWriter, _ value: UInt64) {
        var v = value
        repeat {
            var chunk = UInt32(v & 0x7F)
            v >>= 7
            if v != 0 { chunk |= 0x80 }
            w.writeBits(chunk, 8)
        } while v != 0
    }

    public static func readUnsignedInteger(_ r: BitReader) throws -> UInt64 {
        var value: UInt64 = 0
        var shift = 0
        while true {
            let chunk = try r.readBits(8)
            value |= UInt64(chunk & 0x7F) << UInt64(shift)
            if chunk & 0x80 == 0 { return value }
            shift += 7
            if shift > 63 { throw ExiError.unsignedIntegerOverflow }
        }
    }

    /// EXI Integer: a 1-bit sign (0 = non-negative) followed by the magnitude as an Unsigned
    /// Integer. For negative values the magnitude is `|value| - 1`, so -1 maps to 0 and zero has a
    /// single representation.
    public static func writeSignedInteger(_ w: BitWriter, _ value: Int64) {
        if value < 0 {
            w.writeBits(1, 1)
            // Computed in the unsigned domain: Int64.min has no positive counterpart, so negating
            // it before the -1 would trap.
            writeUnsignedInteger(w, UInt64(bitPattern: ~value))
        } else {
            w.writeBits(0, 1)
            writeUnsignedInteger(w, UInt64(value))
        }
    }

    public static func readSignedInteger(_ r: BitReader) throws -> Int64 {
        let negative = try r.readBits(1) != 0
        let mag = try readUnsignedInteger(r)
        if negative {
            guard mag <= UInt64(Int64.max) else { throw ExiError.signedIntegerOutOfRange }
            return -Int64(mag) - 1
        }
        guard mag <= UInt64(Int64.max) else { throw ExiError.signedIntegerOutOfRange }
        return Int64(mag)
    }

    /// EXI string value, "miss" case: `UnsignedInteger(codePointCount + 2)` followed by each
    /// Unicode code point as an `UnsignedInteger`. The +2 leaves codes 0 and 1 for local / global
    /// value-table hits.
    ///
    /// Iteration is per **code point** (`unicodeScalars`), not per `Character`: a grapheme cluster
    /// such as "e" + combining accent is two values, and an astral character such as U+1F600 is
    /// one — matching what the C# and Kotlin ports count.
    public static func writeStringValue(_ w: BitWriter, _ s: String) {
        let scalars = Array(s.unicodeScalars)
        writeUnsignedInteger(w, UInt64(scalars.count + 2))
        for scalar in scalars { writeUnsignedInteger(w, UInt64(scalar.value)) }
    }

    /// Reads a string value at the given slot, resolving value-table hits against the reader's own
    /// ``BitReader/stringTable``.
    ///
    /// The slot is the QName local part of the element or attribute whose value this is; EXI keeps
    /// one local value partition per slot, plus one global partition per stream.
    public static func readStringValue(_ r: BitReader, slot: String) throws -> String {
        try r.stringTable.readStringValue(r, slot: slot)
    }

    /// EXI Binary: the byte count as an Unsigned Integer, then the raw octets. hexBinary and
    /// base64Binary are identical on the wire — the difference is only lexical, which EXI never sees.
    public static func writeBinary(_ w: BitWriter, _ data: [UInt8]) {
        writeUnsignedInteger(w, UInt64(data.count))
        for b in data { w.writeBits(UInt32(b), 8) }
    }

    public static func readBinary(_ r: BitReader) throws -> [UInt8] {
        let len = try readUnsignedInteger(r)
        var out = [UInt8]()
        out.reserveCapacity(Int(min(len, 4096)))
        for _ in 0..<len { out.append(UInt8(try r.readBits(8))) }
        return out
    }

    public static func writeBoolean(_ w: BitWriter, _ value: Bool) {
        w.writeBits(value ? 1 : 0, 1)
    }

    public static func readBoolean(_ r: BitReader) throws -> Bool {
        try r.readBits(1) != 0
    }
}
