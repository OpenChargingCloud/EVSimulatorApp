import CryptoKit
import Foundation

import ExiIso2
import ExiIso20Common
import ExiXmlDsig
import V2GCertificates
import V2GKeystore

/// Contract credentials that switch an EVCC from EIM to **Plug & Charge**. `nil` (the default) keeps
/// the session on external payment.
///
/// The key is P-256 for -2, whose raw `r‖s` signature field is 64 bytes — a curve with a larger
/// field simply does not fit, which is the kind of constraint that fails loudly rather than subtly.
public struct PncEvccOptions {

    public let contractCertificate: [UInt8]
    public let subCertificates: [[UInt8]]
    /// Signs, and need not be readable.
    ///
    /// A `V2GSigner` rather than a private key because a secure-element key **has no bytes to hand
    /// over** — see `docs/CONCEPT.md` §3.4. Taking a key here would have ruled out hardware backing
    /// for every credential, whatever its curve, and that is not a thing one retrofits cheaply.
    public let contractKey: any V2GSigner

    /// The eMAID this credential can present in -2's PaymentDetails, read from the contract
    /// certificate's Common Name — as C# and Kotlin both do. `nil` when the Common Name cannot be
    /// one.
    ///
    /// **Optional on purpose, and this was a correction.** An earlier version refused the credential
    /// outright, which is stricter than the standard: the 14–15 character rule is a *-2* rule, from
    /// `eMAIDType` in `V2G_CI_MsgDataTypes.xsd`. ISO 15118-20 never sends the eMAID from the
    /// certificate at all — it carries only the chain — so a credential that cannot do -2 Plug &
    /// Charge may be perfectly usable for -20. Refusing it here would have blocked both.
    ///
    /// ``Evcc2`` therefore checks it before opening a session, and ``Evcc20Base`` never looks. An app
    /// installing a chain from a QR code should check ``V2GCertificate/hasUnusableEmaid`` at that
    /// moment and say so, which is where the warning actually belongs.
    public let emaid: String?

    /// The algorithms of a -20 `AuthorizationReq` signature: ``XmlDsigSuite/iso20`` unless a Josev
    /// station is on the other end. -2 ignores it.
    public let suite: XmlDsigSuite

    /// - Throws: if the contract certificate will not parse at all.
    public init(contractCertificate: [UInt8], subCertificates: [[UInt8]],
                contractKey: any V2GSigner, suite: XmlDsigSuite = .iso20) throws {

        self.emaid = try V2GCertificate(der: contractCertificate).emaid
        self.contractCertificate = contractCertificate
        self.subCertificates = subCertificates
        self.contractKey = contractKey
        self.suite = suite
    }
}


/// Which algorithms a -20 header signature declares and uses. A port of C# `XmlDsigSuite`.
///
/// The grammar is the same for both: the `SignedInfo` is signed in its standalone xmldsig encoding,
/// the entry point [V2G20-1449] names. Josev and EVerest 2026.10.0 both verify those octets.
///
/// - ``iso20`` is `ecdsa-sha512` over a SHA-512 digest ([V2G20-2473], [V2G20-2475]). It is the
///   default. Our car signed SHA-256 until 2026-11-27, and EVerest refused it with
///   `FAILED_SignatureError`.
/// - ``josevSha256`` is `ecdsa-sha256` over SHA-256, which is all Josev verifies. -2 always uses it.
public enum XmlDsigSuite: Sendable, CaseIterable {
    case iso20
    case josevSha256

    public var signatureMethod: String {
        switch self {
        case .iso20:       return "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512"
        case .josevSha256: return "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256"
        }
    }

    public var digestMethod: String {
        switch self {
        case .iso20:       return "http://www.w3.org/2001/04/xmlenc#sha512"
        case .josevSha256: return "http://www.w3.org/2001/04/xmlenc#sha256"
        }
    }

    public var hash: V2GSignatureHash {
        switch self {
        case .iso20:       return .sha512
        case .josevSha256: return .sha256
        }
    }

    /// The `DigestValue` of a reference under this suite.
    public func digest(_ bytes: [UInt8]) -> [UInt8] {
        switch self {
        case .iso20:       return Array(SHA512.hash(data: Data(bytes)))
        case .josevSha256: return Array(SHA256.hash(data: Data(bytes)))
        }
    }

    /// The suite a `SignatureMethod` URI names, or `nil` for one this form does not know.
    static func declared(by signatureMethod: String) -> XmlDsigSuite? {
        allCases.first { $0.signatureMethod == signatureMethod }
    }
}


