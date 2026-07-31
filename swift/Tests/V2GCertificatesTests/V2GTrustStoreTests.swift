import Crypto
import Foundation
import SwiftASN1
import X509
import XCTest
@testable import V2GCertificates

/// The MO root store, and the question a scanned root actually poses.
///
/// Unlike the codec and session tests, there is no C# counterpart to be held to here: this
/// classification is behaviour the app defines, not a port of something that already exists. So the
/// certificates are built in the test. What that costs is the cross-language check; what it buys is
/// that the awkward cases — same name with a different key, above all — can be constructed exactly.
final class V2GTrustStoreTests: XCTestCase {

    private let epoch = Date(timeIntervalSince1970: 1_767_225_600)

    /// Builds a self-signed CA. `key` is passed in so two certificates can deliberately share one —
    /// which is the whole distinction between a renewal and a replacement.
    private func root(commonName: String,
                      key: P256.Signing.PrivateKey = P256.Signing.PrivateKey(),
                      from: Date? = nil,
                      to: Date? = nil,
                      isCA: Bool = true) throws -> V2GCertificate {

        let privateKey = Certificate.PrivateKey(key)
        let name = try DistinguishedName { CommonName(commonName) }

        let certificate = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: privateKey.publicKey,
            notValidBefore: from ?? epoch.addingTimeInterval(-86_400),
            notValidAfter: to ?? epoch.addingTimeInterval(86_400 * 3650),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: try Certificate.Extensions {
                Critical(BasicConstraints.isCertificateAuthority(maxPathLength: isCA ? 1 : nil))
            },
            issuerPrivateKey: privateKey)

