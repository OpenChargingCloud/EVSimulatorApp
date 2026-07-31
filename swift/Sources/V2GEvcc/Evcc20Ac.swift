import ExiIso20AC
import V2GDispatch

/// The AC hooks: charge-parameter discovery and one charge-loop iteration. No pre- or post-charge
/// sequence — a cable check, a pre-charge and a welding detection are DC-only, and an AC session
/// that ran one would be wrong rather than merely unusual.
///
/// A port of the C# `Evcc20Ac`. Every type below is module-qualified, because `RationalNumberType`
/// exists identically in all three -20 modules and Swift will not guess which one is meant.
public final class Evcc20Ac: Evcc20Base {

    public override var energyMode: PowerMode { .ac }

    public override func runChargeParameterDiscovery() throws {

        let request = AC_ChargeParameterDiscoveryReq(
            header: sessionCtx.toAcHeader(),
            aC_CPDReqEnergyTransferMode: AC_CPDReqEnergyTransferModeType(
                eVMaximumChargePower: Self.rat(2_200, 1),
                eVMinimumChargePower: Self.rat(0)))

        let (set, message) = try exchangeRaw(.iso20AC, ACCodec.encode(request))
        let _: AC_ChargeParameterDiscoveryRes = try expect(set, message, .iso20AC)
    }

    public override func runChargeLoopIteration() throws {

        let request = AC_ChargeLoopReq(
            header: sessionCtx.toAcHeader(),
            meterInfoRequested: false,
            cLReqControlMode: Scheduled_AC_CLReqControlModeType(
                eVPresentActivePower: Self.rat(2_200, 1)))

        let (set, message) = try exchangeRaw(.iso20AC, ACCodec.encode(request))
        let _: AC_ChargeLoopRes = try expect(set, message, .iso20AC)
    }

    /// (value, exponent) as in the C# helper; the generated type takes (exponent, value).
    private static func rat(_ value: Int16, _ exponent: Int8 = 0) -> ExiIso20AC.RationalNumberType {
        ExiIso20AC.RationalNumberType(exponent: exponent, value: value)
    }
}
