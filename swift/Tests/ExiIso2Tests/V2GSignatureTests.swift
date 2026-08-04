#if canImport(CryptoKit)
import CryptoKit
import XCTest
@testable import ExiIso2

/// ISO 15118-2 signing. Two of these tests exist because of mistakes that are invisible to a round
/// trip and fatal on a charger.
final class V2GSignatureTests: XCTestCase {

    private func sampleSignedInfo() throws -> SignedInfoType {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent(
                "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Tests/Vectors/Iso15118_2.fragments.vectors.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        guard let raw = json?["vectors"] as? [[String: Any]],
              let hex = raw.first(where: { $0["element"] as? String == "SignedInfo" })?["expectedHex"] as? String
        else { throw XCTSkip("no SignedInfo fragment vector") }

        let bytes = hex.split(whereSeparator: { $0 == " " || $0 == "\n" }).compactMap { UInt8($0, radix: 16) }
        return try Iso15118_2Codec.decodeFragment_SignedInfo(bytes)
    }

    /// The one that matters most. ISO 15118-2 carries r‖s, not DER — and a DER signature verifies
    /// against itself perfectly, so nothing but a length check catches the mistake before a peer
    /// does.
    func testASignatureIsSixtyFourRawBytes() throws {
        let key = P256.Signing.PrivateKey()
        let signature = try V2GSignature.sign(try sampleSignedInfo(), with: key)

        XCTAssertEqual(signature.count, 64, "ISO 15118-2 puts the raw r‖s pair on the wire, not ASN.1/DER")
    }

    func testASignatureVerifies() throws {
        let key = P256.Signing.PrivateKey()
        let signedInfo = try sampleSignedInfo()

        let signature = try V2GSignature.sign(signedInfo, with: key)
        XCTAssertTrue(V2GSignature.verify(signedInfo, signature: signature, with: key.publicKey))
    }

    func testAnotherKeyDoesNotVerify() throws {
        let signedInfo = try sampleSignedInfo()
        let signature = try V2GSignature.sign(signedInfo, with: P256.Signing.PrivateKey())

        XCTAssertFalse(V2GSignature.verify(signedInfo, signature: signature,
                                           with: P256.Signing.PrivateKey().publicKey))
    }

    /// A DER-encoded signature must be refused rather than quietly failing deep inside CryptoKit,
    /// because this is the shape a JCA- or OpenSSL-minded implementation produces by default.
    func testADerSignatureIsRejected() throws {
        let key = P256.Signing.PrivateKey()
        let signedInfo = try sampleSignedInfo()
        let fragment = Iso15118_2Codec.encodeFragment_SignedInfo(signedInfo)
        let der = Array(try key.signature(for: Data(fragment)).derRepresentation)

        XCTAssertNotEqual(der.count, 64, "the DER form should not coincidentally be 64 bytes")
        XCTAssertFalse(V2GSignature.verify(signedInfo, signature: der, with: key.publicKey),
                       "a DER signature was accepted — the wire format is r‖s")
    }

    /// The digest is taken over the **fragment**. Digesting a document-wrapped encoding of the same
    /// content is the other silent way to produce a locally-consistent, universally-rejected
    /// signature.
    func testTheDigestIsOverTheFragmentNotTheMessage() throws {
        let signedInfo = try sampleSignedInfo()
        let fragment = Iso15118_2Codec.encodeFragment_SignedInfo(signedInfo)

        XCTAssertEqual(V2GSignature.digest(ofFragment: fragment).count, 32)
        XCTAssertEqual(V2GSignature.digest(ofFragment: fragment),
                       Array(SHA256.hash(data: Data(fragment))))
    }
}
#endif
