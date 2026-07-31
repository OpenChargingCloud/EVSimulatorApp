import CryptoKit
import Foundation
import X509

/// A SHA-256 fingerprint over a certificate's DER — the only thing about a scanned root that
/// actually binds.
///
/// A root's subject is whatever its issuer chose to write, and its issuer is itself. Anyone can mint
/// one calling itself "Hubject MO Root CA". So a confirmation dialog may *show* the name, and must
/// not let the name do the convincing: the fingerprint is what a user can compare against something
/// they got another way, and it is the only field with that property.
public struct V2GFingerprint: Hashable, Sendable, CustomStringConvertible {

    public let bytes: [UInt8]

    public init(of der: [UInt8]) {
        self.bytes = Array(SHA256.hash(data: Data(der)))
    }

    /// Uppercase hex, one byte per colon-separated group — the conventional form, and the one a
    /// user will find wherever they got the fingerprint to compare against.
    ///
    /// An earlier draft grouped two bytes per separator. Nothing broke, because nothing else read
    /// it — which is exactly the problem: the whole purpose of this string is to be compared by eye
    /// with one printed somewhere else, so an unconventional grouping defeats it silently. Caught by
    /// writing the Kotlin half, which had grouped it the usual way.
    public var description: String {
        bytes.map { String(format: "%02X", $0) }.joined(separator: ":")
    }
}


/// What installing a scanned root would mean, given what is already trusted.
///
/// The distinction that matters is the last two. Both are "same name, different certificate", and a
/// user's mental model collapses them into "an update" — which is exactly why an attacker would
/// present one. A renewal keeps the key and only extends the dates: it is the same CA, and a user
/// who trusted it still trusts it. A replacement carries a **different public key**, which makes it a
/// different CA wearing a known name, and nothing about the earlier trust decision carries over.
public enum V2GRootInstallVerdict: Equatable, Sendable {

    /// Byte-for-byte already in the store. Installing is a no-op; the dialog should say so rather
    /// than asking again.
    case alreadyTrusted

    /// No stored root shares this subject. The plain first-contact case.
    case new

    /// A stored root has the same subject **and the same public key**. The same CA, re-issued.
    case renewal

    /// A stored root has the same subject and a **different public key**. Not an update — a
    /// different CA under a known name. Must be presented at least as loudly as `new`, never as a
    /// routine refresh.
    case replacementUnderKnownName

    /// The candidate carries a **different key** but is signed by a root already in the store, and
    /// that signature verifies. A vouched rotation: the CA used the key it still had to introduce
    /// its successor, which is how root rollover is meant to work.
    ///
    /// **This is softer than a replacement, and it is not proof.** The vouching is worth exactly as
    /// much as the vouching key was sound. If the old private key was compromised, whoever holds it
    /// can introduce any successor they like and it will land here; if it was lost, the legitimate
    /// CA cannot use this path at all and its real rotation shows up as
    /// ``replacementUnderKnownName``. So a confirmation is still a confirmation — what changes is
    /// what the dialog can honestly say: "the root you already trust vouches for this one", rather
    /// than "this is new, decide".
    case vouchedByTrustedRoot(fingerprintOfVouchingRoot: V2GFingerprint)
}


/// Why a scanned certificate cannot be a trust anchor at all — a separate question from whether the
/// user wants to trust it, and one no dialog should be needed for.
public enum V2GRootDefect: Equatable, Sendable, CustomStringConvertible {

    case notACertificateAuthority
    case neitherSelfSignedNorVouched
    case expired(on: Date)
    case notYetValid(until: Date)

    public var description: String {
        switch self {
        case .notACertificateAuthority:
            return "the certificate is not marked as a certificate authority, so it cannot sign anything."
        case .neitherSelfSignedNorVouched:
            return "the certificate is neither self-signed nor signed by a root already trusted, "
                 + "so there is nothing that makes it an anchor."
        case .expired(let on):
            return "the certificate expired on \(on)."
        case .notYetValid(let until):
            return "the certificate is not valid until \(until)."
        }
    }
}


