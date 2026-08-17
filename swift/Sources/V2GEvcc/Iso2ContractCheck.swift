import CryptoKit
import Foundation

import ExiIso2
import V2GCertificates

/// The five fields a `CertificateInstallationRes` and a `CertificateUpdateRes` both carry, in the
/// same order, and which everything downstream of the response actually reads.
public struct Iso2ProvisioningPayload {

    /// The station's SA provisioning chain — whose leaf key signed the response, so this is both a
    /// payload and the verify key for the signature over it.
    public let provisioningChain: CertificateChainType

    /// The issued contract certificate and its MO sub-CAs.
    public let contractChain: CertificateChainType

    /// IV(16) ‖ AES-128-CBC ciphertext(32) of the contract's private scalar.
    public let encryptedKey: ContractSignatureEncryptedPrivateKeyType

    /// The station's ephemeral P-256 point, uncompressed (65 B).
    public let dhPublicKey: DiffieHellmanPublickeyType

    /// The identity the contract was issued under.
    public let emaid: EMAIDType

    public let responseCode: ResponseCode
}

/// An EVCC's verdict over a contract-provisioning response (§7.9.2.4.2).
public struct Iso2ContractVerdict: Equatable, Sendable {

    /// The response header carried a Signature at all. A station that sends none has issued a
    /// contract nobody vouched for.
    public let signaturePresent: Bool

    /// How many References the SignedInfo carried. §7.9.2.4.2 asks for exactly four, and reporting
    /// the count separately is what distinguishes *one digest is wrong* from *the station only
    /// signed some of what it sent* — two different failures with one boolean between them.
    public let references: Int

    /// All four references were present, each matched by URI to its element's Id, and each digest
    /// equals the SHA-256 of that element's own EXI fragment. Answerable without any key.
    ///
    /// **False when ``references`` is not four**, deliberately: three sound digests are not a signed
    /// response, they are a signed part of one.
    public let digestOk: Bool

    /// The ECDSA signature over the SignedInfo verified against the leaf of the provisioning chain
    /// the response itself carried.
    public let signatureOk: Bool

    /// Which grammar matched: `iso2-msgdef`, `xmldsig-standalone`, or `none` when neither did.
    public let signatureGrammar: String
}

/// §7.9.2.4.2 — what an EVCC must make of the answer to its `CertificateInstallationReq` or
/// `CertificateUpdateReq`.
///
/// A port of C#'s `Iso2ContractCheck`, held to the same corpus:
/// `Contract.provisioning.vectors.json` carries whole response frames and the verdict each must
/// produce. That corpus exists because **the verdict never reaches the wire** — the car checks the
/// response and tells the station nothing — so a recorded session proves this code can *parse* a
/// provisioning answer and can never prove it *judges* one.
///
/// ## Four references, and all four have to hold
///
/// Every other signed -2 message has one. Here the contract chain, the encrypted private key, the DH
/// public point and the eMAID are each digested separately, and a car that checked only the chain
/// would accept an encrypted key nobody signed for — which is to say it would install a private key
/// of the attacker's choosing under a certificate the operator really did issue.
///
/// ## This is only half of what makes a contract usable
///
/// The other half is that the unwrapped key belongs to the certificate it arrived with, and it lives
/// in ``ContractProvisioning2/matches(_:_:)`` rather than here because it needs the car's own private
/// key and this type deliberately needs nothing but the response.
public enum Iso2ContractCheck {

    /// What §7.9.2.4.2 asks the station to sign: the contract chain, the encrypted key, the DH point,
    /// and the eMAID.
    public static let requiredReferences = 4

