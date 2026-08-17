package cloud.charging.v2g.evcc

import java.security.PrivateKey

import cloud.charging.v2g.keystore.V2GSigner

/** Which of the two ISO 15118-2 provisioning exchanges an EVCC runs. */
enum class Iso2CertificateAction {

    /** The car has no contract and asks for one, signing with its OEM provisioning key. */
    Install,

    /** The car has a contract that is running out and asks for a fresh one, signing with the expiring
     *  contract's key — which is also the key the answer is encrypted for. */
    Update,
}

/**
 * The credentials that make an [Evcc2] ask for a contract.
 *
 * When set, and the station advertises the certificate service, the EVCC selects that service and
 * runs a `CertificateInstallationReq` or `CertificateUpdateReq` before PaymentDetails, then verifies
 * the four-reference response signature and ECDH-unwraps the issued contract private key.
 *
 * One type for both exchanges, because in -2 they differ in almost nothing but which credential plays
 * the part: an installation presents the OEM provisioning certificate, an update presents the
 * contract about to expire. Both sign their own request and both have the answer wrapped for the same
 * key they signed with — which is exactly why an update needs no other proof of who is asking.
 *
 * @property certificate The certificate to present (DER): the OEM provisioning one for an
 *   installation, the expiring contract for an update. Must carry a **P-256** key — that is the only
 *   curve the -2 key transport can wrap for.
 * @property signKey That certificate's key, for signing the request. A [V2GSigner] rather than a
 *   private key for the reason [PncEvccOptions]'s contract key is one: a credential held in a secure
 *   element has no bytes to hand over.
 * @property keyAgreement The same key as a plain [PrivateKey], for unwrapping the answer. Separate
 *   from [signKey], and not derivable from it: signing can be delegated to hardware that never reveals
 *   the scalar, but an ECDH agreement needs the private key itself. A credential that can only sign
 *   cannot receive a contract, and that is a property of the exchange rather than of this type.
 * @property emaid The contract identity, required for [Iso2CertificateAction.Update] (the message
 *   carries it) and unused for an installation, where the operator picks it.
 * @property subCertificates The MO sub-CAs of the expiring contract, for an update. -2's installation
 *   message has nowhere to put a chain and carries the OEM certificate alone.
 */
class Iso2CertInstallOptions(
    val certificate: ByteArray,
    val signKey: V2GSigner,
    val keyAgreement: PrivateKey,
    val action: Iso2CertificateAction = Iso2CertificateAction.Install,
    val emaid: String? = null,
    val subCertificates: List<ByteArray> = emptyList(),
)

/**
 * OEM-provisioning credentials that make an [Evcc20Base] request **contract provisioning**.
 *
 * When set — and the SECC announces `CertificateInstallationService` — the EVCC sends a signed
 * `CertificateInstallationReq` before its AuthorizationReq and processes the response: it verifies the
 * CPS signature over `SignedInstallationData` and ECDH-unwraps the issued contract private key.
 *
 * The OEM key must be **P-521** to take part in the -20 secp521r1 key agreement at all. A P-256
 * provisioning certificate — a -2-era one, which is what a Josev EVCC carries — reaches the station,
 * is answered, and cannot open the answer: there is no shared curve to agree on.
 */
class CertInstallEvccOptions(
    val oemCertificate: ByteArray,
    val oemSubCertificates: List<ByteArray>,
    val oemSignKey: V2GSigner,
    val oemKeyAgreement: PrivateKey,
)
