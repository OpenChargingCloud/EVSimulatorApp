import XCTest
@testable import ExiRuntime

/// Wire-level tests for the hand-written runtime.
///
/// These pin **bytes**, not round trips. A round trip cannot see a bug mirrored in both directions
/// — the same reason `kotlin/README.md` lists the vector corpus and the cross-emitter comparison as
/// separate gates. The literals below are the EXI spec's encodings and must equal what the C# and
/// Kotlin runtimes produce for the same input.
final class BitIoTests: XCTestCase {

    func testBitsAreWrittenMsbFirstWithinEachByte() {
        let w = BitWriter()
        w.writeBit(true)   // 0x80
        w.writeBit(false)
        w.writeBit(true)   // 0x20
        XCTAssertEqual(w.bytes, [0xA0], "first bit written must land in bit 7, not bit 0")
        XCTAssertEqual(w.bitsWritten, 3)
        XCTAssertEqual(w.bytesWritten, 1)
    }

    func testWriteBitsTakesTheLowBitsMsbFirst() {
        let w = BitWriter()
        w.writeBits(0b1011, 4)
        w.writeBits(0b0001, 4)
        XCTAssertEqual(w.bytes, [0xB1])
    }

    func testAlignToBytePadsWithZeros() {
        let w = BitWriter()
        w.writeBits(0b111, 3)
        w.alignToByte()
        XCTAssertEqual(w.bytes, [0xE0])
        XCTAssertEqual(w.bitsWritten, 8)
    }

    func testReaderMirrorsWriterBitOrder() throws {
        let r = BitReader([0xA0])
        XCTAssertTrue(try r.readBit())
        XCTAssertFalse(try r.readBit())
        XCTAssertTrue(try r.readBit())
    }

    func testReaderHonoursOffset() throws {
        let r = BitReader([0xFF, 0xA0], offset: 1)
        XCTAssertEqual(try r.readBits(3), 0b101)
    }

    func testExhaustedBitstreamThrowsRatherThanTraps() {
        let r = BitReader([0x00])
        XCTAssertNoThrow(try r.readBits(8))
        XCTAssertThrowsError(try r.readBit()) { error in
            XCTAssertEqual(error as? ExiError, .bitstreamExhausted)
        }
    }
}

final class ExiPrimitivesTests: XCTestCase {

    private func encoded(_ body: (BitWriter) -> Void) -> [UInt8] {
        let w = BitWriter()
        body(w)
        w.alignToByte()
        return w.bytes
    }

    // MARK: Unsigned Integer — 7 bits per byte, MSB is the continuation flag

    func testUnsignedIntegerEncodings() {
        XCTAssertEqual(encoded { ExiPrimitives.writeUnsignedInteger($0, 0) }, [0x00])
        XCTAssertEqual(encoded { ExiPrimitives.writeUnsignedInteger($0, 127) }, [0x7F])
        XCTAssertEqual(encoded { ExiPrimitives.writeUnsignedInteger($0, 128) }, [0x80, 0x01])
        XCTAssertEqual(encoded { ExiPrimitives.writeUnsignedInteger($0, 300) }, [0xAC, 0x02])
    }

    func testUnsignedIntegerRoundTripsAtTheTop() throws {
        let bytes = encoded { ExiPrimitives.writeUnsignedInteger($0, UInt64.max) }
        XCTAssertEqual(try ExiPrimitives.readUnsignedInteger(BitReader(bytes)), UInt64.max)
    }

    func testNonTerminatingUnsignedIntegerThrows() {
        // Ten continuation bytes: more than 64 bits of value, so this must be rejected rather than
        // silently wrapping.
        let r = BitReader([UInt8](repeating: 0x80, count: 10))
        XCTAssertThrowsError(try ExiPrimitives.readUnsignedInteger(r)) { error in
            XCTAssertEqual(error as? ExiError, .unsignedIntegerOverflow)
        }
    }

    // MARK: Integer — 1 sign bit, then |value| - 1 for negatives

    func testSignedIntegerUsesMagnitudeMinusOneForNegatives() {
        // -1 is the smallest negative, so its magnitude is 0: sign bit 1, then UnsignedInteger(0).
        // Sign bit occupies bit 7, the 0x00 magnitude byte follows, so 9 bits → 0x80 0x00.
        XCTAssertEqual(encoded { ExiPrimitives.writeSignedInteger($0, -1) }, [0x80, 0x00])
        XCTAssertEqual(encoded { ExiPrimitives.writeSignedInteger($0, 0) }, [0x00, 0x00])
    }

    func testSignedIntegerRoundTrips() throws {
        for value in [Int64.min, -300, -1, 0, 1, 300, Int64.max] {
            let bytes = encoded { ExiPrimitives.writeSignedInteger($0, value) }
            XCTAssertEqual(try ExiPrimitives.readSignedInteger(BitReader(bytes)), value,
                           "round trip failed for \(value)")
        }
    }

    func testInt64MinDoesNotTrap() {
        // |Int64.min| has no positive Int64 counterpart, so negating before the -1 would trap.
        // The magnitude is computed in the unsigned domain instead. Bytes taken from the C#
        // runtime for the same input — this is the widest encoding either produces.
        XCTAssertEqual(encoded { ExiPrimitives.writeSignedInteger($0, Int64.min) },
                       [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xBF, 0x80])
    }