    /// The common fields of either response, or `nil` for anything else. The two messages differ in
    /// almost nothing but the update's trailing RetryCounter, which no verifier reads.
    public static func unpack(_ body: BodyBaseType?) -> Iso2ProvisioningPayload? {

        if let r = body as? CertificateInstallationResType {
            return Iso2ProvisioningPayload(provisioningChain: r.sAProvisioningCertificateChain,
                                           contractChain:     r.contractSignatureCertChain,
                                           encryptedKey:      r.contractSignatureEncryptedPrivateKey,
                                           dhPublicKey:       r.dHpublickey,
                                           emaid:             r.eMAID,
                                           responseCode:      r.responseCode)
        }

        if let r = body as? CertificateUpdateResType {
            return Iso2ProvisioningPayload(provisioningChain: r.sAProvisioningCertificateChain,
                                           contractChain:     r.contractSignatureCertChain,
                                           encryptedKey:      r.contractSignatureEncryptedPrivateKey,
                                           dhPublicKey:       r.dHpublickey,
                                           emaid:             r.eMAID,
                                           responseCode:      r.responseCode)
        }

        return nil
    }

    /// Evaluates a provisioning response against the header signature it arrived with.
    ///
    /// No verify key is passed in — unlike every other check here — because the station sends its
    /// own: the signature is made by the leaf of `SAProvisioningCertificateChain`, which travels in
    /// the message. What makes that chain trustworthy is a separate question, and one the trust store
    /// answers.
    public static func evaluate(_ body: BodyBaseType?,
                                headerSignature: SignatureType?) -> Iso2ContractVerdict {

        guard let signature = headerSignature, let payload = unpack(body) else {
            return Iso2ContractVerdict(signaturePresent: false, references: 0,
                                       digestOk: false, signatureOk: false, signatureGrammar: "none")
        }

        let references = signature.signedInfo.reference.count

        // (1) the four digests, each over its own element's EXI fragment. A reference count other
        //     than four fails here rather than being read as "the ones present are fine".
        func matches(_ id: String?, _ fragment: @autoclosure () -> [UInt8]) -> Bool {
            guard let id,
                  let reference = signature.signedInfo.reference.first(where: { $0.uRI == "#" + id })
            else { return false }
            return V2GSignature.verifyReference(reference, fragment: fragment())
        }

        let digestOk =
            references == requiredReferences &&
            matches(payload.contractChain.id, Iso15118_2Codec.encodeFragment_ContractSignatureCertChain(payload.contractChain)) &&
            matches(payload.encryptedKey.id,  Iso15118_2Codec.encodeFragment_ContractSignatureEncryptedPrivateKey(payload.encryptedKey)) &&
            matches(payload.dhPublicKey.id,   Iso15118_2Codec.encodeFragment_DHpublickey(payload.dhPublicKey)) &&
            matches(payload.emaid.id,         Iso15118_2Codec.encodeFragment_eMAID(payload.emaid))

        // (2) the ECDSA signature over the SignedInfo, against the chain the station sent. Both
        //     grammars, and which one matched is reported for the reason Iso2TariffCheck reports it:
        //     "it verified" and "it verified the way the standard says" are different facts.
        guard let leaf = try? V2GCertificate(der: payload.provisioningChain.certificate),
              let verifyKey = leaf.p256VerificationKey
        else {
            // An unparseable provisioning certificate leaves the signature unestablished rather than
            // failed, and the car must not install what it cannot check either way.
            return Iso2ContractVerdict(signaturePresent: true, references: references,
                                       digestOk: digestOk, signatureOk: false, signatureGrammar: "none")
        }

        let value = signature.signatureValue.value

        if V2GSignature.verify(signature.signedInfo, signature: value, with: verifyKey) {
            return Iso2ContractVerdict(signaturePresent: true, references: references,
                                       digestOk: digestOk, signatureOk: true, signatureGrammar: "iso2-msgdef")
        }

        if XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(signature.signedInfo), value, verifyKey) {
            return Iso2ContractVerdict(signaturePresent: true, references: references,
                                       digestOk: digestOk, signatureOk: true, signatureGrammar: "xmldsig-standalone")
        }

        return Iso2ContractVerdict(signaturePresent: true, references: references,
                                   digestOk: digestOk, signatureOk: false, signatureGrammar: "none")
    }
}
