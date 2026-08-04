import XCTest
@testable import V2GDispatch
import V2GTP

/// The payload-type → codec wiring.
///
/// Frames are built from **real cbV2G vectors** — the first of each set's corpus — rather than
/// hand-made payloads, and the assertion is on the module the decoded message came from. That is
/// what makes a mis-wired payload type fail: pointing AC's id at the DC codec would still decode
/// *something*, but not into `ExiIso20AC`.
final class V2GTPDispatcherTests: XCTestCase {

    private static func repositoryRoot() throws -> URL {
        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("libs/Vanaheimr.V2G.Exi").path) { return dir }
        }
        throw XCTSkip("repository root not found from \(#filePath)")
    }

    /// The first vector of a corpus, as bytes.
    private static func firstVector(_ corpus: String) throws -> [UInt8] {
        let url = try repositoryRoot().appendingPathComponent(
            "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/Vanaheimr.V2G.Exi.Tests/Vectors/\(corpus).vectors.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        guard let raw = json?["vectors"] as? [[String: Any]],
              let hex = raw.first?["expectedHex"] as? String else {
            throw XCTSkip("no vectors in \(corpus)")
        }
        return hex.split(whereSeparator: { $0 == " " || $0 == "\n" }).compactMap { UInt8($0, radix: 16) }
    }

    private func module(of value: Any) -> String {
        String(reflecting: type(of: value)).split(separator: ".").first.map(String.init) ?? ""
    }

    // (message set, its corpus, the module its types live in)
    private static let wiring: [(MessageSet, String, String)] = [
        (.iso15118_2,          "Iso15118_2",                 "ExiIso2"),
        (.iso20CommonMessages, "Iso15118_20.CommonMessages", "ExiIso20Common"),
        (.iso20AC,             "Iso15118_20.AC",             "ExiIso20AC"),
        (.iso20DC,             "Iso15118_20.DC",             "ExiIso20DC"),
        (.iso20ACDP,           "Iso15118_20.ACDP",           "ExiIso20ACDP"),
    ]

    func testEachPayloadTypeResolvesToItsOwnCodec() throws {
        for (set, corpus, expectedModule) in Self.wiring {
            let payload = try Self.firstVector(corpus)
            let frame = V2GTPDispatcher.encode(set, payload)

            guard case let .decoded(decodedSet, message) = try V2GTPDispatcher.decode(frame) else {
                return XCTFail("\(corpus): the dispatcher refused a frame it built itself")
            }

            XCTAssertEqual(decodedSet, set, "\(corpus): resolved to the wrong message set")
            XCTAssertEqual(module(of: message), expectedModule,
                           "\(corpus): decoded into the wrong module — the payload type is mis-wired")
        }
    }

    /// Framing must not disturb the payload: what comes back out is what went in.
    func testFramingPreservesThePayload() throws {
        let payload = try Self.firstVector("Iso15118_20.DC")
        let frame = V2GTPDispatcher.encode(.iso20DC, payload)

        XCTAssertEqual(frame.count, V2GTP.headerSize + payload.count)
        XCTAssertEqual(Array(frame[V2GTP.headerSize...]), payload)
        XCTAssertEqual(V2GTP.readHeader(frame)?.payloadLength, UInt32(payload.count))
    }

    /// Payload types are distinct per set — with one deliberate exception, which is the whole
    /// reason SAP is never decoded through this dispatcher.
    func testPayloadTypesAreDistinctApartFromTheSapCollision() {
        let sets: [MessageSet] = [.appProtocol, .iso15118_2, .iso20CommonMessages,
                                  .iso20AC, .iso20DC, .iso20WPT, .iso20ACDP]
        let types = sets.map { V2GTPDispatcher.payloadType(of: $0) }

        XCTAssertEqual(Set(types).count, sets.count - 1, "exactly one pair may share an id")
        XCTAssertEqual(V2GTPDispatcher.payloadType(of: .appProtocol),
                       V2GTPDispatcher.payloadType(of: .iso15118_2),
                       "SAP and -2 share 0x8001 and are told apart by session phase")
    }

    // MARK: framing problems are values, not errors

    func testRejectsAFrameWithBadVersionBytes() throws {
        var frame = V2GTPDispatcher.encode(.iso20DC, try Self.firstVector("Iso15118_20.DC"))
        frame[0] = 0x02

        guard case let .failed(error) = try V2GTPDispatcher.decode(frame) else {
            return XCTFail("a frame with a bad version byte was accepted")
        }
        XCTAssertTrue(error.contains("not a valid V2GTP frame"), error)
    }

    func testRejectsALengthThatDisagreesWithTheFrame() throws {
        var frame = V2GTPDispatcher.encode(.iso20DC, try Self.firstVector("Iso15118_20.DC"))
        frame.removeLast()   // the header still declares the old length

        guard case let .failed(error) = try V2GTPDispatcher.decode(frame) else {
            return XCTFail("a length mismatch was accepted")
        }
        XCTAssertTrue(error.contains("payload length mismatch"), error)
    }

    func testRejectsAnUnknownPayloadType() throws {
        var frame = V2GTPDispatcher.encode(.iso20DC, try Self.firstVector("Iso15118_20.DC"))
        frame[2] = 0x9F; frame[3] = 0xFF

        guard case let .failed(error) = try V2GTPDispatcher.decode(frame) else {
            return XCTFail("an unknown payload type was accepted")
        }
        XCTAssertTrue(error.contains("unknown V2GTP payload type 0x9FFF"), error)
    }

    /// WPT is a *known* payload type with no Swift codec behind it, and the dispatcher says so
    /// rather than calling the frame unknown — which would suggest it was malformed.
    func testReportsWptAsRecognisedButUngenerated() throws {
        var frame = V2GTPDispatcher.encode(.iso20DC, try Self.firstVector("Iso15118_20.DC"))
        frame[2] = 0x80; frame[3] = 0x06

        guard case let .failed(error) = try V2GTPDispatcher.decode(frame) else {
            return XCTFail("a WPT frame was decoded, but no WPT codec is generated")
        }
        XCTAssertTrue(error.contains("WPT"), error)
        XCTAssertFalse(error.contains("unknown"), "WPT is recognised, not unknown: \(error)")
    }
}