/// Signing a Plug & Charge request, in the form a live Josev peer produces and accepts.
///
/// ## The one non-obvious thing
///
/// The `SignedInfo` that travels **in the message** is encoded under its own message set's grammar,
/// but the octets actually **signed** are the same `SignedInfo` encoded under the *standalone* W3C
/// xmldsig grammar — the one `ExiXmlDsig` exists for. Those are different bytes for the same
/// structure, because the fragment selector is sized over a different set of global elements. Signing
/// the message-set encoding instead would produce a signature that verifies locally and nowhere else.
///
/// ## Why the mapping is a copy and not a cast
///
/// `SignedInfoType` is generated three times over — once per message set, once standalone — from the
/// same `xmldsig-core-schema.xsd`. Structurally identical, mutually unassignable, exactly like the
/// three `MessageHeaderType`s ``SessionContext`` reconciles. So the map below is a field-for-field
/// copy that looks like boilerplate and is not.
///
/// ## Which algorithms
///
/// -2 is always `ecdsa-sha256` over a SHA-256 digest. -20 follows an ``XmlDsigSuite``:
/// `ecdsa-sha512` over SHA-512 by default, Josev's SHA-256 pair on request.
enum XmlDsigInterop {

    private static let canonicalizationExi = "http://www.w3.org/TR/canonical-exi/"

    // ── ISO 15118-2 ───────────────────────────────────────────────────────

    /// -2 has one suite, and it is the SHA-256 pair.
    static func sign2(_ referenceId: String, _ signedElementFragment: [UInt8],
                      _ contractKey: any V2GSigner) throws -> ExiIso2.SignatureType {

        let suite = XmlDsigSuite.josevSha256
        let signedInfo = ExiIso2.SignedInfoType(
            canonicalizationMethod: ExiIso2.CanonicalizationMethodType(algorithm: canonicalizationExi),
            signatureMethod: ExiIso2.SignatureMethodType(algorithm: suite.signatureMethod),
            reference: [ExiIso2.ReferenceType(
                uRI: "#" + referenceId,
                transforms: ExiIso2.TransformsType(transform: [
                    ExiIso2.TransformType(algorithm: canonicalizationExi)]),
                digestMethod: ExiIso2.DigestMethodType(algorithm: suite.digestMethod),
                digestValue: suite.digest(signedElementFragment))])

        return ExiIso2.SignatureType(
            signedInfo: signedInfo,
            signatureValue: ExiIso2.SignatureValueType(
                value: try sign(standaloneOctets(signedInfo), contractKey, suite)))
    }

    /// The octets a -2 signature is actually over. Shared with verification, because deriving them
    /// twice is how the two drift apart.
    static func standaloneOctets(_ signedInfo: ExiIso2.SignedInfoType) -> [UInt8] {
        XmlDsigCodec.encodeFragment_SignedInfo(ExiXmlDsig.SignedInfoType(
            id: signedInfo.id,
            canonicalizationMethod: ExiXmlDsig.CanonicalizationMethodType(
                algorithm: signedInfo.canonicalizationMethod.algorithm,
                aNY: signedInfo.canonicalizationMethod.aNY),
            signatureMethod: ExiXmlDsig.SignatureMethodType(
                algorithm: signedInfo.signatureMethod.algorithm,
                hMACOutputLength: signedInfo.signatureMethod.hMACOutputLength,
                aNY: signedInfo.signatureMethod.aNY),
            reference: signedInfo.reference.map { r in
                ExiXmlDsig.ReferenceType(
                    id: r.id, type: r.type, uRI: r.uRI,
                    transforms: r.transforms.map { t in
                        ExiXmlDsig.TransformsType(transform: t.transform.map {
                            ExiXmlDsig.TransformType(algorithm: $0.algorithm, xPath: $0.xPath, aNY: $0.aNY)
                        })
                    },
                    digestMethod: ExiXmlDsig.DigestMethodType(
                        algorithm: r.digestMethod.algorithm, aNY: r.digestMethod.aNY),
                    digestValue: r.digestValue)
            }))
    }

    // ── ISO 15118-20 CommonMessages ───────────────────────────────────────

