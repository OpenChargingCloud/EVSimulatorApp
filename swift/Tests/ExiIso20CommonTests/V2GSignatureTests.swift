#if canImport(CryptoKit)
import CryptoKit
import XCTest
@testable import ExiIso20Common

/// ISO 15118-20 signing, on the CommonMessages set.
///
/// The -20 suite allows ECDSA-P521 or Ed448, and **both are now implemented** — Ed448 through
/// `swift-goldilocks`, whose primitive is held to RFC 8032 §7.4 by `Ed448GoldilocksSpikeTests`.
/// What the tests below pin is the layer above that: which algorithm is used, decided by the
/// message rather than guessed, and the wire format of each.
final class Iso20CommonV2GSignatureTests: XCTestCase {

    /// A minimal SignedInfo, built rather than read: the CommonMessages corpus has no fragment
    /// vectors, and what these tests need is a well-formed value, not reference bytes — those are
    /// the fragment codec's business, and -2's fragments already check it against libcbv2g.
    private func signedInfo(
        algorithm: String = V2GSignature.Algorithm.ecdsaSha512.rawValue
    ) -> SignedInfoType {
        SignedInfoType(
            canonicalizationMethod: CanonicalizationMethodType(algorithm: "http://www.w3.org/TR/canonical-exi/"),
            signatureMethod: SignatureMethodType(algorithm: algorithm),
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

    private func ed448SignedInfo() -> SignedInfoType {
        signedInfo(algorithm: V2GSignature.Algorithm.eddsaEd448.rawValue)
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

    // ── Ed448 ───────────────────────────────────────────────────────────────────────────────────

    /// Ed448 signatures are always 114 bytes, and there is no DER form to confuse them with.
    func testAnEd448SignatureIsOneHundredAndFourteenBytes() throws {
        let key = try Ed448PrivateKey()
        let signature = try V2GSignature.sign(ed448SignedInfo(), with: key)

        XCTAssertEqual(signature.count, 114)
    }

    func testAnEd448SignatureVerifies() throws {
        let key  = try Ed448PrivateKey()
        let info = ed448SignedInfo()

        let signature = try V2GSignature.sign(info, with: key)
        XCTAssertTrue(try V2GSignature.verify(info, signature: signature, with: key.publicKey))
    }

    func testAnotherEd448KeyDoesNotVerify() throws {
        let info = ed448SignedInfo()
        let signature = try V2GSignature.sign(info, with: try Ed448PrivateKey())

        XCTAssertFalse(try V2GSignature.verify(info, signature: signature,
                                               with: try Ed448PrivateKey().publicKey))
    }

    /// A private key determines its public key, so the pair cannot be mismatched by construction.
    func testAPrivateKeyCarriesItsOwnPublicKey() throws {
        let key = try Ed448PrivateKey()

        XCTAssertEqual(key.rawRepresentation.count, 57)
        XCTAssertEqual(key.publicKey.rawRepresentation.count, 57)
        XCTAssertEqual(try Ed448PrivateKey(rawRepresentation: key.rawRepresentation).publicKey,
                       key.publicKey)
    }

    func testAKeyOfTheWrongLengthIsRefused() {
        XCTAssertThrowsError(try Ed448PrivateKey(rawRepresentation: [UInt8](repeating: 0, count: 56))) {
            XCTAssertEqual($0 as? V2GSignatureError, .invalidKeyLength(expected: 57, actual: 56))
        }
    }

    // ── Which algorithm, decided by the message ─────────────────────────────────────────────────

    /// The heart of the change: the SignedInfo *declares* the algorithm, so nothing is inferred
    /// from a signature length.
    func testTheAlgorithmComesFromTheSignedInfo() throws {
        XCTAssertEqual(try V2GSignature.algorithm(of: signedInfo()), .ecdsaSha512)
        XCTAssertEqual(try V2GSignature.algorithm(of: ed448SignedInfo()), .eddsaEd448)
    }

    /// Signing under an algorithm the message does not name is refused rather than honoured. It
    /// would produce a signature that verifies locally and is rejected by every conforming peer —
    /// the same class of quiet failure as the DER/`r‖s` mix-up, one level up.
    func testSigningAgainstTheDeclaredAlgorithmIsRefused() async throws {
        XCTAssertThrowsError(try V2GSignature.sign(signedInfo(), with: try Ed448PrivateKey())) {
            XCTAssertEqual($0 as? V2GSignatureError,
                           .algorithmMismatch(declared: .ecdsaSha512, attempted: .eddsaEd448))
        }
        XCTAssertThrowsError(try V2GSignature.sign(ed448SignedInfo(), with: P521.Signing.PrivateKey())) {
            XCTAssertEqual($0 as? V2GSignatureError,
                           .algorithmMismatch(declared: .eddsaEd448, attempted: .ecdsaSha512))
        }
    }

    /// And verification the same way: an Ed448-declared SignedInfo handed a P-521 key is a caller
    /// mistake, not a bad signature, and must not come back as `false`.
    func testVerifyingAgainstTheDeclaredAlgorithmIsRefused() throws {
        let ed448 = try Ed448PrivateKey()
        let info  = ed448SignedInfo()
        let signature = try V2GSignature.sign(info, with: ed448)

        XCTAssertThrowsError(try V2GSignature.verify(info, signature: signature,
                                                     with: P521.Signing.PrivateKey().publicKey)) {
            XCTAssertEqual($0 as? V2GSignatureError,
                           .algorithmMismatch(declared: .eddsaEd448, attempted: .ecdsaSha512))
        }
    }

    /// `#eddsa-ed448ph` is the trap this dispatch exists for. RFC 9231 §2.3.12 gives the prehashed
    /// variant its own identifier; it is a different algorithm and is not implemented. A peer
    /// asking for it must be told that by name, not have its message signed as if it were pure
    /// Ed448.
    func testThePrehashedVariantIsRefusedByName() {
        let ph = "http://www.w3.org/2021/04/xmldsig-more#eddsa-ed448ph"

        XCTAssertThrowsError(try V2GSignature.algorithm(of: signedInfo(algorithm: ph))) {
            XCTAssertEqual($0 as? V2GSignatureError, .unsupportedAlgorithm(ph))
        }
    }

    func testAnUnknownAlgorithmIsReportedVerbatim() {
        XCTAssertThrowsError(try V2GSignature.algorithm(of: signedInfo(algorithm: "urn:nonsense"))) {
            XCTAssertEqual($0 as? V2GSignatureError, .unsupportedAlgorithm("urn:nonsense"))
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
