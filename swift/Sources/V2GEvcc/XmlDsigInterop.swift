import CryptoKit
import Foundation

import ExiIso2
import ExiIso20Common
import ExiXmlDsig

/// Contract credentials that switch an EVCC from EIM to **Plug & Charge**. `nil` (the default) keeps
/// the session on external payment.
///
/// The key is P-256 because the interop form's raw `r‖s` signature field is 64 bytes — a curve with
/// a larger field simply does not fit, which is the kind of constraint that fails loudly rather than
/// subtly.
public struct PncEvccOptions {

    public let contractCertificate: [UInt8]
    public let subCertificates: [[UInt8]]
    public let contractKey: P256.Signing.PrivateKey
    /// The eMAID sent in -2's PaymentDetails. Supplied rather than derived: reading the CN out of the
    /// certificate needs an X.509 parser, which this Foundation-light package does not have and does
    /// not need — the identity is known to whoever provisioned the credentials.
    public let emaid: String

    public init(contractCertificate: [UInt8], subCertificates: [[UInt8]],
                contractKey: P256.Signing.PrivateKey, emaid: String) {
        self.contractCertificate = contractCertificate
        self.subCertificates = subCertificates
        self.contractKey = contractKey
        self.emaid = emaid
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
enum XmlDsigInterop {

    private static let canonicalizationExi = "http://www.w3.org/TR/canonical-exi/"
    private static let ecdsaSha256         = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256"
    private static let sha256Uri           = "http://www.w3.org/2001/04/xmlenc#sha256"

    // ── ISO 15118-2 ───────────────────────────────────────────────────────

    static func sign2(_ referenceId: String, _ signedElementFragment: [UInt8],
                      _ contractKey: P256.Signing.PrivateKey) throws -> ExiIso2.SignatureType {

        let signedInfo = ExiIso2.SignedInfoType(
            canonicalizationMethod: ExiIso2.CanonicalizationMethodType(algorithm: canonicalizationExi),
            signatureMethod: ExiIso2.SignatureMethodType(algorithm: ecdsaSha256),
            reference: [ExiIso2.ReferenceType(
                uRI: "#" + referenceId,
                transforms: ExiIso2.TransformsType(transform: [
                    ExiIso2.TransformType(algorithm: canonicalizationExi)]),
                digestMethod: ExiIso2.DigestMethodType(algorithm: sha256Uri),
                digestValue: sha256(signedElementFragment))])

        return ExiIso2.SignatureType(
            signedInfo: signedInfo,
            signatureValue: ExiIso2.SignatureValueType(
                value: try sign(standaloneOctets(signedInfo), contractKey)))
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
                       _ contractKey: P256.Signing.PrivateKey) throws -> ExiIso20Common.SignatureType {

        let signedInfo = ExiIso20Common.SignedInfoType(
            canonicalizationMethod: ExiIso20Common.CanonicalizationMethodType(algorithm: canonicalizationExi),
            signatureMethod: ExiIso20Common.SignatureMethodType(algorithm: ecdsaSha256),
            reference: [ExiIso20Common.ReferenceType(
                uRI: "#" + referenceId,
                transforms: ExiIso20Common.TransformsType(transform: [
                    ExiIso20Common.TransformType(algorithm: canonicalizationExi)]),
                digestMethod: ExiIso20Common.DigestMethodType(algorithm: sha256Uri),
                digestValue: sha256(signedElementFragment))])

        return ExiIso20Common.SignatureType(
            signedInfo: signedInfo,
            signatureValue: ExiIso20Common.SignatureValueType(
                value: try sign(standaloneOctets(signedInfo), contractKey)))
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

    /// Raw `r‖s`, which is what CryptoKit's `rawRepresentation` already is — no DER anywhere, and the
    /// field is 64 bytes so a DER signature would not fit even by accident.
    private static func sign(_ octets: [UInt8], _ key: P256.Signing.PrivateKey) throws -> [UInt8] {
        Array(try key.signature(for: Data(octets)).rawRepresentation)
    }

    static func verify(_ octets: [UInt8], _ signatureValue: [UInt8],
                       _ publicKey: P256.Signing.PublicKey) -> Bool {
        guard let signature = try? P256.Signing.ECDSASignature(rawRepresentation: Data(signatureValue))
        else { return false }
        return publicKey.isValidSignature(signature, for: Data(octets))
    }

    private static func sha256(_ bytes: [UInt8]) -> [UInt8] {
        Array(SHA256.hash(data: Data(bytes)))
    }
}
