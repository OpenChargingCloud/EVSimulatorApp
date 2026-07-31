import Foundation
import SwiftASN1
import X509

/// An X.509 certificate, read.
///
/// ## Why this type exists at all
///
/// It is a thin surface over `swift-certificates`, and thin on purpose: it is the **only** place in
/// this package that knows that library exists. Everything above it — the EVCC today, B2's wallet
/// later — sees this type instead. That keeps the dependency one target wide, so vendoring or
/// replacing it costs one file rather than a search across the tree.
///
/// The other reason is parity. C# reads certificates with
/// `System.Security.Cryptography.X509Certificates` and Kotlin with `java.security.cert`; both get a
/// small, obvious API over a well-tested implementation. This is Swift's equivalent, and it is
/// deliberately shaped like theirs rather than like `swift-certificates`, so the three back ends
/// read alike at the call site.
///
/// ## What it does not do
///
/// **No validation.** Nothing here checks a signature, a chain, an expiry or a key usage. Parsing
/// and trusting are different operations, and conflating them is how a "the certificate parsed"
/// check quietly becomes a security decision. Chain validation belongs to the TLS stack and, for the
/// wallet, to an explicit policy — see `docs/CONCEPT.md` §4.1.
public struct V2GCertificate: Sendable {

    internal let certificate: Certificate

    /// The DER this was read from, byte for byte. Kept rather than re-serialised: a certificate that
    /// round-trips through *any* library is not guaranteed to reproduce its input, and what belongs
    /// on the wire is what arrived.
    public let der: [UInt8]

    public init(der: [UInt8]) throws {
        self.certificate = try Certificate(derEncoded: der)
        self.der = der
    }

    /// The subject's Common Name, if it has one.
    ///
    /// This is what an eMAID is carried as, and what C#'s `GetNameInfo(X509NameType.SimpleName, …)`
    /// returns for the certificates this project deals with. A subject with several CNs yields the
    /// first; that is a shape no ISO 15118 credential has, and if one ever shows up it should be a
    /// finding rather than a silent pick — hence `commonNames` below, which the wallet can check.
    public var commonName: String? { commonNames.first }

    /// The eMAID this certificate carries, or `nil` if its Common Name cannot be one.
    ///
    /// ## Why this is not just `commonName`
    ///
    /// ISO 15118-2 constrains `eMAIDType` to **14 or 15 characters** (`V2G_CI_MsgDataTypes.xsd`:
    /// `minLength 14`, `maxLength 15`) — the provider and instance identifiers, with the check digit
    /// optional. A Common Name outside that range simply cannot be sent as an eMAID, so a contract
    /// certificate carrying one is unusable for Plug & Charge no matter how well it parses.
    ///
    /// The distinction matters at exactly the moment this project cares about: a certificate arrives
    /// by QR code and is installed. Telling the user *then* that its identity is not a valid eMAID is
    /// worth a great deal more than discovering it at a charger, halfway through PaymentDetails.
    ///
    /// **Length is all that is checked here**, because length is all the schema constrains. The
    /// richer EMAID grammar — country code, provider id, check digit — is a separate concern and a
    /// separate check; claiming to validate it here without implementing it would be worse than not
    /// claiming it. ISO 15118-20 has no eMAID type at all: it carries the identity in
    /// `identifierType`, which allows 255 characters, so this rule is a -2 rule.
    public var emaid: String? {
        guard let name = commonName, Self.emaidLength.contains(name.count) else { return nil }
        return name
    }

    /// True when `commonName` exists but is not a usable eMAID — the case the wallet should report
    /// rather than silently drop, since the certificate is otherwise perfectly well-formed.
    public var hasUnusableEmaid: Bool { commonName != nil && emaid == nil }

    /// ISO 15118-2 `eMAIDType`: 14 characters without the check digit, 15 with it.
    private static let emaidLength = 14...15

    /// Every Common Name in the subject, in order.
    public var commonNames: [String] {
        certificate.subject.flatMap { rdn in
            rdn.compactMap { attribute in
                attribute.type == ASN1ObjectIdentifier.RDNAttributeType.commonName
                    ? attribute.value.description
                    : nil
            }
        }
    }

    /// The subject as an RFC 4514 string, for display and for tests to pin.
    public var subject: String { certificate.subject.description }

    /// The issuer as an RFC 4514 string.
    public var issuer: String { certificate.issuer.description }

    /// The serial number, as the unsigned big-endian bytes the certificate carries.
    public var serialNumber: [UInt8] { Array(certificate.serialNumber.bytes) }

    public var notValidBefore: Date { certificate.notValidBefore }
    public var notValidAfter: Date { certificate.notValidAfter }

    /// True when the subject and issuer names match. **Not** a claim that the signature verifies —
    /// see the type comment on why nothing here validates.
    public var isSelfIssued: Bool { certificate.subject == certificate.issuer }

    /// True when `basicConstraints` marks this as a CA. A certificate that is not one cannot be a
    /// trust anchor and cannot have issued anything.
    ///
    /// A *missing* basicConstraints extension counts as "not a CA", which is both what RFC 5280
    /// means and the safe reading: the evil-certificate factory has a `no_basic_constraints` variant
    /// precisely because treating absence as permission is a real and exploited mistake.
    public var isCertificateAuthority: Bool {
        guard case .some(.isCertificateAuthority) = try? certificate.extensions.basicConstraints
        else { return false }
        return true
    }

    /// How many CAs may appear below this one, when it says. `nil` means unconstrained or not a CA.
    public var maxPathLength: Int? {
        guard case .some(.isCertificateAuthority(let maxPathLength)) =
                try? certificate.extensions.basicConstraints
        else { return nil }
        return maxPathLength
    }

    /// True when the extended key usage permits `serverAuth` — a purpose a contract certificate has
    /// no business carrying, since it would let the same credential be presented as a station.
    public var permitsServerAuth: Bool {
        guard let eku = try? certificate.extensions.extendedKeyUsage else { return false }
        return eku.contains(ExtendedKeyUsage.Usage.serverAuth)
    }

    /// True when this certificate's key produced `other`'s signature — the check behind a vouched
    /// root rotation. A **signature** check, deliberately: matching issuer and subject names proves
    /// nothing, since both are written by whoever made the certificate.
    ///
    /// It says nothing about whether *this* certificate deserves trust. That is the store's question.
    public func hasSigned(_ other: V2GCertificate) -> Bool {
        (try? certificate.publicKey.isValidSignature(other.certificate.signature,
                                                     for: other.certificate)) ?? false
    }

    /// The subject public key, DER-encoded — for asking whether two certificates carry the *same*
    /// key, which is what separates a CA renewing itself from a different CA taking its name.
    public var publicKeyDer: [UInt8] {
        Array(certificate.publicKey.subjectPublicKeyInfoBytes)
    }
}
