package cloud.charging.v2g.evcc

import java.security.PublicKey

import cloud.charging.v2g.iso20.common.AbsolutePriceScheduleType
import cloud.charging.v2g.iso20.common.CommonMessagesCodec
import cloud.charging.v2g.iso20.common.ScheduleExchangeRes
import cloud.charging.v2g.iso20.common.SignatureType
import cloud.charging.v2g.iso20.common.V2GSignature

/**
 * What an EVCC makes of a signed `AbsolutePriceSchedule` in a `ScheduleExchangeRes`.
 *
 * @property signaturePresent The response header carried a Signature. There *was* a price schedule to
 *   check — a response with none produces no result at all, not a result with three falses.
 * @property digestOk The Reference's DigestValue equals the SHA-512 of the schedule's own EXI
 *   fragment. Answerable without any key, and the half that catches a schedule edited after signing.
 * @property signatureOk The ECDSA-P521/SHA-512 signature over the SignedInfo verified. `false` also
 *   means *not attempted* when no verify key was supplied — and unlike the -2 result there is no
 *   grammar field to tell those apart, so a caller that needs the distinction has to know whether it
 *   passed a key.
 */
data class Iso20TariffResult(
    val signaturePresent: Boolean,
    val digestOk: Boolean,
    val signatureOk: Boolean,
)

/**
 * The -20 counterpart of [Iso2TariffCheck], and a port of C#'s `Iso20PriceScheduleCheck`.
 *
 * Held to `PriceSchedule.signature.vectors.json`, which exists because the verdict never reaches the
 * wire: the EV checks the schedule and tells the station nothing, so a recorded session pins the bytes
 * and never the conclusion.
 *
 * ## Two control modes, one check
 *
 * Scheduled mode hangs the schedule off a schedule tuple's ChargingSchedule; Dynamic mode has no tuples
 * and carries one directly on the control mode. A verifier that looks in only one place reports
 * *unsigned* for half the sessions in the field — and an unsigned offer is exactly what an honest
 * station that does not sign looks like, so the mistake never shows from the outside. The corpus
 * carries the same schedule in both positions for that reason.
 *
 * ## One reference, and no second grammar
 *
 * Unlike [Iso2TariffCheck] this attempts ISO's grammar alone, mirroring C#. The counterparty that signs
 * under Josev's standalone grammar emits no price schedule at all, so a second attempt would be code no
 * counterparty exercises. Written down because the asymmetry otherwise reads as an oversight.
 */
object Iso20PriceScheduleCheck {

    /** The signed price schedule a response carries, from whichever control mode holds it. */
    fun scheduleIn(res: ScheduleExchangeRes): AbsolutePriceScheduleType? {

        val dynamic = res.dynamic_SEResControlMode?.absolutePriceSchedule
        if (dynamic?.id != null) return dynamic

        return res.scheduled_SEResControlMode?.scheduleTuple
            ?.mapNotNull { it.chargingSchedule.absolutePriceSchedule }
            ?.firstOrNull { it.id != null }
    }

    /**
     * Evaluates a response. `null` back means the offer carried no signed schedule at all — which is
     * not a failure and must not be reported as one: most stations never sign, and Josev's SECC emits
     * no `AbsolutePriceSchedule` whatsoever.
     *
     * [verifyKey] may be `null`; the digest half is still answered.
     */
    fun evaluate(
        res: ScheduleExchangeRes,
        headerSignature: SignatureType?,
        verifyKey: PublicKey?,
    ): Iso20TariffResult? {

        val priceSchedule = scheduleIn(res) ?: return null

        if (headerSignature == null)
            return Iso20TariffResult(signaturePresent = false, digestOk = false, signatureOk = false)

        // One reference, matched by the schedule's own Id. A signature that references something else
        // covers something else, however well its ECDSA verifies.
        val reference = headerSignature.signedInfo.reference
            .firstOrNull { it.uRI == "#" + priceSchedule.id }

        val digestOk = reference != null &&
            V2GSignature.verifyReference(
                reference, CommonMessagesCodec.encodeFragment_AbsolutePriceSchedule(priceSchedule))

        if (verifyKey == null)
            return Iso20TariffResult(signaturePresent = true, digestOk = digestOk, signatureOk = false)

        val signatureOk = V2GSignature.verify(
            headerSignature.signedInfo, headerSignature.signatureValue.value, verifyKey)

        return Iso20TariffResult(signaturePresent = true, digestOk = digestOk, signatureOk = signatureOk)
    }
}
