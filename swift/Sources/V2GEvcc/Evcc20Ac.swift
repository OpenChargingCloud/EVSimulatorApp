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

        // Asking in kind, the mirror of [V2G20-1600] — see ``Evcc20Dc`` for the same split and why.
        let controlMode: CLReqControlModeType = preferDynamicControlMode
            ? Dynamic_AC_CLReqControlModeType(
                  departureTime:          departureTime,
                  eVTargetEnergyRequest:  Self.rat(30, 3),    // 30 kWh
                  eVMaximumEnergyRequest: Self.rat(60, 3),    // 60 kWh
                  eVMinimumEnergyRequest: Self.rat(10, 3),    // 10 kWh
                  eVMaximumChargePower:   Self.rat(11, 3),    // 11 kW
                  eVMinimumChargePower:   Self.rat(1, 3),
                  eVPresentActivePower:   Self.presentActivePower,
                  eVPresentReactivePower: Self.rat(0))
            : Scheduled_AC_CLReqControlModeType(
                  eVPresentActivePower: Self.presentActivePower)

        let request = AC_ChargeLoopReq(
            header: sessionCtx.toAcHeader(),
            meterInfoRequested: false,
            cLReqControlMode: controlMode)

        let (set, message) = try exchangeRaw(.iso20AC, ACCodec.encode(request))
        let response: AC_ChargeLoopRes = try expect(set, message, .iso20AC)

        // [V2G20-1477]: the station asks for a service renegotiation through the otherwise
        // absent EVSEStatus. The base acts on it once this iteration is finished and the
        // contactor is open — it cannot see this type, which is why the loop reports it.
        noteRenegotiationRequest(response.eVSEStatus?.eVSENotification == .ServiceRenegotiation)

        // The one place in this project where the EV's own inlet power is a field on the wire: -20 AC
        // has EVPresentActivePower in the request, so the vehicle's view needs no deriving and
        // nothing borrowed from the station.
        meter.sample(Self.presentActivePowerW)
    }

    /// The EV's present active power, 22 kW. One constant rather than the same literal in two
    /// control-mode branches and again at the meter: those three drifting apart would mean the
    /// vehicle's counter no longer counted what the vehicle said.
    private static var presentActivePower: ExiIso20AC.RationalNumberType { rat(2_200, 1) }
    private static let presentActivePowerW: Double = 22_000

    /// (value, exponent) as in the C# helper; the generated type takes (exponent, value).
    private static func rat(_ value: Int16, _ exponent: Int8 = 0) -> ExiIso20AC.RationalNumberType {
        ExiIso20AC.RationalNumberType(exponent: exponent, value: value)
    }
}
