import XCTest
@testable import ExiIso20Common

/// Gate 1 for the generated Iso15118_20.CommonMessages codec: decode `expectedHex`, re-encode, require the bytes
/// back. The corpus is EVerest's libcbv2g at a pinned commit, read out of the submodule — the same
/// one the C# and Kotlin suites use.
///
/// Unlike ISO 15118-2 this schema set has one global element per message rather than a single
/// envelope, so re-encoding needs an explicit dispatch: `decodeAny` can only promise `Any`. The
/// arms below are generated from the codec's own overloads, so a message type that appears without
/// one would not silently fall through — it would fail to build.
///
/// What the round trip does *not* prove: both directions are ours, so a mistake mirrored across
/// them survives it. That is what the cross-emitter comparison in the .NET suite is for.
final class ExiIso20CommonVectorTests: XCTestCase {

    private static func repositoryRoot() throws -> URL {
        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("libs/Vanaheimr.V2G.Exi").path) { return dir }
        }
        throw XCTSkip("repository root not found from \(#filePath)")
    }

    private static func loadVectors() throws -> [(name: String, bytes: [UInt8])] {
        let url = try repositoryRoot().appendingPathComponent(
            "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/Vanaheimr.V2G.Exi.Tests/Vectors/Iso15118_20.CommonMessages.vectors.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        guard let raw = json?["vectors"] as? [[String: Any]] else {
            throw XCTSkip("vector file has no 'vectors' array: \(url.path)")
        }
        return raw.compactMap { v in
            guard let n = v["name"] as? String, let h = v["expectedHex"] as? String else { return nil }
            return (n, h.split(whereSeparator: { $0 == " " || $0 == "\n" }).compactMap { UInt8($0, radix: 16) })
        }
    }

    private static func hex(_ b: [UInt8]) -> String {
        b.map { String(format: "%02x", $0) }.joined(separator: " ")
    }

    private func reencode(_ m: Any) -> [UInt8]? {
        switch m {
        case let m as SessionSetupReq: return CommonMessagesCodec.encode(m)
        case let m as SessionSetupRes: return CommonMessagesCodec.encode(m)
        case let m as AuthorizationSetupReq: return CommonMessagesCodec.encode(m)
        case let m as AuthorizationSetupRes: return CommonMessagesCodec.encode(m)
        case let m as AuthorizationReq: return CommonMessagesCodec.encode(m)
        case let m as AuthorizationRes: return CommonMessagesCodec.encode(m)
        case let m as ServiceDiscoveryReq: return CommonMessagesCodec.encode(m)
        case let m as ServiceDiscoveryRes: return CommonMessagesCodec.encode(m)
        case let m as ServiceDetailReq: return CommonMessagesCodec.encode(m)
        case let m as ServiceDetailRes: return CommonMessagesCodec.encode(m)
        case let m as ServiceSelectionReq: return CommonMessagesCodec.encode(m)
        case let m as ServiceSelectionRes: return CommonMessagesCodec.encode(m)
        case let m as ScheduleExchangeReq: return CommonMessagesCodec.encode(m)
        case let m as ScheduleExchangeRes: return CommonMessagesCodec.encode(m)
        case let m as PowerDeliveryReq: return CommonMessagesCodec.encode(m)
        case let m as PowerDeliveryRes: return CommonMessagesCodec.encode(m)
        case let m as MeteringConfirmationReq: return CommonMessagesCodec.encode(m)
        case let m as MeteringConfirmationRes: return CommonMessagesCodec.encode(m)
        case let m as SessionStopReq: return CommonMessagesCodec.encode(m)
        case let m as SessionStopRes: return CommonMessagesCodec.encode(m)
        case let m as CertificateInstallationReq: return CommonMessagesCodec.encode(m)
        case let m as CertificateInstallationRes: return CommonMessagesCodec.encode(m)
        case let m as VehicleCheckInReq: return CommonMessagesCodec.encode(m)
        case let m as VehicleCheckInRes: return CommonMessagesCodec.encode(m)
        case let m as VehicleCheckOutReq: return CommonMessagesCodec.encode(m)
        case let m as VehicleCheckOutRes: return CommonMessagesCodec.encode(m)
        case let m as SignedInstallationData: return CommonMessagesCodec.encode(m)
        case let m as SignedMeteringData: return CommonMessagesCodec.encode(m)
        default: return nil
        }
    }

    /// The corpus has to actually load, or every assertion below is vacuously true.
    func testCorpusIsTheExpectedSize() throws {
        XCTAssertEqual(try Self.loadVectors().count, 26)
    }

    func testEveryVectorReEncodesToTheReferenceBytes() throws {
        var failures: [String] = []

        for v in try Self.loadVectors() {
            do {
                let decoded = try CommonMessagesCodec.decodeAny(v.bytes)
                guard let actual = reencode(decoded) else {
                    failures.append("\(v.name): no encode overload for \(type(of: decoded))")
                    continue
                }
                if actual != v.bytes {
                    failures.append("\(v.name):\n  expected \(Self.hex(v.bytes))\n  actual   \(Self.hex(actual))")
                }
            } catch {
                failures.append("\(v.name): \(error)")
            }
        }

        XCTAssertTrue(failures.isEmpty,
                      "\(failures.count) of 26 vectors differ:\n" + failures.joined(separator: "\n"))
    }
}
