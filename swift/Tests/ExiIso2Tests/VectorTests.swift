import XCTest
@testable import ExiIso2

/// Gate 1 for the ISO 15118-2 codec: EVerest's libcbv2g at a pinned commit, read straight out of
/// the submodule — the same corpus the C# and Kotlin suites use.
///
/// Unlike AppProtocol, the -2 vectors carry only `expectedHex`, so the loop is decode → re-encode →
/// compare. That exercises the decoder as well as the encoder, but it is worth being clear about
/// what it cannot do: both halves are ours, so a mistake mirrored in the two directions
/// round-trips perfectly. Only the cross-emitter comparison in the .NET suite rules that out — and
/// one such bug (SalesTariffEntryType writing a 1-bit selector where cbV2G writes 2) survived a
/// clean compile and a clean round trip before that comparison caught it.
final class Iso2VectorTests: XCTestCase {

    private struct Vector {
        let name: String
        let messageType: String
        let expected: [UInt8]
    }

    private static func repositoryRoot() throws -> URL {
        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("libs/Vanaheimr.V2G.Exi").path) {
                return dir
            }
        }
        throw XCTSkip("repository root not found from \(#filePath)")
    }

    private static func loadVectors() throws -> [Vector] {
        let url = try repositoryRoot()
            .appendingPathComponent("libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Tests/Vectors/Iso15118_2.vectors.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        guard let raw = json?["vectors"] as? [[String: Any]] else {
            throw XCTSkip("vector file has no 'vectors' array: \(url.path)")
        }
        return raw.compactMap { v in
            guard let name = v["name"] as? String,
                  let type = v["messageType"] as? String,
                  let hex = v["expectedHex"] as? String else { return nil }
            return Vector(name: name, messageType: type, expected: bytes(fromHex: hex))
        }
    }

    private static func bytes(fromHex hex: String) -> [UInt8] {
        hex.split(whereSeparator: { $0 == " " || $0 == "\n" }).compactMap { UInt8($0, radix: 16) }
    }

    private static func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined(separator: " ")
    }

    /// The corpus has to actually load, or every assertion below is vacuously true.
    func testCorpusIsTheExpectedSize() throws {
        XCTAssertEqual(try Self.loadVectors().count, 39)
    }

    func testEveryVectorDecodes() throws {
        for v in try Self.loadVectors() {
            XCTAssertNoThrow(try Iso15118_2Codec.decodeAny(v.expected), "\(v.name) (\(v.messageType))")
        }
    }

    func testEveryVectorReEncodesToTheReferenceBytes() throws {
        var failures: [String] = []

        for v in try Self.loadVectors() {
            do {
                guard let msg = try Iso15118_2Codec.decodeAny(v.expected) as? V2G_Message else {
                    failures.append("\(v.name): decodeAny returned something other than V2G_Message")
                    continue
                }
                let actual = Iso15118_2Codec.encode(msg)
                if actual != v.expected {
                    failures.append("\(v.name) (\(v.messageType)):\n" +
                                    "  expected \(Self.hex(v.expected))\n" +
                                    "  actual   \(Self.hex(actual))")
                }
            } catch {
                failures.append("\(v.name) (\(v.messageType)): \(error)")
            }
        }

        // Reported together: with 39 vectors over one grammar, a single wrong bit width usually
        // moves several at once, and the pattern says more than the first failure does.
        XCTAssertTrue(failures.isEmpty,
                      "\(failures.count) of 39 vectors differ:\n" + failures.joined(separator: "\n"))
    }
}
