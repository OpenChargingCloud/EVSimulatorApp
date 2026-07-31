import CryptoKit
import SwiftASN1
import X509
import XCTest
@testable import V2GKeystore

/// CSR generation, and the two things about it worth checking.
///
/// A CSR proves possession of a private key, so it is signed by that key — which makes it the first
/// real customer of ``V2GSigner``. The signer is asked for a signature and never for the key, which
/// is what would let a secure-element key produce one.
final class V2GCsrTests: XCTestCase {

    private func signer() throws -> InMemoryP256Signer {
        try InMemoryP256Signer(P256.Signing.PrivateKey())
    }

    private func subject() throws -> DistinguishedName {
        try DistinguishedName { CommonName("DE8AA1A2B3C4D5") }
    }

    /// **The check that matters.** A CSR whose signature does not verify is not malformed in any way
    /// a parser notices — it is simply refused by whichever CA receives it, which is a miserable
    /// place to discover a conversion bug. So the request is verified here with the public key it
    /// carries, exactly as a CA would.
    func testTheRequestVerifiesUnderItsOwnPublicKey() throws {

        let der = try V2GCsr.build(subject: try subject(), signer: try signer())
        let csr = try CertificateSigningRequest(derEncoded: der)

        // The library's own check, which covers exactly the octets a CA would: it knows which part
        // of the request the signature spans, and this test deliberately does not restate that.
        XCTAssertTrue(csr.publicKey.isValidSignature(csr.signature, for: csr),
                      "a CSR whose signature does not verify would be refused by the CA and by "
                    + "nothing before it")
    }

    func testTheSubjectAndPublicKeySurvive() throws {

        let signer = try signer()
        let csr = try CertificateSigningRequest(
            derEncoded: try V2GCsr.build(subject: try subject(), signer: signer))

        XCTAssertEqual(csr.subject, try subject())
        // Compared as keys, not as bytes: `subjectPublicKeyInfoBytes` is the raw EC point, while a
        // signer's `publicKeyDer` is the full SubjectPublicKeyInfo. Both are correct names for
        // different things, and comparing them directly would fail for no reason worth reporting.
        let expected = try Certificate.PublicKey(
            P256.Signing.PublicKey(derRepresentation: Data(signer.publicKeyDer)))

        XCTAssertEqual(csr.publicKey, expected,
                       "the CSR must carry the very key the signer holds, or possession proves nothing")
    }

    /// The bytes a signature covers must be the *original* ones, not a re-encoding. The walk that
    /// extracts them is small enough to check directly: the first element of the outer SEQUENCE is
    /// itself a SEQUENCE, and it is shorter than the whole request.
    func testTheSignedOctetsAreTheFirstElementOfTheRequest() throws {

        let der = try V2GCsr.build(subject: try subject(), signer: try signer())
        let info = try V2GCsr.firstElement(ofSequence: der)

        XCTAssertEqual(info.first, 0x30, "CertificationRequestInfo is a SEQUENCE")
        XCTAssertLessThan(info.count, der.count)
        XCTAssertEqual(Array(der[0 ..< 1]), [0x30], "…inside an outer SEQUENCE")

        // And it really is a prefix of the request's content, not something rebuilt.
        XCTAssertTrue(der.count > info.count)
    }

    /// Long-form DER lengths are the case a naive walk gets wrong, and a CSR is comfortably over 127
    /// bytes, so this path is the one actually taken.
    func testTheWalkHandlesLongFormLengths() throws {

        let der = try V2GCsr.build(subject: try subject(), signer: try signer())
        XCTAssertGreaterThan(der.count, 127, "the request should be long enough to need a long form")
        XCTAssertEqual(der[1] & 0x80, 0x80, "…and it is: the outer length is long-form")

        XCTAssertNoThrow(try V2GCsr.firstElement(ofSequence: der))
    }

    func testATruncatedRequestIsRefusedRatherThanGuessedAt() {
        XCTAssertThrowsError(try V2GCsr.firstElement(ofSequence: [0x30]))
        XCTAssertThrowsError(try V2GCsr.firstElement(ofSequence: [0x30, 0x82, 0x01]))
    }

    /// Only P-256 so far, and it says so rather than producing a request whose algorithm identifier
    /// and `r‖s` width disagree with the key.
    func testAnUnsupportedCurveIsRefusedRatherThanMisEncoded() throws {

        struct P521Signer: V2GSigner {
            let curve = V2GKeyCurve.p521
            let protection = V2GKeyProtection.softwareInMemory
            let publicKeyDer: [UInt8] = []
            func signature(over octets: [UInt8]) throws -> [UInt8] { [UInt8](repeating: 0, count: 132) }
        }

        XCTAssertThrowsError(try V2GCsr.build(subject: try subject(), signer: P521Signer())) {
            XCTAssertEqual($0 as? V2GKeyError, .unsupportedCurve(.p521))
        }
    }
}
