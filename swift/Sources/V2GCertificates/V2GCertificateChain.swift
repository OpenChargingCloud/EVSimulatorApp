import Foundation

/// A contract certificate and the sub-CA certificates that lead towards a root — what an app
/// installs when it scans a provisioning QR code, and what `PaymentDetailsReq` /
/// `PnC_AReqAuthorizationMode` carry on the wire.
///
/// ## What `linksUp` does and does not mean
///
/// It checks that each certificate's issuer name equals the next one's subject name. That is a
/// **name** check: it says the chain is ordered and plausibly connected, which is what the wire
/// format requires (leaf first, then its issuer, and so on). It is emphatically **not** a claim that
/// any signature verifies or that the chain reaches a trusted root.
///
/// Keeping those apart is deliberate. A chain that arrives by QR code is untrusted; "it parsed and
/// the names line up" is a *shape* check that belongs at install time, and trust is a separate
/// decision made against a pinned root — §4.5's `root` fingerprint, or the platform trust store.
/// Conflating the two is how "the certificate loaded" quietly becomes "the certificate is good".
public struct V2GCertificateChain: Sendable {

    /// The contract certificate itself.
    public let leaf: V2GCertificate

    /// The sub-CA certificates, leaf's issuer first. May be empty.
    public let subCertificates: [V2GCertificate]

    public init(leaf: V2GCertificate, subCertificates: [V2GCertificate] = []) {
        self.leaf = leaf
        self.subCertificates = subCertificates
    }

    /// Parses a chain given leaf-first DER, as both the QR payload and the wire order it.
    public init(der: [[UInt8]]) throws {
        guard let first = der.first else { throw V2GCertificateError.emptyChain }
        self.leaf = try V2GCertificate(der: first)
        self.subCertificates = try der.dropFirst().map { try V2GCertificate(der: $0) }
    }

    /// Every certificate, leaf first — the order the wire wants.
    public var all: [V2GCertificate] { [leaf] + subCertificates }

    /// True when each certificate's issuer name matches the next one's subject name. See the type
    /// comment: a name check, not a signature check.
    public var linksUp: Bool {
        zip(all, all.dropFirst()).allSatisfy { $0.issuer == $1.subject }
    }

    /// The eMAID this chain would authorize as, if the leaf carries a usable one.
    public var emaid: String? { leaf.emaid }
}

public enum V2GCertificateError: Error, CustomStringConvertible {
    case emptyChain

    public var description: String {
        switch self {
        case .emptyChain: return "a certificate chain needs at least the leaf certificate."
        }
    }
}
