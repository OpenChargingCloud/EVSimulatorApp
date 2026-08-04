import XCTest
@testable import ExiIso2

/// The EXI *fragment* encodings of ISO 15118-2's signable elements — the bytes XMLDSig digests.
///
/// A fragment is not a message: EXI header, the element's fragment-grammar event code, its content,
/// End Fragment, and no document or body wrapper. The corpus comes from libcbv2g's
/// `encode_iso2_exiFragment` at the pinned commit, so these are a reference encoder's bytes rather
/// than ours.
///
/// Getting a fragment wrong is unusually quiet: the message codecs stay green, every round trip
/// still closes, and the only symptom is a signature that verifies against itself and is rejected
/// by every conforming peer.
final class Iso2FragmentVectorTests: XCTestCase {

    private static func repositoryRoot() throws -> URL {
        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("libs/Vanaheimr.V2G.Exi").path) { return dir }
        }
        throw XCTSkip("repository root not found from \(#filePath)")
    }

    private static func loadVectors() throws -> [(element: String, bytes: [UInt8])] {
        let url = try repositoryRoot().appendingPathComponent(
            "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/WWCP_ISO15118_EXI_Tests/Vectors/Iso15118_2.fragments.vectors.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        guard let raw = json?["vectors"] as? [[String: Any]] else {
            throw XCTSkip("vector file has no 'vectors' array: \(url.path)")
        }
        return raw.compactMap { v in
            guard let e = v["element"] as? String, let h = v["expectedHex"] as? String else { return nil }
            return (e, h.split(whereSeparator: { $0 == " " || $0 == "\n" }).compactMap { UInt8($0, radix: 16) })
        }
    }

    private static func hex(_ b: [UInt8]) -> String {
        b.map { String(format: "%02x", $0) }.joined(separator: " ")
    }

    /// Decode each fragment and re-encode it through the codec whose name the vector gives, so a
    /// fragment encoder wired to the wrong element cannot pass.
    private func roundTrip(_ element: String, _ bytes: [UInt8]) throws -> [UInt8]? {
        switch element {
        case "AuthorizationReq":
            return Iso15118_2Codec.encodeFragment_AuthorizationReq(
                try Iso15118_2Codec.decodeFragment_AuthorizationReq(bytes))
        case "MeteringReceiptReq":
            return Iso15118_2Codec.encodeFragment_MeteringReceiptReq(
                try Iso15118_2Codec.decodeFragment_MeteringReceiptReq(bytes))
        case "SalesTariff":
            return Iso15118_2Codec.encodeFragment_SalesTariff(
                try Iso15118_2Codec.decodeFragment_SalesTariff(bytes))
        case "SignedInfo":
            return Iso15118_2Codec.encodeFragment_SignedInfo(
                try Iso15118_2Codec.decodeFragment_SignedInfo(bytes))
        default:
            return nil
        }
    }

    func testTheCorpusCoversEverySignableElement() throws {
        let elements = Set(try Self.loadVectors().map(\.element))
        XCTAssertEqual(elements, ["AuthorizationReq", "MeteringReceiptReq", "SalesTariff", "SignedInfo"])
    }

    func testEveryFragmentReEncodesToTheReferenceBytes() throws {
        var failures: [String] = []

        for v in try Self.loadVectors() {
            do {
                guard let actual = try roundTrip(v.element, v.bytes) else {
                    failures.append("\(v.element): no fragment codec")
                    continue
                }
                if actual != v.bytes {
                    failures.append("\(v.element):\n  expected \(Self.hex(v.bytes))\n  actual   \(Self.hex(actual))")
                }
            } catch {
                failures.append("\(v.element): \(error)")
            }
        }

        XCTAssertTrue(failures.isEmpty, "fragments differ:\n" + failures.joined(separator: "\n"))
    }

    /// A fragment carries no document wrapper, so its bytes must differ from the same content
    /// encoded as a message — the distinction the whole signing layer rests on.
    func testAFragmentIsNotAMessage() throws {
        let signedInfo = try Self.loadVectors().first { $0.element == "SignedInfo" }
        let bytes = try XCTUnwrap(signedInfo?.bytes)

        // The fragment decoder accepts it; the document decoder must not, because the bits after
        // the EXI header are a fragment selector rather than a document one.
        XCTAssertNoThrow(try Iso15118_2Codec.decodeFragment_SignedInfo(bytes))
        XCTAssertThrowsError(try Iso15118_2Codec.decodeAny(bytes),
                             "a fragment decoded as a document — the two selectors are being confused")
    }
}
