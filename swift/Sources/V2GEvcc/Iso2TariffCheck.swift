import CryptoKit
import Foundation

import ExiIso2

/// The signature half of an EVCC's verdict over a SASchedule offer (§7.9.2.5).
public struct Iso2TariffSignatureVerdict: Equatable, Sendable {

    /// The header carried a Signature **and** at least one tuple carried a SalesTariff with an Id to
    /// reference. Neither alone is a signed offer.
    public let signaturePresent: Bool

    /// Every signed tariff has a matching Reference whose DigestValue equals the SHA-256 of that
    /// tariff's own EXI fragment. Answerable without any key.
    public let digestOk: Bool

    /// The ECDSA signature over the `SignedInfo` verified. `false` also means *not attempted* when no
    /// verify key was supplied — ``signatureGrammar`` is what tells the two apart when reporting.
    public let signatureOk: Bool

    /// Which grammar the signature matched under: `iso2-msgdef`, `xmldsig-standalone`, or `none` when
    /// it matched neither or was never attempted.
    public let signatureGrammar: String
}

/// §7.9.2.5, on its own so that something other than a live session can ask the question.
///
/// A port of C#'s `Iso2TariffCheck`, and held to the same corpus: `Tariff.signature.vectors.json`
/// carries whole `ChargeParameterDiscoveryRes` frames and the verdict each must produce. That corpus
/// exists because **the verdict never reaches the wire** — the EV checks the offer and tells the
/// station nothing — so no recorded session trace can pin it. A replayed signed offer proves this code
/// can *parse* one; only the corpus proves it *judges* one correctly, which for a verifier is the
/// entire question.
///
/// ## The two halves answer different questions
///
/// The digest says *these are the tariffs that were signed*; the signature says *and the Mobility
/// Operator signed them*. A verifier that checks only the signature accepts an offer whose tariffs were
/// edited after signing — the signature covers the `SignedInfo`, not the tariffs — and one that checks
/// only the digests accepts anything a station cares to hash. The corpus carries a case for each, so
/// collapsing them fails there rather than at a charger.
public enum Iso2TariffCheck {

    /// Evaluates a received offer. `verifyKey` may be `nil`: the digest half is still answered, and the
    /// signature half honestly reports "not established" rather than "failed".
    public static func evaluate(offer: SAScheduleListType?,
                                headerSignature: SignatureType?,
                                verifyKey: P256.Signing.PublicKey?) -> Iso2TariffSignatureVerdict {

        let signedTariffs = (offer?.sAScheduleTuple ?? [])
            .compactMap { $0.salesTariff }
            .filter { $0.id != nil }

        guard let signature = headerSignature, !signedTariffs.isEmpty else {
            return Iso2TariffSignatureVerdict(signaturePresent: false, digestOk: false,
                                              signatureOk: false, signatureGrammar: "none")
        }

        // (1) one reference per SalesTariff Id, each over that tariff's own EXI fragment.
        var digestOk = true
        for tariff in signedTariffs {
            guard let reference = signature.signedInfo.reference.first(where: { $0.uRI == "#" + (tariff.id ?? "") })
            else { digestOk = false; continue }

            let fragment = Iso15118_2Codec.encodeFragment_SalesTariff(tariff)
            digestOk = V2GSignature.verifyReference(reference, fragment: fragment) && digestOk
        }

        // (2) the ECDSA signature over the SignedInfo — ISO's grammar first, then Josev's. Reporting
        //     which one matched is the point: "it verified" and "it verified the way the standard says"
        //     are different facts, and only one of them is a conformance statement.
        guard let key = verifyKey else {
            return Iso2TariffSignatureVerdict(signaturePresent: true, digestOk: digestOk,
                                              signatureOk: false, signatureGrammar: "none")
        }

        let value = signature.signatureValue.value

        if V2GSignature.verify(signature.signedInfo, signature: value, with: key) {
            return Iso2TariffSignatureVerdict(signaturePresent: true, digestOk: digestOk,
                                              signatureOk: true, signatureGrammar: "iso2-msgdef")
        }

        if XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(signature.signedInfo), value, key) {
            return Iso2TariffSignatureVerdict(signaturePresent: true, digestOk: digestOk,
                                              signatureOk: true, signatureGrammar: "xmldsig-standalone")
        }

        return Iso2TariffSignatureVerdict(signaturePresent: true, digestOk: digestOk,
                                          signatureOk: false, signatureGrammar: "none")
    }
}
