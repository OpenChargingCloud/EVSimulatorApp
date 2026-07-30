/// Errors a decoder raises on malformed input.
///
/// Only the **read** path throws. That split is deliberate and shapes every generated decoder:
/// a decoder faces bytes from the network, so bad input must be a recoverable error rather than a
/// trap, while an encoder is driven by our own value types and can only fail through a programmer
/// error — those stay `precondition`s. C# and Kotlin do not need this distinction because both have
/// unchecked exceptions; Swift has none, so it is expressed in the signatures instead.
public enum ExiError: Error, Equatable {
    /// The bitstream ended while more bits were required.
    case bitstreamExhausted
    /// An EXI Unsigned Integer did not terminate within 64 bits of value.
    case unsignedIntegerOverflow
    /// An EXI Integer's magnitude does not fit a signed 64-bit value.
    case signedIntegerOutOfRange
    /// A string value-table hit named a slot the partition does not have.
    case valueTableHitOutOfRange(id: Int, partitionSize: Int)
    /// A code point in a string value is not a valid Unicode scalar.
    case invalidCodePoint(UInt64)
    /// The stream does not start with the expected EXI header byte.
    case invalidHeader
    /// The document element selector named a production the schema set does not have.
    case unknownDocumentIndex(UInt32)
    /// A grammar event code is not one this position allows — named by the construct that read it.
    case invalidEventCode(String)
    /// An enumeration index is outside the type's member list.
    case unknownEnumValue(type: String, index: UInt32)
    /// The stream contains a construct the generated codec models as absent-only — the XMLDSig
    /// elements the generator leaves opaque. Reading one is not a malformed stream, it is a gap.
    case unsupportedConstruct(String)
}

/// Bit-level writer, MSB-first within each byte to match EXI bit-packed alignment: the first bit
/// written occupies bit 7 (0x80) of the first byte, the second bit 6 (0x40), and so on.
///
/// A faithful port of the C# `BitWriter` and its Kotlin counterpart; all three must agree bit for
/// bit. **One divergence, in the API rather than the output:** C#/Kotlin write into a
/// caller-supplied array at an offset, which Swift cannot express — arrays are value types, so a
/// caller would not see the mutation. The writer therefore owns its buffer and grows it. Callers
/// that reserved a leading byte for the EXI header now simply write the header through the writer.
public final class BitWriter {

    private var buffer: [UInt8] = []
    private var bitPos: Int = 0

    /// - Parameter capacity: optional hint; purely an allocation optimisation.
    public init(capacity: Int = 0) {
        if capacity > 0 { buffer.reserveCapacity(capacity) }
    }

    public var bitsWritten: Int { bitPos }
    public var bytesWritten: Int { (bitPos + 7) >> 3 }

    /// The bytes written so far, zero-padded to a byte boundary.
    public var bytes: [UInt8] { Array(buffer.prefix(bytesWritten)) }

    /// Write the lowest `numBits` of `value`, MSB first.
    public func writeBits(_ value: UInt32, _ numBits: Int) {
        precondition((0...32).contains(numBits), "numBits out of range: \(numBits)")
        guard numBits > 0 else { return }
        for i in stride(from: numBits - 1, through: 0, by: -1) {
            writeBit((value >> UInt32(i)) & 1 != 0)
        }
    }

    public func writeBit(_ b: Bool) {
        let byteIdx = bitPos >> 3
        if byteIdx >= buffer.count { buffer.append(0) }
        let mask = UInt8(1 << (7 - (bitPos & 7)))
        // Overwrite the target bit rather than only OR-ing 1s, so a rewound writer cannot leave
        // stale 1-bits behind.
        if b { buffer[byteIdx] |= mask } else { buffer[byteIdx] &= ~mask }
        bitPos += 1
    }

    /// Pad to the next byte boundary with zero bits.
    public func alignToByte() {
        let rem = bitPos & 7
        if rem != 0 {
            for _ in 0..<(8 - rem) { writeBit(false) }
        }
    }
}

/// Bit-level reader, MSB-first to match EXI bit-packed alignment. A faithful port of the C#
/// `BitReader`.
public final class BitReader {

    private let buffer: [UInt8]
    private let offset: Int
    private var bitPos: Int = 0

    /// The EXI string value-table partitions for this stream.
    ///
    /// It hangs off the reader so the generated decoders need no extra parameter threaded through
    /// every call — a value read only has to name its own slot. There is deliberately no
    /// counterpart on ``BitWriter``: cbV2G is miss-only, every checked-in vector is its output, and
    /// an encoder that started emitting hits would invalidate all of them.
    public private(set) lazy var stringTable = ExiStringTable()

    /// - Parameter offset: byte offset the bitstream starts at.
    public init(_ buffer: [UInt8], offset: Int = 0) {
        self.buffer = buffer
        self.offset = offset
    }

    public var bitsRead: Int { bitPos }
    public var bytesConsumed: Int { (bitPos + 7) >> 3 }

    public func readBit() throws -> Bool {
        let byteIdx = offset + (bitPos >> 3)
        guard byteIdx < buffer.count else { throw ExiError.bitstreamExhausted }
        let bit = (buffer[byteIdx] >> UInt8(7 - (bitPos & 7))) & 1 != 0
        bitPos += 1
        return bit
    }

    public func readBits(_ numBits: Int) throws -> UInt32 {
        precondition((0...32).contains(numBits), "numBits out of range: \(numBits)")
        var value: UInt32 = 0
        for _ in 0..<numBits {
            value = (value << 1) | (try readBit() ? 1 : 0)
        }
        return value
    }
}
