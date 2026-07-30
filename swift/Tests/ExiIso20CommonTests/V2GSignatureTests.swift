#if canImport(CryptoKit)
import CryptoKit
import XCTest
@testable import ExiIso20Common

/// ISO 15118-20 signing, on the CommonMessages set.
///
/// The -20 suite allows ECDSA-P521 or Ed448. Only the first is implemented; the tests below pin
/// both what works and what deliberately does not, so "Ed448 is missing" stays a stated condition
/// rather than something a caller discovers as a verification failure.
final class Iso20CommonV2GSignatureTests: XCTestCase {

    /// A minimal SignedInfo, built rather than read: the CommonMessages corpus has no fragment
    /// vectors, and what these tests need is a well-formed value, not reference bytes — those are
    /// the fragment codec's business, and -2's fragments already check it against libcbv2g.
    private func signedInfo() -> SignedInfoType {
        SignedInfoType(
            canonicalizationMethod: CanonicalizationMethodType(algorithm: "http://www.w3.org/TR/canonical-exi/"),
            signatureMethod: SignatureMethodType(algorithm: "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512"),
            reference: [
                ReferenceType(
                    uRI: "#id1",
                    transforms: TransformsType(transform: [
                        TransformType(algorithm: "http://www.w3.org/TR/canonical-exi/")
                    ]),
                    digestMethod: DigestMethodType(algorithm: "http://www.w3.org/2001/04/xmlenc#sha512"),
                    digestValue: [UInt8](repeating: 0xAB, count: 64))
            ])
    }

    /// The wire carries the raw r‖s pair — 66 + 66 for P-521 — not ASN.1/DER. Same trap as -2,
    /// one curve up.
    func testAnEcdsaSignatureIsOneHundredAndThirtyTwoRawBytes() throws {
        let key = P521.Signing.PrivateKey()
        let signature = try V2GSignature.sign(signedInfo(), with: key)

        XCTAssertEqual(signature.count, 132, "-20 puts the raw r‖s pair on the wire, not ASN.1/DER")
    }

    func testASignatureVerifies() throws {
        let key = P521.Signing.PrivateKey()
        let info = signedInfo()

        let signature = try V2GSignature.sign(info, with: key)
        XCTAssertTrue(try V2GSignature.verify(info, signature: signature, with: key.publicKey))
    }

    func testAnotherKeyDoesNotVerify() throws {
        let info = signedInfo()
        let signature = try V2GSignature.sign(info, with: P521.Signing.PrivateKey())

        XCTAssertFalse(try V2GSignature.verify(info, signature: signature,
                                               with: P521.Signing.PrivateKey().publicKey))
    }

    func testADerSignatureIsRejected() throws {
        let key = P521.Signing.PrivateKey()
        let info = signedInfo()
        let fragment = CommonMessagesCodec.encodeFragment_SignedInfo(info)
        let der = Array(try key.signature(for: Data(fragment)).derRepresentation)

        XCTAssertNotEqual(der.count, 132)
        XCTAssertFalse(try V2GSignature.verify(info, signature: der, with: key.publicKey),
                       "a DER signature was accepted — the wire format is r‖s")
    }

    /// Ed448 is part of the -20 suite and is not available here. An Ed448-shaped signature has to
    /// come back as *unsupported*, not as a failed verification: the two mean different things to a
    /// caller deciding whether to renegotiate or to reject the peer.
    func testAnEd448SignatureIsReportedAsUnsupportedRatherThanInvalid() throws {
        let key = P521.Signing.PrivateKey()
        let ed448Shaped = [UInt8](repeating: 0x00, count: 114)

        XCTAssertThrowsError(try V2GSignature.verify(signedInfo(), signature: ed448Shaped,
                                                     with: key.publicKey)) { error in
            XCTAssertEqual(error as? V2GSignatureError, .ed448NotAvailable)
        }
    }

    func testTheDigestIsSha512OverTheFragment() throws {
        let fragment = CommonMessagesCodec.encodeFragment_SignedInfo(signedInfo())

        XCTAssertEqual(V2GSignature.digest(ofFragment: fragment).count, 64)
        XCTAssertEqual(V2GSignature.digest(ofFragment: fragment),
                       Array(SHA512.hash(data: Data(fragment))))
    }

    /// The point of the per-set copies: this set's SignedInfo fragment must not coincide with
    /// another's, or the repetition would be pointless and a shared helper would be safe.
    func testThisSetsSignedInfoFragmentIsItsOwn() {
        let fragment = CommonMessagesCodec.encodeFragment_SignedInfo(signedInfo())

        // 230 over 9 bits, after the 0x80 EXI header: the first bits carry 0b011100110.
        XCTAssertEqual(fragment[0], 0x80)
        XCTAssertEqual(fragment[1], 0b01110011)
    }
}
#endif
