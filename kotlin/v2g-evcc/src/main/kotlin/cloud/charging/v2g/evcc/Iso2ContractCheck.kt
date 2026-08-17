package cloud.charging.v2g.evcc

import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.cert.CertificateFactory

import cloud.charging.v2g.iso2.BodyBaseType
import cloud.charging.v2g.iso2.CertificateChainType
import cloud.charging.v2g.iso2.CertificateInstallationResType
import cloud.charging.v2g.iso2.CertificateUpdateResType
import cloud.charging.v2g.iso2.ContractSignatureEncryptedPrivateKeyType
import cloud.charging.v2g.iso2.DiffieHellmanPublickeyType
import cloud.charging.v2g.iso2.EMAIDType
import cloud.charging.v2g.iso2.Iso15118_2Codec
import cloud.charging.v2g.iso2.ResponseCode
import cloud.charging.v2g.iso2.SignatureType
import cloud.charging.v2g.iso2.V2GSignature

/**
 * The five fields a `CertificateInstallationRes` and a `CertificateUpdateRes` both carry, in the same
 * order, and which everything downstream of the response actually reads.
 *
 * @property provisioningChain The station's SA provisioning chain — whose leaf key signed the response,
 *   so this is both a payload and the verify key for the signature over it.
 * @property contractChain The issued contract certificate and its MO sub-CAs.
 * @property encryptedKey IV(16) ‖ AES-128-CBC ciphertext(32) of the contract's private scalar.
 * @property dhPublicKey The station's ephemeral P-256 point, uncompressed (65 B).
 * @property emaid The identity the contract was issued under.
 */
data class Iso2ProvisioningPayload(
    val provisioningChain: CertificateChainType,
    val contractChain: CertificateChainType,
    val encryptedKey: ContractSignatureEncryptedPrivateKeyType,
    val dhPublicKey: DiffieHellmanPublickeyType,
    val emaid: EMAIDType,
    val responseCode: ResponseCode,
)

/**
 * An EVCC's verdict over a contract-provisioning response (§7.9.2.4.2).
 *
 * @property signaturePresent The response header carried a Signature at all. A station that sends none
 *   has issued a contract nobody vouched for.
 * @property references How many References the SignedInfo carried. §7.9.2.4.2 asks for exactly four, and
 *   reporting the count separately is what distinguishes *one digest is wrong* from *the station only
 *   signed some of what it sent* — two different failures with one boolean between them.
 * @property digestOk All four references were present, each matched by URI to its element's Id, and each
 *   digest equals the SHA-256 of that element's own EXI fragment. **False when [references] is not
 *   four**, deliberately: three sound digests are not a signed response, they are a signed part of one.
 * @property signatureOk The ECDSA signature over the SignedInfo verified against the leaf of the
 *   provisioning chain the response itself carried.
 * @property signatureGrammar Which grammar matched: `iso2-msgdef`, `xmldsig-standalone`, or `none`.
 */
data class Iso2ContractVerdict(
    val signaturePresent: Boolean,
    val references: Int,
    val digestOk: Boolean,
    val signatureOk: Boolean,
    val signatureGrammar: String,
)

/**
 * §7.9.2.4.2 — what an EVCC must make of the answer to its `CertificateInstallationReq` or
 * `CertificateUpdateReq`.
 *
 * A port of C#'s `Iso2ContractCheck`, held to the same corpus: `Contract.provisioning.vectors.json`
 * carries whole response frames and the verdict each must produce. That corpus exists because **the
 * verdict never reaches the wire** — the car checks the response and tells the station nothing — so a
 * recorded session proves this code can *parse* a provisioning answer and can never prove it *judges*
 * one.
 *
 * ## Four references, and all four have to hold
 *
 * Every other signed -2 message has one. Here the contract chain, the encrypted private key, the DH
 * public point and the eMAID are each digested separately, and a car that checked only the chain would
 * accept an encrypted key nobody signed for — which is to say it would install a private key of the
 * attacker's choosing under a certificate the operator really did issue.
 *
 * ## This is only half of what makes a contract usable
 *
 * The other half is that the unwrapped key belongs to the certificate it arrived with, and it lives in
 * [ContractProvisioning2.matches] rather than here because it needs the car's own private key and this
 * object deliberately needs nothing but the response.
 */
