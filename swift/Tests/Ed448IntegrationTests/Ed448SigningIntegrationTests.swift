import XCTest

import V2GEd448
@testable import ExiIso20Common

/// The join between the two halves, and the only place that can make it.
///
/// `V2GEd448Tests` proves the primitive reproduces RFC 8032 §7.4; `Iso20CommonV2GSignatureTests`
/// proves our dispatch and wire formats. Neither can see whether the octets actually handed to the
/// primitive are the `SignedInfo` **fragment** — this target imports both, so it can.
final class Ed448SigningIntegrationTests: XCTestCase {

    struct Vector {
        let label: String
        let secretKey: [UInt8]
        let publicKey: [UInt8]
        let message: [UInt8]
        let context: [UInt8]
        let signature: [UInt8]
    }

    private static func hex(_ s: String) -> [UInt8] {
        stride(from: 0, to: s.count, by: 2).map {
            let i = s.index(s.startIndex, offsetBy: $0)
            return UInt8(s[i...s.index(i, offsetBy: 1)], radix: 16)!
        }
    }

    private static func repositoryRoot() throws -> URL {
        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("libs/Vanaheimr.V2G.Exi").path) { return dir }
        }
        throw XCTSkip("repository root not found from \(#filePath)")
    }

    static func vectors() throws -> [Vector] {
        let url = try repositoryRoot().appendingPathComponent(
            "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Tests/Vectors/Ed448.rfc8032.vectors.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        guard let raw = json?["vectors"] as? [[String: Any]] else {
            throw XCTSkip("vector file has no 'vectors' array: \(url.path)")
        }
        return raw.map {
            Vector(label:     $0["label"] as! String,
                   secretKey: hex($0["secretKey"] as! String),
                   publicKey: hex($0["publicKey"] as! String),
                   message:   hex($0["message"] as! String),
                   context:   hex($0["context"] as! String),
                   signature: hex($0["signature"] as! String))
        }
    }



    /// `V2GSignature.sign` is the vector-checked primitive over the fragment, and nothing else.
    ///
    /// This is the join between the two halves, and the only place that can make it: the vectors
    /// above validate Goldilocks, `Iso20CommonV2GSignatureTests` validates our dispatch and wire
    /// formats, and neither can see whether the bytes actually handed to the primitive are the
    /// `SignedInfo` fragment. Here both dependencies are in scope, so the check is an equality
    /// against a hand-rolled call rather than another round trip.
    ///
    /// Pure EdDSA signs the fragment octets **directly** — SHAKE256 inside Ed448 replaces the
    /// external pre-hash, unlike the P-521 path where SHA-512 is a separate step. If
    /// `V2GSignature` ever grows one, or a context, or a different notion of which octets are
    /// signed, this fails while every test either side of it keeps passing.
    func testOurSigningPathIsTheVectorCheckedPrimitiveOverTheFragment() throws {
        let v   = try XCTUnwrap(try Self.vectors().first)
        let key = try Ed448PrivateKey(rawRepresentation: v.secretKey)

        let signedInfo = SignedInfoType(
            canonicalizationMethod: .init(algorithm: "http://www.w3.org/TR/canonical-exi/"),
            signatureMethod: .init(algorithm: V2GSignature.Algorithm.eddsaEd448.rawValue),
            reference: [.init(uRI: "#id1",
                              transforms: .init(transform: [.init(algorithm: "http://www.w3.org/TR/canonical-exi/")]),
                              digestMethod: .init(algorithm: "http://www.w3.org/2001/04/xmlenc#sha512"),
                              digestValue: [UInt8](repeating: 0xAB, count: 64))])

        let ours     = try V2GSignature.sign(signedInfo, with: key)
        let fragment = CommonMessagesCodec.encodeFragment_SignedInfo(signedInfo)
        let expected = try Ed448.sign(fragment, seed: v.secretKey, publicKey: v.publicKey)

        XCTAssertEqual(ours, expected)
        XCTAssertEqual(ours.count, 114)

        // The key wrapper derives the same public key the RFC pairs with this seed, so signing
        // through it cannot silently use a different one.
        XCTAssertEqual(key.publicKey.rawRepresentation, v.publicKey)

        // And the fragment really is this set's — 230 over 9 bits after the header, as
        // ExiIso20CommonTests pins independently.
        XCTAssertEqual(fragment[0], 0x80)
        XCTAssertEqual(fragment[1], 0b01110011)
    }
}
