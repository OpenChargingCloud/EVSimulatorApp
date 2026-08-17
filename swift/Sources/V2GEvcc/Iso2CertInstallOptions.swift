import CryptoKit
import Foundation

import V2GKeystore

/// Which of the two ISO 15118-2 provisioning exchanges an EVCC runs.
public enum Iso2CertificateAction: Sendable {

    /// The car has no contract and asks for one, signing with its OEM provisioning key.
    case install

    /// The car has a contract that is running out and asks for a fresh one, signing with the expiring
    /// contract's key — which is also the key the answer is encrypted for.
    case update
}

/// The credentials that make an ``Evcc2`` ask for a contract.
///
/// When set, and the station advertises the certificate service, the EVCC selects that service and
/// runs a `CertificateInstallationReq` or `CertificateUpdateReq` before PaymentDetails, then verifies
/// the four-reference response signature and ECDH-unwraps the issued contract private key.
///
/// One type for both exchanges, because in -2 they differ in almost nothing but which credential
/// plays the part: an installation presents the OEM provisioning certificate, an update presents the
/// contract about to expire. Both sign their own request and both have the answer wrapped for the
/// same key they signed with — which is exactly why an update needs no other proof of who is asking.
public struct Iso2CertInstallOptions {

    /// The certificate to present (DER): the OEM provisioning one for an installation, the expiring
    /// contract for an update. Must carry a **P-256** key — that is the only curve the -2 key
    /// transport can wrap for.
    public let certificate: [UInt8]

    /// That certificate's key, for signing the request. A ``V2GSigner`` rather than a private key for
    /// the reason ``PncEvccOptions/contractKey`` is one: an OEM credential held in a secure element
    /// has no bytes to hand over.
    public let signKey: any V2GSigner

    /// The same key as a key-agreement handle, for unwrapping the answer.
    ///
    /// Separate from ``signKey``, and not derivable from it: signing can be delegated to hardware that
    /// never reveals the scalar, but an ECDH agreement needs the private key itself. A credential that
    /// can only sign cannot receive a contract, and that is a property of the exchange rather than of
    /// this type.
    public let keyAgreement: P256.KeyAgreement.PrivateKey

    public let action: Iso2CertificateAction

    /// The contract identity, required for ``Iso2CertificateAction/update`` (the message carries it)
    /// and unused for an installation, where the operator picks it.
    public let emaid: String?

    /// The MO sub-CAs of the expiring contract, for an update. -2's installation message has nowhere
    /// to put a chain and carries the OEM certificate alone.
    public let subCertificates: [[UInt8]]

    public init(certificate: [UInt8],
                signKey: any V2GSigner,
                keyAgreement: P256.KeyAgreement.PrivateKey,
                action: Iso2CertificateAction = .install,
                emaid: String? = nil,
                subCertificates: [[UInt8]] = []) {

        self.certificate     = certificate
        self.signKey         = signKey
        self.keyAgreement    = keyAgreement
        self.action          = action
        self.emaid           = emaid
        self.subCertificates = subCertificates
    }
}


/// OEM-provisioning credentials that make an ``Evcc20Base`` request **contract provisioning**.
///
/// When set — and the SECC announces `CertificateInstallationService` — the EVCC sends a signed
/// `CertificateInstallationReq` before its AuthorizationReq and processes the response: it verifies
/// the CPS signature over `SignedInstallationData` and ECDH-unwraps the issued contract private key.
///
/// The OEM key must be **P-521** to take part in the -20 secp521r1 key agreement at all. A P-256
/// provisioning certificate — a -2-era one, which is what a Josev EVCC carries — reaches the station,
/// is answered, and cannot open the answer: there is no shared curve to agree on.
public struct CertInstallEvccOptions {

    /// The OEM provisioning leaf certificate (DER, P-521).
    public let oemCertificate: [UInt8]

    /// The OEM sub-CA certificates (DER), leaf-issuer first.
    public let oemSubCertificates: [[UInt8]]

    /// The OEM leaf's key, for signing the request.
    public let oemSignKey: any V2GSigner

    /// The same key as a key-agreement handle, for unwrapping the contract key.
    public let oemKeyAgreement: P521.KeyAgreement.PrivateKey

    public init(oemCertificate: [UInt8],
                oemSubCertificates: [[UInt8]],
                oemSignKey: any V2GSigner,
                oemKeyAgreement: P521.KeyAgreement.PrivateKey) {

        self.oemCertificate     = oemCertificate
        self.oemSubCertificates = oemSubCertificates
        self.oemSignKey         = oemSignKey
        self.oemKeyAgreement    = oemKeyAgreement
    }
}
