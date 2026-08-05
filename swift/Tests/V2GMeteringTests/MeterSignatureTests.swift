#if canImport(CryptoKit)
import CryptoKit
import XCTest
@testable import V2GMetering

/// The Swift verifier against the **C# side's** corpus.
///
/// Held to bytes another implementation produced, not to its own output. Three ports of one layout,
/// each checked against itself, would agree perfectly and could be wrong together — the mirrored bug
/// this project has been bitten by before. The corpus is read out of the submodule rather than
/// copied, so the two cannot drift.
///
/// It is *not* conformance evidence. ISO 15118 defines the field and not its content, so there is no
/// reference encoder anywhere; what this proves is that the app and the station agree.
final class MeterSignatureTests: XCTestCase {

    struct Vector {
        let meterId: String
        let proto: Int
        let sessionId: [UInt8]
        let reading: UInt64
        let timestamp: Int64?
        let payload: [UInt8]
        let signature: [UInt8]
    }

    private static func hex(_ s: String) -> [UInt8] {
        stride(from: 0, to: s.count, by: 2).map {
            let i = s.index(s.startIndex, offsetBy: $0)
            return UInt8(s[i...s.index(i, offsetBy: 1)], radix: 16)!
        }
    }

    private static func corpus() throws -> (key: P256.Signing.PublicKey, vectors: [Vector]) {
        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("EVSimulatorApp.slnx").path) { break }
        }
        let url = dir.appendingPathComponent(
            "../../ISO15118ConformanceTests.Simulation/Vectors/Meter.signing.vectors.json")
        guard let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
              let raw = json["vectors"] as? [[String: Any]]
        else { throw XCTSkip("meter corpus not found at \(url.path)") }

        // An uncompressed point: 0x04 ‖ X ‖ Y.
        let key = try P256.Signing.PublicKey(x963Representation:
            Data([0x04] + hex(json["publicKeyX"] as! String) + hex(json["publicKeyY"] as! String)))

        return (key, raw.map {
            Vector(meterId:   $0["meterId"] as! String,
                   proto:     $0["protocol"] as! Int,
                   sessionId: hex($0["sessionId"] as! String),
                   reading:   UInt64($0["reading"] as! String)!,
                   timestamp: ($0["timestamp"] as? String).flatMap { Int64($0) },
                   payload:   hex($0["payload"] as! String),
                   signature: hex($0["signature"] as! String))
        })
    }

    func testTheCorpusLoaded() throws {
        let (_, vectors) = try Self.corpus()

        XCTAssertGreaterThanOrEqual(vectors.count, 8)
        XCTAssertTrue(vectors.contains { $0.proto == 20 }, "no -20 vector")
        XCTAssertTrue(vectors.contains { $0.timestamp == nil }, "no absent-timestamp vector")
    }

    /// The payload comparison, which is where a divergence is actually diagnosable.
    ///
    /// A failing signature says only "no". This says *which byte*, and it is the check that would
    /// catch a UTF-8 or endianness disagreement between the three languages.
    func testEveryPayloadIsRebuiltByteForByte() throws {
        let (_, vectors) = try Self.corpus()

        for v in vectors {
            let built = try MeterSignature.payload(protocol: v.proto, sessionId: v.sessionId,
                                                   meterId: v.meterId, reading: v.reading,
                                                   timestamp: v.timestamp)
            XCTAssertEqual(built, v.payload, "meterId \(v.meterId), protocol \(v.proto)")
        }
    }

    func testEverySignatureVerifies() throws {
        let (key, vectors) = try Self.corpus()

        for v in vectors {
            XCTAssertTrue(try MeterSignature.verify(v.signature, protocol: v.proto,
                                                    sessionId: v.sessionId, meterId: v.meterId,
                                                    reading: v.reading, timestamp: v.timestamp,
                                                    publicKey: key),
                          "meterId \(v.meterId), protocol \(v.proto)")
        }
    }

    /// A shaved reading must not verify — the whole reason the field is signed.
    func testATamperedReadingDoesNotVerify() throws {
        let (key, vectors) = try Self.corpus()
        let v = try XCTUnwrap(vectors.first { $0.reading > 100 })

        XCTAssertFalse(try MeterSignature.verify(v.signature, protocol: v.proto,
                                                 sessionId: v.sessionId, meterId: v.meterId,
                                                 reading: v.reading - 100, timestamp: v.timestamp,
                                                 publicKey: key))
    }

    /// The session binding, checked with the corpus's own pair: same meter, same reading, one byte
    /// of session id apart.
    func testAReadingFromAnotherSessionDoesNotVerify() throws {
        let (key, vectors) = try Self.corpus()
        let a = try XCTUnwrap(vectors.first { $0.sessionId == Self.hex("0102030405060708") && $0.proto == 2 })
        let b = try XCTUnwrap(vectors.first { $0.sessionId == Self.hex("0102030405060709") })

        XCTAssertFalse(try MeterSignature.verify(a.signature, protocol: a.proto,
                                                 sessionId: b.sessionId, meterId: a.meterId,
                                                 reading: a.reading, timestamp: a.timestamp,
                                                 publicKey: key))
    }

    /// A -2 reading is not a -20 reading. The corpus carries the same values under both, which is
    /// what makes this checkable rather than merely asserted.
    func testAReadingDoesNotCrossBetweenProtocols() throws {
        let (key, vectors) = try Self.corpus()
        let two = try XCTUnwrap(vectors.first { $0.proto == 2 && $0.meterId == "VAN*M1" })

        XCTAssertFalse(try MeterSignature.verify(two.signature, protocol: 20,
                                                 sessionId: two.sessionId, meterId: two.meterId,
                                                 reading: two.reading, timestamp: two.timestamp,
                                                 publicKey: key))
    }

    /// The length-prefix collision pair, from the corpus rather than constructed here.
    func testTwoReadingsThatWouldCollideWithoutLengthPrefixesDoNot() throws {
        let (_, vectors) = try Self.corpus()
        let a1 = try XCTUnwrap(vectors.first { $0.meterId == "A1" })
        let a  = try XCTUnwrap(vectors.first { $0.meterId == "A" })

        XCTAssertNotEqual(a1.payload, a.payload)
    }

    func testAnUnsupportedProtocolIsRefusedByName() {
        XCTAssertThrowsError(try MeterSignature.payload(protocol: 3, sessionId: [], meterId: "M",
                                                        reading: 0, timestamp: nil)) {
            XCTAssertEqual($0 as? MeterSignature.Error, .unsupportedProtocol(3))
        }
    }

    /// A DER signature is refused on length rather than reaching the crypto.
    func testADerShapedSignatureIsRefused() throws {
        let (key, vectors) = try Self.corpus()
        let v = try XCTUnwrap(vectors.first)

        XCTAssertFalse(try MeterSignature.verify([UInt8](repeating: 0, count: 70), protocol: v.proto,
                                                 sessionId: v.sessionId, meterId: v.meterId,
                                                 reading: v.reading, timestamp: v.timestamp,
                                                 publicKey: key))
    }
}
#endif