/// The set of MO root certificates this app trusts.
///
/// Deliberately a protocol with the persistence left out: where the store lives — Keychain, a file,
/// a database — is an app decision, and the rules below are the same either way. `InMemoryTrustStore`
/// is what the tests use and what an app can start from.
public protocol V2GTrustStore {
    var roots: [V2GCertificate] { get }
    mutating func add(_ root: V2GCertificate)
    mutating func remove(fingerprint: V2GFingerprint)
}

public extension V2GTrustStore {

    /// Structural reasons this certificate cannot serve as a root, independent of trust. Empty does
    /// not mean "trustworthy" — it means "asking the user is a meaningful question".
    ///
    /// Note what is *not* required: being self-signed. A root introduced by its predecessor — a link
    /// certificate — is issued by the old root, not by itself, and that is the well-behaved rotation
    /// path rather than a defect. It counts as an anchor when a stored root vouches for it; only a
    /// certificate that is neither self-signed nor vouched has no business being one.
    func defects(of candidate: V2GCertificate, now: Date = Date()) -> [V2GRootDefect] {
        var found: [V2GRootDefect] = []
        if !candidate.isCertificateAuthority { found.append(.notACertificateAuthority) }
        if !candidate.isSelfIssued && vouchingRoot(for: candidate) == nil {
            found.append(.neitherSelfSignedNorVouched)
        }
        if now > candidate.notValidAfter     { found.append(.expired(on: candidate.notValidAfter)) }
        if now < candidate.notValidBefore    { found.append(.notYetValid(until: candidate.notValidBefore)) }
        return found
    }

    /// A stored root whose key actually signed `candidate`, if there is one. The signature is
    /// checked, not merely the issuer name — a name match alone would let anyone claim a voucher.
    func vouchingRoot(for candidate: V2GCertificate) -> V2GCertificate? {
        roots.first { $0.hasSigned(candidate) }
    }

    /// What installing `candidate` would mean. See ``V2GRootInstallVerdict`` on why renewal and
    /// replacement are not the same answer.
    func verdict(for candidate: V2GCertificate) -> V2GRootInstallVerdict {

        let fingerprint = V2GFingerprint(of: candidate.der)
        if roots.contains(where: { V2GFingerprint(of: $0.der) == fingerprint }) {
            return .alreadyTrusted
        }

        let sameName = roots.filter { $0.subject == candidate.subject }

        if sameName.contains(where: { $0.publicKeyDer == candidate.publicKeyDer }) {
            return .renewal
        }

        // Checked before the name comparison decides anything: a vouched successor may legitimately
        // carry a different name, and a same-named stranger is not made friendlier by the name.
        if let voucher = vouchingRoot(for: candidate) {
            return .vouchedByTrustedRoot(fingerprintOfVouchingRoot: V2GFingerprint(of: voucher.der))
        }

        return sameName.isEmpty ? .new : .replacementUnderKnownName
    }

    func trusts(fingerprint: V2GFingerprint) -> Bool {
        roots.contains { V2GFingerprint(of: $0.der) == fingerprint }
    }
}


public struct InMemoryTrustStore: V2GTrustStore, Sendable {

    public private(set) var roots: [V2GCertificate]

    public init(roots: [V2GCertificate] = []) {
        self.roots = roots
    }

    /// Adding is unconditional on purpose: the decision — defects, verdict, the user's answer —
    /// belongs above this, and a store that silently refused would hide it.
    public mutating func add(_ root: V2GCertificate) {
        let fingerprint = V2GFingerprint(of: root.der)
        guard !roots.contains(where: { V2GFingerprint(of: $0.der) == fingerprint }) else { return }
        roots.append(root)
    }

    public mutating func remove(fingerprint: V2GFingerprint) {
        roots.removeAll { V2GFingerprint(of: $0.der) == fingerprint }
    }
}