        // A non-CA is built by replacing the extension outright; `maxPathLength: nil` above still
        // says "is a CA".
        let final = isCA ? certificate : try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: privateKey.publicKey,
            notValidBefore: from ?? epoch.addingTimeInterval(-86_400),
            notValidAfter: to ?? epoch.addingTimeInterval(86_400 * 3650),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: try Certificate.Extensions {
                Critical(BasicConstraints.notCertificateAuthority)
            },
            issuerPrivateKey: privateKey)

        var serializer = DER.Serializer()
        try serializer.serialize(final)
        return try V2GCertificate(der: serializer.serializedBytes)
    }


    /// A CA certificate issued *by another key* — a link certificate, the shape a vouched root
    /// rotation actually takes. Not self-signed, on purpose.
    private func linkCertificate(commonName: String,
                                 signedBy issuerKey: P256.Signing.PrivateKey,
                                 issuerCommonName: String) throws -> V2GCertificate {

        let subjectKey = Certificate.PrivateKey(P256.Signing.PrivateKey())
        let issuerName = try DistinguishedName { CommonName(issuerCommonName) }
        let subject    = try DistinguishedName { CommonName(commonName) }

        let certificate = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: subjectKey.publicKey,
            notValidBefore: epoch.addingTimeInterval(-86_400),
            notValidAfter: epoch.addingTimeInterval(86_400 * 3650),
            issuer: issuerName,
            subject: subject,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: try Certificate.Extensions {
                Critical(BasicConstraints.isCertificateAuthority(maxPathLength: 1))
            },
            issuerPrivateKey: Certificate.PrivateKey(issuerKey))

        var serializer = DER.Serializer()
        try serializer.serialize(certificate)
        return try V2GCertificate(der: serializer.serializedBytes)
    }


    // ── the verdicts ──────────────────────────────────────────────────────

    func testAnUnknownRootIsNew() throws {
        let store = InMemoryTrustStore()
        XCTAssertEqual(store.verdict(for: try root(commonName: "MO Root A")), .new)
    }

    func testTheSameCertificateTwiceIsAlreadyTrusted() throws {
        let anchor = try root(commonName: "MO Root A")
        let store = InMemoryTrustStore(roots: [anchor])
        XCTAssertEqual(store.verdict(for: anchor), .alreadyTrusted)
    }

    /// Same name, same key, later dates: the CA re-issued itself. A user who trusted it still does.
    func testSameSubjectAndSameKeyIsARenewal() throws {

        let key = P256.Signing.PrivateKey()
        let original = try root(commonName: "MO Root A", key: key)
        let renewed  = try root(commonName: "MO Root A", key: key,
                                from: epoch, to: epoch.addingTimeInterval(86_400 * 7300))

        let store = InMemoryTrustStore(roots: [original])

        XCTAssertNotEqual(original.der, renewed.der, "the test needs two distinct certificates")
        XCTAssertEqual(store.verdict(for: renewed), .renewal)
    }

    /// **The case this whole distinction exists for.**
    ///
    /// Same name, different key. A user's mental model reads "update"; it is a different CA wearing a
    /// known name, and nothing about the earlier decision carries over. If this returned `.renewal`,
    /// an attacker would only need to name their root after one the user already trusts.
    func testSameSubjectWithADifferentKeyIsAReplacementNotARenewal() throws {

        let original    = try root(commonName: "MO Root A")
        let impersonator = try root(commonName: "MO Root A")   // same name, fresh key

        let store = InMemoryTrustStore(roots: [original])

        XCTAssertEqual(store.verdict(for: impersonator), .replacementUnderKnownName)
        XCTAssertNotEqual(store.verdict(for: impersonator), .renewal,
                          "a different key under a known name must never be presented as a refresh")
    }

    /// The friendly rotation: the CA introduces its successor by signing it with the key it still
    /// has. Cryptographic continuity, so the dialog can say something stronger than "this is new".
    func testASuccessorSignedByATrustedRootIsVouchedFor() throws {

        let oldKey   = P256.Signing.PrivateKey()
        let oldRoot  = try root(commonName: "MO Root A", key: oldKey)
        let successor = try linkCertificate(commonName: "MO Root A (2031)", signedBy: oldKey,
                                            issuerCommonName: "MO Root A")

        let store = InMemoryTrustStore(roots: [oldRoot])

        XCTAssertEqual(store.verdict(for: successor),
                       .vouchedByTrustedRoot(fingerprintOfVouchingRoot: V2GFingerprint(of: oldRoot.der)))
        XCTAssertTrue(store.defects(of: successor, now: epoch).isEmpty,
                      "a link certificate is not self-signed, and that is not a defect")
    }

    /// The same successor, offered to a store that does **not** hold the vouching root, is simply a
    /// stranger — and it is not even an anchor, since nothing self-signs it and nothing vouches.
    func testTheSameSuccessorWithoutTheVoucherIsNotAnAnchorAtAll() throws {

        let successor = try linkCertificate(commonName: "MO Root A (2031)",
                                            signedBy: P256.Signing.PrivateKey(),
                                            issuerCommonName: "MO Root A")

        let store = InMemoryTrustStore()

        XCTAssertEqual(store.verdict(for: successor), .new)
        XCTAssertTrue(store.defects(of: successor, now: epoch).contains(.neitherSelfSignedNorVouched))
    }

    /// **What vouching is worth when the old key is in the wrong hands.**
    ///
    /// Whoever holds the trusted root's private key can introduce any successor they like, and it
    /// lands on `.vouchedByTrustedRoot` — indistinguishable from a legitimate rotation, because
    /// cryptographically it *is* one. Asserted rather than merely noted, so nobody later mistakes
    /// this verdict for proof: it says "the key you trusted signed this", which is a fact about the
    /// key, not about who was holding it.
    func testAStolenRootKeyProducesAnEquallyValidVouching() throws {

        let compromisedKey = P256.Signing.PrivateKey()
        let realRoot = try root(commonName: "MO Root A", key: compromisedKey)

        // The attacker signs their own successor with the stolen key.
        let attackersSuccessor = try linkCertificate(commonName: "MO Root A (2031)",
                                                     signedBy: compromisedKey,
                                                     issuerCommonName: "MO Root A")

        let store = InMemoryTrustStore(roots: [realRoot])

        guard case .vouchedByTrustedRoot = store.verdict(for: attackersSuccessor) else {
            return XCTFail("a stolen key vouches exactly as well as an honest one — that is the point")
        }
    }

    func testADifferentNameIsSimplyNew() throws {
        let store = InMemoryTrustStore(roots: [try root(commonName: "MO Root A")])
        XCTAssertEqual(store.verdict(for: try root(commonName: "MO Root B")), .new)
    }


    // ── defects, which are not a trust question ───────────────────────────

    func testACertificateThatIsNotACaCannotBeARoot() throws {
        let store = InMemoryTrustStore()
        let leaf  = try root(commonName: "Not A CA", isCA: false)
        XCTAssertTrue(store.defects(of: leaf).contains(.notACertificateAuthority))
    }

    func testAnExpiredRootIsReportedAsSuch() throws {
        let store = InMemoryTrustStore()
        let stale = try root(commonName: "MO Root A",
                             from: epoch.addingTimeInterval(-86_400 * 20),
                             to: epoch.addingTimeInterval(-86_400 * 10))

        let defects = store.defects(of: stale, now: epoch)
        XCTAssertEqual(defects.count, 1)
        guard case .expired = defects[0] else { return XCTFail("expected .expired, got \(defects)") }
    }

    func testAHealthyRootHasNoDefects() throws {
        let store = InMemoryTrustStore()
        XCTAssertTrue(store.defects(of: try root(commonName: "MO Root A"), now: epoch).isEmpty)
    }


    // ── the store itself ──────────────────────────────────────────────────

    func testAddingIsIdempotentAndRemovalIsByFingerprint() throws {

        var store = InMemoryTrustStore()
        let anchor = try root(commonName: "MO Root A")

        store.add(anchor)
        store.add(anchor)
        XCTAssertEqual(store.roots.count, 1, "the same root twice is one root")

        let fingerprint = V2GFingerprint(of: anchor.der)
        XCTAssertTrue(store.trusts(fingerprint: fingerprint))

        store.remove(fingerprint: fingerprint)
        XCTAssertTrue(store.roots.isEmpty)
        XCTAssertFalse(store.trusts(fingerprint: fingerprint))
    }

    /// The fingerprint is what a user compares by eye, so its shape is part of the interface.
    func testTheFingerprintIsGroupedUppercaseHex() throws {
        let printed = V2GFingerprint(of: try root(commonName: "MO Root A").der).description
        XCTAssertEqual(printed.count, 32 * 2 + 15, "32 bytes as pairs, 15 separators")
        XCTAssertEqual(printed.filter { $0 == ":" }.count, 15)
        XCTAssertTrue(printed.allSatisfy { $0.isHexDigit || $0 == ":" })
        XCTAssertEqual(printed.uppercased(), printed)
    }

    /// Two different certificates must not share a fingerprint — trivially true of SHA-256, asserted
    /// because the fingerprint is the only field the confirmation dialog can rely on.
    func testDifferentCertificatesHaveDifferentFingerprints() throws {
        let a = try root(commonName: "MO Root A")
        let b = try root(commonName: "MO Root A")
        XCTAssertNotEqual(V2GFingerprint(of: a.der), V2GFingerprint(of: b.der))
    }
}
