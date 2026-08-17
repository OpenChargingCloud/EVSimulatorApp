import CryptoKit
import Foundation

import ExiIso20Common

/// What an EVCC makes of a signed `AbsolutePriceSchedule` in a `ScheduleExchangeRes`.
public struct Iso20TariffResult: Equatable, Sendable {

    /// The response header carried a Signature. There *was* a price schedule to check — a response
    /// with none produces no result at all, not a result with three falses.
    public let signaturePresent: Bool

    /// The Reference's DigestValue equals the SHA-512 of the schedule's own EXI fragment. Answerable
    /// without any key, and the half that catches a schedule edited after signing.
    public let digestOk: Bool

    /// The ECDSA-P521/SHA-512 signature over the `SignedInfo` verified. `false` also means *not
    /// attempted* when no verify key was supplied — and unlike the -2 result there is no grammar
    /// field to tell those apart, so a caller that needs the distinction has to know whether it
    /// passed a key.
    public let signatureOk: Bool
}

/// The -20 counterpart of ``Iso2TariffCheck``, and a port of C#'s `Iso20PriceScheduleCheck`.
///
/// Held to `PriceSchedule.signature.vectors.json`, which exists because the verdict never reaches the
/// wire: the EV checks the schedule and tells the station nothing, so a recorded session pins the
/// bytes and never the conclusion.
///
/// ## Two control modes, one check
///
/// Scheduled mode hangs the schedule off a schedule tuple's ChargingSchedule; Dynamic mode has no
/// tuples and carries one directly on the control mode. A verifier that looks in only one place
/// reports *unsigned* for half the sessions in the field — and an unsigned offer is exactly what an
/// honest station that does not sign looks like, so the mistake never shows from the outside. The
/// corpus carries the same schedule in both positions for that reason.
///
/// ## One reference, and no second grammar
///
/// Unlike ``Iso2TariffCheck`` this attempts ISO's grammar alone, mirroring C#. The counterparty that
/// signs under Josev's standalone grammar emits no price schedule at all, so a second attempt would be
/// code no counterparty exercises. Written down because the asymmetry otherwise reads as an oversight.
public enum Iso20PriceScheduleCheck {

    /// The signed price schedule a response carries, from whichever control mode holds it.
    public static func schedule(in res: ScheduleExchangeRes) -> AbsolutePriceScheduleType? {
        if let dynamic = res.dynamic_SEResControlMode?.absolutePriceSchedule, dynamic.id != nil {
            return dynamic
        }
        return res.scheduled_SEResControlMode?.scheduleTuple
            .compactMap { $0.chargingSchedule.absolutePriceSchedule }
            .first { $0.id != nil }
    }

    /// Evaluates a response. `nil` back means the offer carried no signed schedule at all — which is
    /// not a failure and must not be reported as one: most stations never sign, and Josev's SECC emits
    /// no `AbsolutePriceSchedule` whatsoever.
    ///
    /// - Parameter verifyKey: may be `nil`; the digest half is still answered.
    public static func evaluate(_ res: ScheduleExchangeRes,
                                headerSignature: SignatureType?,
                                verifyKey: P521.Signing.PublicKey?) -> Iso20TariffResult? {

        guard let priceSchedule = schedule(in: res) else { return nil }

        guard let signature = headerSignature else {
            return Iso20TariffResult(signaturePresent: false, digestOk: false, signatureOk: false)
        }

        // One reference, matched by the schedule's own Id. A signature that references something else
        // covers something else, however well its ECDSA verifies.
        let digestOk: Bool
        if let reference = signature.signedInfo.reference.first(where: { $0.uRI == "#" + (priceSchedule.id ?? "") }) {
            let fragment = CommonMessagesCodec.encodeFragment_AbsolutePriceSchedule(priceSchedule)
            digestOk = V2GSignature.verifyReference(reference, fragment: fragment)
        } else {
            digestOk = false
        }

        guard let key = verifyKey else {
            return Iso20TariffResult(signaturePresent: true, digestOk: digestOk, signatureOk: false)
        }

        // `verify` throws when the SignedInfo declares an algorithm other than #ecdsa-sha512 — Ed448,
        // say. C# has no such guard and simply fails the ECDSA check, so a mismatch lands on `false`
        // either way and the ports agree with it. It is a real limit of this three-field result: it
        // cannot say "signed with something I do not implement" apart from "signed badly", and only
        // the -2 result's grammar field can.
        let signatureOk = (try? V2GSignature.verify(signature.signedInfo,
                                                    signature: signature.signatureValue.value,
                                                    with: key)) ?? false

        return Iso20TariffResult(signaturePresent: true, digestOk: digestOk, signatureOk: signatureOk)
    }
}
