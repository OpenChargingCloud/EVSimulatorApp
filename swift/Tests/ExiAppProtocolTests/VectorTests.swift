import XCTest
@testable import ExiAppProtocol

/// Gate 1 for the Swift back end: the generated codec against EVerest's libcbv2g at a pinned
/// commit — the same corpus the C# and Kotlin suites read, taken straight from the submodule
/// rather than copied, so the three back ends are checked against one oracle and not against
/// each other.
///
/// AppProtocol vectors carry `expectedHex` for the *encode* direction. Each is also decoded and
/// re-encoded, which exercises the decoder — but that half cannot catch a bug mirrored in both
/// directions, which is what the cross-emitter comparison is for.
final class AppProtocolVectorTests: XCTestCase {

    private struct Vector {
        let name: String
        let messageType: String
        let input: [String: Any]
        let expected: [UInt8]
    }

    /// Walks up from the test bundle to the repository root, the same way the Kotlin vector tests
    /// locate it — the corpus lives in the submodule, not in this package.
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
            .appendingPathComponent("libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/WWCP_ISO15118_EXI_Tests/Vectors/AppProtocol.vectors.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        guard let raw = json?["vectors"] as? [[String: Any]] else {
            throw XCTSkip("vector file has no 'vectors' array: \(url.path)")
        }
        return raw.compactMap { v in
            guard let name = v["name"] as? String,
                  let type = v["messageType"] as? String,
                  let input = v["input"] as? [String: Any],
                  let hex = v["expectedHex"] as? String else { return nil }
            return Vector(name: name, messageType: type, input: input, expected: bytes(fromHex: hex))
        }
    }

    private static func bytes(fromHex hex: String) -> [UInt8] {
        hex.split(whereSeparator: { $0 == " " || $0 == "\n" })
           .compactMap { UInt8($0, radix: 16) }
    }

    private static func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined(separator: " ")
    }

    // MARK: building a message from a vector's `input`

    private func makeReq(_ input: [String: Any]) throws -> SupportedAppProtocolReq {
        guard let entries = input["appProtocols"] as? [[String: Any]] else {
            throw XCTSkip("vector input has no 'appProtocols'")
        }
        return SupportedAppProtocolReq(appProtocol: entries.map {
            AppProtocolType(
                protocolNamespace:  $0["protocolNamespace"] as? String ?? "",
                versionNumberMajor: UInt32(($0["versionNumberMajor"] as? Int) ?? 0),
                versionNumberMinor: UInt32(($0["versionNumberMinor"] as? Int) ?? 0),
                schemaID:           UInt8(($0["schemaId"] as? Int) ?? 0),
                priority:           UInt8(($0["priority"] as? Int) ?? 0))
        })
    }

    private func makeRes(_ input: [String: Any]) throws -> SupportedAppProtocolRes {
        guard let code = input["code"] as? String,
              let responseCode = ResponseCode.allCases.first(where: { "\($0)" == code }) else {
            throw XCTSkip("unknown ResponseCode in vector input")
        }
        let schemaID = (input["schemaId"] as? Int).map { UInt8($0) }
        return SupportedAppProtocolRes(responseCode: responseCode, schemaID: schemaID)
    }

    // MARK: the gate

    func testEveryVectorEncodesToTheReferenceBytes() throws {
        let vectors = try Self.loadVectors()
        XCTAssertGreaterThan(vectors.count, 0, "no vectors loaded — the corpus path is wrong")

        for v in vectors {
            let actual: [UInt8]
            switch v.messageType {
            case "SupportedAppProtocolReq": actual = SupportedAppProtocolCodec.encode(try makeReq(v.input))
            case "SupportedAppProtocolRes": actual = SupportedAppProtocolCodec.encode(try makeRes(v.input))
            default: continue
            }
            XCTAssertEqual(actual, v.expected,
                           "\(v.name): expected \(Self.hex(v.expected)), got \(Self.hex(actual))")
        }
    }

    func testEveryVectorDecodesAndReEncodesToItself() throws {
        for v in try Self.loadVectors() {
            let decoded = try SupportedAppProtocolCodec.decodeAny(v.expected)
            let reEncoded: [UInt8]
            switch decoded {
            case let m as SupportedAppProtocolReq: reEncoded = SupportedAppProtocolCodec.encode(m)
            case let m as SupportedAppProtocolRes: reEncoded = SupportedAppProtocolCodec.encode(m)
            default: return XCTFail("\(v.name): decodeAny returned \(type(of: decoded))")
            }
            XCTAssertEqual(reEncoded, v.expected, "\(v.name): re-encode differs from the reference")
        }
    }

    /// The corpus is only a gate if it is actually there — a silently empty load would make every
    /// assertion above vacuous.
    func testCorpusIsTheExpectedSize() throws {
        XCTAssertEqual(try Self.loadVectors().count, 17)
    }
}
