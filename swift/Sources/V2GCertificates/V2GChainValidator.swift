import Foundation
import X509

/// An ISO 15118 profile deviation: the chain is sound and the certificate is not what it claims.
///
/// Separate from trust on purpose. These do not make a chain invalid — they make it *wrong for this
/// purpose*, which is something to show a user rather than to decide for them. The same split the
/// pairing payload already draws between "this cannot be parsed" and "this declares something you
/// should know about".
public enum V2GProfileFinding: String, Equatable, Sendable, CustomStringConvertible {

    /// The contract leaf also carries `serverAuth`, so the same credential could be presented as a
    /// charging station. PKIX accepts it — nothing about path building objects to an extra purpose —
    /// and the ISO 15118 profile does not.
    case serverAuthOnContractCertificate

    /// The leaf is marked as a certificate authority. A contract certificate signs sessions, not
    /// certificates, and a leaf that can issue is a privilege it should not have.
    case contractCertificateIsMarkedAsCa

    /// The leaf carries no Common Name, so it carries no eMAID and cannot authorize a -2 session.
    case noCommonName

    public var description: String {
        switch self {
        case .serverAuthOnContractCertificate:
            return "the contract certificate also permits serverAuth, so it could be presented as a station."
        case .contractCertificateIsMarkedAsCa:
            return "the contract certificate is marked as a certificate authority."
        case .noCommonName:
            return "the contract certificate has no Common Name, so it carries no eMAID."
        }
    }
}


/// Why a chain could not be trusted. One right answer, no user opinion involved.
public enum V2GChainRejection: Equatable, Sendable, CustomStringConvertible {

    /// No path could be built from the leaf to any trusted root — a missing intermediate, a broken
    /// signature, an issuer that is not a CA, a certificate out of date, or simply a chain to a root
    /// this app does not trust. `detail` is the verifier's own words.
    case noPathToATrustedRoot(detail: String)

    /// The store holds no roots at all, so "trusted" is not yet a question that can be asked.
    case noTrustAnchors

    /// The bundle's certificates do not form an ordered chain, so **which one is the contract
    /// certificate is not stated**.
    ///
    /// ISO 15118 puts the leaf first, and that order is not decoration: it is the statement of which
    /// credential is being presented. A validator that reorders the bundle and picks a plausible leaf
    /// is guessing at an identity, and would happily validate a sub-CA as though it were the contract.
    /// Refusing is the only answer that does not invent one.
    case bundleDoesNotLinkUp

    public var description: String {
        switch self {
        case .noPathToATrustedRoot(let detail):
            return "no path from this certificate to a trusted root: \(detail)"
        case .noTrustAnchors:
            return "no root certificates are installed, so nothing can be trusted yet."
        case .bundleDoesNotLinkUp:
            return "the certificates do not form an ordered chain, so which one is the contract "
                 + "certificate is not stated."
        }
    }
}


/// The outcome of validating a chain: whether it is trusted, and what is worth saying about it.
public struct V2GChainVerdict: Sendable {

    /// `nil` when the chain is trusted.
    public let rejection: V2GChainRejection?

    /// Profile deviations. Present whether or not the chain is trusted — a rejected chain may also
    /// have been the wrong sort of certificate, and hiding that behind the rejection would make the
    /// second scan just as confusing as the first.
    public let findings: [V2GProfileFinding]

    public var isTrusted: Bool { rejection == nil }
}


/// Validates a contract certificate chain against a trust store.
///
/// ## Two questions, deliberately not one
///
/// **Is the chain sound?** Path building to a trusted root, signatures, validity dates, CA flags,
/// path-length constraints. RFC 5280's question, answered by `swift-certificates`, which is where
/// this belongs: chain validation is subtle, well specified and thoroughly implemented elsewhere.
///
/// **Does the leaf match the ISO 15118 profile?** Our question, and one PKIX has no opinion about.
/// A contract certificate carrying `serverAuth` builds a perfect path; it is simply a credential
/// that could also impersonate a station. So it is *reported*, not rejected.
///
/// Both are held to `Vectors/Certificate.chain.vectors.json`, generated from `WWCP_ISO15118_PKI` —
/// including its evil-certificate factory, which exists precisely to defeat validators that stop at
/// the path maths.
///
/// ## What is not here
///
/// Revocation. ISO 15118-20 staples OCSP into the TLS handshake, which is the transport's business
/// rather than the wallet's, and a contract certificate is not checked for revocation mid-session by
/// anything in this project. Naming it rather than leaving it to be assumed.
public struct V2GChainValidator: Sendable {

    public init() {}

    public func validate(_ chain: V2GCertificateChain,
                         against store: some V2GTrustStore,
                         now: Date = Date()) async -> V2GChainVerdict {

        // First, because everything after it assumes we know which certificate is the leaf. A bundle
        // that does not link up does not say, and no finding about a guessed leaf would be worth
        // reporting — hence no findings here either.
        guard chain.linksUp else {
            return V2GChainVerdict(rejection: .bundleDoesNotLinkUp, findings: [])
        }

        let findings = profileFindings(for: chain.leaf)

        guard !store.roots.isEmpty else {
            return V2GChainVerdict(rejection: .noTrustAnchors, findings: findings)
        }

        // Everything but the leaf is offered as an untrusted intermediate, so the verifier does the
        // path building rather than this code believing the bundle's own account of itself.
        let leaf = chain.leaf
        let intermediates = CertificateStore(chain.all.filter { $0.der != leaf.der }.map(\.certificate))

        var verifier = Verifier(rootCertificates: CertificateStore(store.roots.map(\.certificate))) {
            RFC5280Policy(validationTime: now)
        }

        let result = await verifier.validate(leafCertificate: leaf.certificate,
                                             intermediates: intermediates)

        switch result {
        case .validCertificate:
            return V2GChainVerdict(rejection: nil, findings: findings)
        case .couldNotValidate(let failures):
            let detail = failures.isEmpty
                ? "no candidate path reached a trusted root"
                : failures.map { String(describing: $0) }.joined(separator: "; ")
            return V2GChainVerdict(rejection: .noPathToATrustedRoot(detail: detail), findings: findings)
        }
    }

    /// The ISO 15118 profile questions, asked of the leaf alone. Everything here is a property of the
    /// certificate rather than of the path, which is why it survives a rejection.
    private func profileFindings(for leaf: V2GCertificate) -> [V2GProfileFinding] {

        var found: [V2GProfileFinding] = []

        if leaf.permitsServerAuth      { found.append(.serverAuthOnContractCertificate) }
        if leaf.isCertificateAuthority { found.append(.contractCertificateIsMarkedAsCa) }
        if leaf.commonName == nil      { found.append(.noCommonName) }

        return found
    }
}
