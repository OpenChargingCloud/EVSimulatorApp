package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso20.common.CertificateInstallationRes
import cloud.charging.v2g.iso20.common.CommonMessagesCodec
import cloud.charging.v2g.iso20.common.SignatureType
import cloud.charging.v2g.iso20.common.V2GSignature

/**
 * An EVCC's verdict over a `CertificateInstallationRes`.
 *
 * @property signaturePresent The response header carried a Signature at all.
 * @property references How many References the SignedInfo carried. -20 signs one element here, so
 *   anything but one is a station signing something other than what it sent.
 * @property digestOk The reference named by the `SignedInstallationData`'s own Id carries the SHA-512 of
 *   that element's EXI fragment. Answerable without any key.
 * @property signatureOk The ECDSA signature over the SignedInfo verified against the leaf of the CPS
 *   chain the response itself carried.
 */
data class Iso20ContractVerdict(
    val signaturePresent: Boolean,
    val references: Int,
    val digestOk: Boolean,
    val signatureOk: Boolean,
)

/**
 * The -20 counterpart of [Iso2ContractCheck]: what an EVCC must make of the answer to its
 * `CertificateInstallationReq`.
 *
 * A port of C#'s `Iso20ContractCheck`, held to the same corpus, and existing for the same reason its
 * siblings do: the verdict never reaches the wire.
 *
 * ## One reference where -2 has four
 *
 * Not a weaker signature: -20 folds the contract chain, the curve, the DH point and the wrapped key into
 * a single `SignedInstallationData` element and signs that, so one digest covers everything -2 needs four
 * to cover. The eMAID is the one thing -2 signs and -20 does not — it has no eMAID field at all; the
 * identity travels inside the issued certificate.
 *
 * ## Matched by Id, not by position
 *
 * A single reference carrying the right digest under the wrong URI covers, read literally, an element
 * this message does not contain. The corpus case `iso20/install-wrong-uri` is exactly that, and a
 * verifier reading `reference[0]` passes it.
 *
 * ## ISO's grammar alone
 *
 * As with [Iso20PriceScheduleCheck], and for the same reason: the counterparty that signs under Josev's
 * standalone grammar does not implement -20 provisioning at all, so a second attempt here would be code
 * no counterparty exercises. The *request* direction is different — a foreign EVCC really can arrive
 * signing Josev-style — but that is the SECC's problem and this port has no SECC.
 */
object Iso20ContractCheck {

    /**
     * Evaluates a certificate-installation response against the header signature it arrived with.
     *
     * No verify key is passed in: the station sends its own CPS chain, and the leaf of that chain is what
     * signed. Whether the chain deserves trust is the trust store's question, not this one's.
     */
    fun evaluate(res: CertificateInstallationRes, headerSignature: SignatureType?): Iso20ContractVerdict {

        if (headerSignature == null)
            return Iso20ContractVerdict(false, 0, false, false)

        val references = headerSignature.signedInfo.reference.size
        val installData = res.signedInstallationData

        val reference = headerSignature.signedInfo.reference
            .firstOrNull { it.uRI == "#" + installData.id }

        val digestOk = reference != null &&
            V2GSignature.verifyReference(
                reference, CommonMessagesCodec.encodeFragment_SignedInstallationData(installData))

        val cpsKey = publicKeyOf(res.cPSCertificateChain.certificate)
        val signatureOk = cpsKey != null &&
            V2GSignature.verify(headerSignature.signedInfo, headerSignature.signatureValue.value, cpsKey)

        return Iso20ContractVerdict(true, references, digestOk, signatureOk)
    }
}