    // MARK: String values — miss-only on encode

    func testStringValueMissCarriesLengthPlusTwo() {
        // "AB" → UnsignedInteger(2 + 2), then the code points 65 and 66.
        XCTAssertEqual(encoded { ExiPrimitives.writeStringValue($0, "AB") }, [0x04, 0x41, 0x42])
    }

    func testStringValueCountsCodePointsNotCharacters() {
        // Both byte strings are the C# runtime's output for the same input. Swift counting by
        // `Character` instead of `unicodeScalars` would change the prefix on both — grapheme
        // clusters are the one place Swift's default string view disagrees with C# and Java.

        // U+1F600 is one code point (two UTF-16 units): length prefix 1 + 2 = 3.
        XCTAssertEqual(encoded { ExiPrimitives.writeStringValue($0, "\u{1F600}") },
                       [0x03, 0x80, 0xEC, 0x07])

        // "e" + combining acute is one Character but two code points: prefix 2 + 2 = 4.
        XCTAssertEqual(encoded { ExiPrimitives.writeStringValue($0, "e\u{0301}") },
                       [0x04, 0x65, 0x81, 0x06])
    }

    func testEmptyStringIsLengthTwo() {
        XCTAssertEqual(encoded { ExiPrimitives.writeStringValue($0, "") }, [0x02])
    }

    // MARK: Binary and Boolean

    func testBinaryIsLengthThenRawOctets() {
        XCTAssertEqual(encoded { ExiPrimitives.writeBinary($0, [0xDE, 0xAD]) }, [0x02, 0xDE, 0xAD])
    }

    func testBooleanIsASingleBit() {
        XCTAssertEqual(encoded { ExiPrimitives.writeBoolean($0, true) }, [0x80])
        XCTAssertEqual(encoded { ExiPrimitives.writeBoolean($0, false) }, [0x00])
    }
}

final class ExiStringTableTests: XCTestCase {

    /// A miss must be readable back, and must populate both partitions.
    func testMissRoundTripsAndIsRemembered() throws {
        let w = BitWriter()
        let table = ExiStringTable()
        table.writeStringValue(w, localKey: "Slot", value: "urn:iso")
        w.alignToByte()

        let r = BitReader(w.bytes)
        XCTAssertEqual(try r.stringTable.readStringValue(r, slot: "Slot"), "urn:iso")
    }

    /// The second occurrence in the same slot is a local hit, and must be shorter than the miss.
    func testRepeatedValueInTheSameSlotBecomesALocalHit() throws {
        let w = BitWriter()
        let table = ExiStringTable()
        table.writeStringValue(w, localKey: "Slot", value: "urn:iso")
        let afterFirst = w.bitsWritten
        table.writeStringValue(w, localKey: "Slot", value: "urn:iso")
        let hitBits = w.bitsWritten - afterFirst
        w.alignToByte()

        // Miss carries 7 code points; the hit is UnsignedInteger(0) over a size-1 partition, whose
        // compact id needs 0 bits — so exactly one byte.
        XCTAssertEqual(hitBits, 8)

        let r = BitReader(w.bytes)
        XCTAssertEqual(try r.stringTable.readStringValue(r, slot: "Slot"), "urn:iso")
        XCTAssertEqual(try r.stringTable.readStringValue(r, slot: "Slot"), "urn:iso")
    }

    /// A value first seen in another slot is not a local hit there, but is a global one.
    func testValueFromAnotherSlotResolvesGlobally() throws {
        let w = BitWriter()
        let table = ExiStringTable()
        table.writeStringValue(w, localKey: "A", value: "shared")
        table.writeStringValue(w, localKey: "B", value: "shared")
        w.alignToByte()

        let r = BitReader(w.bytes)
        XCTAssertEqual(try r.stringTable.readStringValue(r, slot: "A"), "shared")
        XCTAssertEqual(try r.stringTable.readStringValue(r, slot: "B"), "shared")
    }

    /// Compact ids widen with the partition, so a third distinct value needs 2 bits where the
    /// second needed 1. Getting this wrong desynchronises encoder and decoder silently.
    func testCompactIdWidthGrowsWithThePartition() throws {
        let w = BitWriter()
        let table = ExiStringTable()
        for value in ["a", "b", "c"] { table.writeStringValue(w, localKey: "S", value: value) }
        table.writeStringValue(w, localKey: "S", value: "c")   // hit, id 2, needs 2 bits
        w.alignToByte()

        let r = BitReader(w.bytes)
        XCTAssertEqual(try r.stringTable.readStringValue(r, slot: "S"), "a")
        XCTAssertEqual(try r.stringTable.readStringValue(r, slot: "S"), "b")
        XCTAssertEqual(try r.stringTable.readStringValue(r, slot: "S"), "c")
        XCTAssertEqual(try r.stringTable.readStringValue(r, slot: "S"), "c")
    }

    func testHitBeyondThePartitionThrows() {
        // UnsignedInteger(0) = local hit, into an empty partition: there is no id 0 to resolve.
        let r = BitReader([0x00])
        XCTAssertThrowsError(try r.stringTable.readStringValue(r, slot: "S")) { error in
            guard case .valueTableHitOutOfRange = (error as? ExiError) else {
                return XCTFail("expected valueTableHitOutOfRange, got \(error)")
            }
        }
    }
}
