package cloud.charging.v2g.evcc

import java.security.PublicKey

import cloud.charging.v2g.iso2.Iso15118_2Codec
import cloud.charging.v2g.iso2.SAScheduleListType
import cloud.charging.v2g.iso2.SignatureType
import cloud.charging.v2g.iso2.V2GSignature

/**
 * The signature half of an EVCC's verdict over a SASchedule offer (§7.9.2.5).
 *
 * @property signaturePresent The header carried a Signature **and** at least one tuple carried a
 *   SalesTariff with an Id to reference. Neither alone is a signed offer.
 * @property digestOk Every signed tariff has a matching Reference whose DigestValue equals the
 *   SHA-256 of that tariff's own EXI fragment. Answerable without any key.
 * @property signatureOk The ECDSA signature over the SignedInfo verified. `false` also means *not
 *   attempted* when no verify key was supplied — [signatureGrammar] is what tells the two apart.
 * @property signatureGrammar Which grammar the signature matched under: `iso2-msgdef`,
 *   `xmldsig-standalone`, or `none` when it matched neither or was never attempted.
 */
data class Iso2TariffSignatureVerdict(
    val signaturePresent: Boolean,
    val digestOk: Boolean,
    val signatureOk: Boolean,
    val signatureGrammar: String,
)

/**
 * §7.9.2.5, on its own so that something other than a live session can ask the question.
 *
 * A port of C#'s `Iso2TariffCheck`, and held to the same corpus: `Tariff.signature.vectors.json`
 * carries whole `ChargeParameterDiscoveryRes` frames and the verdict each must produce. That corpus
 * exists because **the verdict never reaches the wire** — the EV checks the offer and tells the
 * station nothing — so no recorded session trace can pin it. A replayed signed offer proves this code
 * can *parse* one; only the corpus proves it *judges* one correctly, which for a verifier is the whole
 * question.
 *
 * ## The two halves answer different questions
 *
 * The digest says *these are the tariffs that were signed*; the signature says *and the Mobility
 * Operator signed them*. A verifier that checks only the signature accepts an offer whose tariffs were
 * edited after signing — the signature covers the SignedInfo, not the tariffs — and one that checks
 * only the digests accepts anything a station cares to hash. The corpus carries a case for each, so
 * collapsing them fails there rather than at a charger.
 */
object Iso2TariffCheck {

    /**
     * Evaluates a received offer. [verifyKey] may be `null`: the digest half is still answered, and the
     * signature half honestly reports "not established" rather than "failed".
     */
    fun evaluate(
        offer: SAScheduleListType?,
        headerSignature: SignatureType?,
        verifyKey: PublicKey?,
    ): Iso2TariffSignatureVerdict {

        val signedTariffs = (offer?.sAScheduleTuple ?: emptyList())
            .mapNotNull { it.salesTariff }
            .filter { it.id != null }

        if (headerSignature == null || signedTariffs.isEmpty())
            return Iso2TariffSignatureVerdict(false, false, false, "none")

        // (1) one reference per SalesTariff Id, each over that tariff's own EXI fragment.
        var digestOk = true
        for (tariff in signedTariffs) {
            val reference = headerSignature.signedInfo.reference.firstOrNull { it.uRI == "#" + tariff.id }
            digestOk = if (reference == null) false
                       else V2GSignature.verifyReference(
                                reference, Iso15118_2Codec.encodeFragment_SalesTariff(tariff)) && digestOk
        }

        // (2) the ECDSA signature over the SignedInfo — ISO's grammar first, then Josev's. Reporting
        //     which one matched is the point: "it verified" and "it verified the way the standard says"
        //     are different facts, and only one of them is a conformance statement.
        if (verifyKey == null)
            return Iso2TariffSignatureVerdict(true, digestOk, false, "none")

        val value = headerSignature.signatureValue.value

        if (V2GSignature.verify(headerSignature.signedInfo, value, verifyKey))
            return Iso2TariffSignatureVerdict(true, digestOk, true, "iso2-msgdef")

        if (XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(headerSignature.signedInfo), value, verifyKey))
            return Iso2TariffSignatureVerdict(true, digestOk, true, "xmldsig-standalone")

        return Iso2TariffSignatureVerdict(true, digestOk, false, "none")
    }
}