    static func sign20(_ referenceId: String, _ signedElementFragment: [UInt8],
                       _ contractKey: any V2GSigner,
                       _ suite: XmlDsigSuite) throws -> ExiIso20Common.SignatureType {

        let signedInfo = ExiIso20Common.SignedInfoType(
            canonicalizationMethod: ExiIso20Common.CanonicalizationMethodType(algorithm: canonicalizationExi),
            signatureMethod: ExiIso20Common.SignatureMethodType(algorithm: suite.signatureMethod),
            reference: [ExiIso20Common.ReferenceType(
                uRI: "#" + referenceId,
                transforms: ExiIso20Common.TransformsType(transform: [
                    ExiIso20Common.TransformType(algorithm: canonicalizationExi)]),
                digestMethod: ExiIso20Common.DigestMethodType(algorithm: suite.digestMethod),
                digestValue: suite.digest(signedElementFragment))])

        return ExiIso20Common.SignatureType(
            signedInfo: signedInfo,
            signatureValue: ExiIso20Common.SignatureValueType(
                value: try sign(standaloneOctets(signedInfo), contractKey, suite)))
    }

    /// The -20 counterpart. Identical shape, unassignable type — see the enum's comment.
    static func standaloneOctets(_ signedInfo: ExiIso20Common.SignedInfoType) -> [UInt8] {
        XmlDsigCodec.encodeFragment_SignedInfo(ExiXmlDsig.SignedInfoType(
            id: signedInfo.id,
            canonicalizationMethod: ExiXmlDsig.CanonicalizationMethodType(
                algorithm: signedInfo.canonicalizationMethod.algorithm,
                aNY: signedInfo.canonicalizationMethod.aNY),
            signatureMethod: ExiXmlDsig.SignatureMethodType(
                algorithm: signedInfo.signatureMethod.algorithm,
                hMACOutputLength: signedInfo.signatureMethod.hMACOutputLength,
                aNY: signedInfo.signatureMethod.aNY),
            reference: signedInfo.reference.map { r in
                ExiXmlDsig.ReferenceType(
                    id: r.id, type: r.type, uRI: r.uRI,
                    transforms: r.transforms.map { t in
                        ExiXmlDsig.TransformsType(transform: t.transform.map {
                            ExiXmlDsig.TransformType(algorithm: $0.algorithm, xPath: $0.xPath, aNY: $0.aNY)
                        })
                    },
                    digestMethod: ExiXmlDsig.DigestMethodType(
                        algorithm: r.digestMethod.algorithm, aNY: r.digestMethod.aNY),
                    digestValue: r.digestValue)
            }))
    }

    // ── the crypto, such as it is ─────────────────────────────────────────

    /// Raw `r‖s` — no DER anywhere, and the field is sized to the curve so a DER signature would not
    /// fit even by accident. Which key does the work, and whether its bytes exist at all, is the
    /// signer's business and not this file's.
    private static func sign(_ octets: [UInt8], _ key: any V2GSigner,
                             _ suite: XmlDsigSuite) throws -> [UInt8] {
        try key.signature(over: octets, hash: suite.hash)
    }

    /// Verifies raw `r‖s` over `octets` under the hash `signatureMethod` declares. An algorithm this
    /// form does not know is `false`, not a guess. The hash comes from the declaration because a
    /// verifier with its own fixed hash would refuse every signature made under the other suite.
    static func verify(_ octets: [UInt8], _ signatureValue: [UInt8],
                       _ publicKey: P256.Signing.PublicKey, signatureMethod: String) -> Bool {
        guard let suite = XmlDsigSuite.declared(by: signatureMethod),
              let signature = try? P256.Signing.ECDSASignature(rawRepresentation: Data(signatureValue))
        else { return false }
        switch suite.hash {
        case .sha256: return publicKey.isValidSignature(signature, for: SHA256.hash(data: Data(octets)))
        case .sha512: return publicKey.isValidSignature(signature, for: SHA512.hash(data: Data(octets)))
        }
    }

    /// The P-521 half, which arrived with contract provisioning. The digest is the declaration's,
    /// never the curve's: handing the octets to CryptoKit would hash them with SHA-512 whatever the
    /// `SignedInfo` says. See ``V2GSigner/signature(over:hash:)`` for the same point on the signing side.
    static func verify(_ octets: [UInt8], _ signatureValue: [UInt8],
                       _ publicKey: P521.Signing.PublicKey, signatureMethod: String) -> Bool {
        guard let suite = XmlDsigSuite.declared(by: signatureMethod),
              let signature = try? P521.Signing.ECDSASignature(rawRepresentation: Data(signatureValue))
        else { return false }
        switch suite.hash {
        case .sha256: return publicKey.isValidSignature(signature, for: SHA256.hash(data: Data(octets)))
        case .sha512: return publicKey.isValidSignature(signature, for: SHA512.hash(data: Data(octets)))
        }
    }
}