object Iso2ContractCheck {

    /**
     * What §7.9.2.4.2 asks the station to sign: the contract chain, the encrypted key, the DH point, and
     * the eMAID.
     */
    const val REQUIRED_REFERENCES = 4

    /**
     * The common fields of either response, or `null` for anything else. The two messages differ in
     * almost nothing but the update's trailing RetryCounter, which no verifier reads.
     */
    fun unpack(body: BodyBaseType?): Iso2ProvisioningPayload? = when (body) {

        is CertificateInstallationResType -> Iso2ProvisioningPayload(
            body.sAProvisioningCertificateChain, body.contractSignatureCertChain,
            body.contractSignatureEncryptedPrivateKey, body.dHpublickey, body.eMAID, body.responseCode)

        is CertificateUpdateResType -> Iso2ProvisioningPayload(
            body.sAProvisioningCertificateChain, body.contractSignatureCertChain,
            body.contractSignatureEncryptedPrivateKey, body.dHpublickey, body.eMAID, body.responseCode)

        else -> null
    }

    /**
     * Evaluates a provisioning response against the header signature it arrived with.
     *
     * No verify key is passed in — unlike every other check here — because the station sends its own: the
     * signature is made by the leaf of `SAProvisioningCertificateChain`, which travels in the message.
     * What makes that chain trustworthy is a separate question, and one the trust store answers.
     */
    fun evaluate(body: BodyBaseType?, headerSignature: SignatureType?): Iso2ContractVerdict {

        val payload = unpack(body)
        if (headerSignature == null || payload == null)
            return Iso2ContractVerdict(false, 0, false, false, "none")

        val references = headerSignature.signedInfo.reference.size

        // (1) the four digests, each over its own element's EXI fragment. A reference count other than
        //     four fails here rather than being read as "the ones present are fine".
        fun matches(id: String?, fragment: () -> ByteArray): Boolean {
            val reference = id?.let { i -> headerSignature.signedInfo.reference.firstOrNull { it.uRI == "#$i" } }
            return reference != null && V2GSignature.verifyReference(reference, fragment())
        }

        val digestOk =
            references == REQUIRED_REFERENCES &&
            matches(payload.contractChain.id) { Iso15118_2Codec.encodeFragment_ContractSignatureCertChain(payload.contractChain) } &&
            matches(payload.encryptedKey.id)  { Iso15118_2Codec.encodeFragment_ContractSignatureEncryptedPrivateKey(payload.encryptedKey) } &&
            matches(payload.dhPublicKey.id)   { Iso15118_2Codec.encodeFragment_DHpublickey(payload.dhPublicKey) } &&
            matches(payload.emaid.id)         { Iso15118_2Codec.encodeFragment_eMAID(payload.emaid) }

        // (2) the ECDSA signature over the SignedInfo, against the chain the station sent. Both grammars,
        //     and which one matched is reported for the reason Iso2TariffCheck reports it: "it verified"
        //     and "it verified the way the standard says" are different facts.
        val verifyKey = publicKeyOf(payload.provisioningChain.certificate)
            // An unparseable provisioning certificate leaves the signature unestablished rather than
            // failed, and the car must not install what it cannot check either way.
            ?: return Iso2ContractVerdict(true, references, digestOk, false, "none")

        val value = headerSignature.signatureValue.value

        if (V2GSignature.verify(headerSignature.signedInfo, value, verifyKey))
            return Iso2ContractVerdict(true, references, digestOk, true, "iso2-msgdef")

        if (XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(headerSignature.signedInfo), value, verifyKey))
            return Iso2ContractVerdict(true, references, digestOk, true, "xmldsig-standalone")

        return Iso2ContractVerdict(true, references, digestOk, false, "none")
    }
}

/**
 * The subject public key of a DER certificate, or `null` if it will not parse.
 *
 * Contract provisioning is the one exchange on either protocol where **the station sends the verify key
 * inside the message it is signing for** — the SA provisioning chain on -2, the CPS chain on -20. So the
 * verdict cannot be reached without reading a key out of a certificate, and both checks come here for it.
 */
internal fun publicKeyOf(der: ByteArray): PublicKey? = try {
    CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(der))
        .publicKey
} catch (_: GeneralSecurityException) {
    null
}
