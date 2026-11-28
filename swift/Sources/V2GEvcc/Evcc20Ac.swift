import Foundation
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

        // Asking in kind on the direction axis, see ``Evcc20Dc``. AC's BPT subtype is the charge-only
        // one plus a discharge power per phase; this EV is single-phase and symmetric, so L2/L3 stay
        // nil on both halves and the discharge envelope mirrors the charge one.
        let transferMode: AC_CPDReqEnergyTransferModeType = bidirectionalService
            ? BPT_AC_CPDReqEnergyTransferModeType(
                  eVMaximumChargePower:    Self.rat(2_200, 1),
                  eVMinimumChargePower:    Self.rat(0),
                  eVMaximumDischargePower: Self.rat(2_200, 1),
                  eVMinimumDischargePower: Self.rat(0))
            : AC_CPDReqEnergyTransferModeType(
                  eVMaximumChargePower: Self.rat(2_200, 1),
                  eVMinimumChargePower: Self.rat(0))

        let request = AC_ChargeParameterDiscoveryReq(
            header: sessionCtx.toAcHeader(),
            aC_CPDReqEnergyTransferMode: transferMode)

        let (set, message) = try exchangeRaw(.iso20AC, ACCodec.encode(request))
        let _: AC_ChargeParameterDiscoveryRes = try expect(set, message, .iso20AC)
    }

    public override func runChargeLoopIteration() throws {

        // Asking in kind, the mirror of [V2G20-1600], on both axes — see ``Evcc20Dc`` for the same
        // split and why.
        let controlMode: CLReqControlModeType
        switch (preferDynamicControlMode, bidirectionalService) {
        case (true, true):
            // The Dynamic discharge pair is *mandatory* in the BPT subtype: a station steering a
            // bidirectional session needs to know how far either way.
            controlMode = BPT_Dynamic_AC_CLReqControlModeType(
                departureTime:           departureTime,
                eVTargetEnergyRequest:   Self.rat(30, 3),    // 30 kWh
                eVMaximumEnergyRequest:  Self.rat(60, 3),    // 60 kWh
                eVMinimumEnergyRequest:  Self.rat(10, 3),    // 10 kWh
                eVMaximumChargePower:    Self.rat(11, 3),    // 11 kW
                eVMinimumChargePower:    Self.rat(1, 3),
                eVPresentActivePower:    presentActivePower,
                eVPresentReactivePower:  Self.rat(0),
                eVMaximumDischargePower: Self.rat(11, 3),    // the charge request mirrored
                eVMinimumDischargePower: Self.rat(1, 3))
        case (true, false):
            controlMode = Dynamic_AC_CLReqControlModeType(
                departureTime:          departureTime,
                eVTargetEnergyRequest:  Self.rat(30, 3),    // 30 kWh
                eVMaximumEnergyRequest: Self.rat(60, 3),    // 60 kWh
                eVMinimumEnergyRequest: Self.rat(10, 3),    // 10 kWh
                eVMaximumChargePower:   Self.rat(11, 3),    // 11 kW
                eVMinimumChargePower:   Self.rat(1, 3),
                eVPresentActivePower:   presentActivePower,
                eVPresentReactivePower: Self.rat(0))
        case (false, true):
            // Scheduled's limits are all optional, so these two arms differ only in the discharge
            // envelope, which is the whole of what makes the request bidirectional, hence stated.
            controlMode = BPT_Scheduled_AC_CLReqControlModeType(
                eVPresentActivePower:    presentActivePower,
                eVMaximumDischargePower: Self.rat(2_200, 1),  // 22 kW: what the car can give back
                eVMinimumDischargePower: Self.rat(0))
        case (false, false):
            controlMode = Scheduled_AC_CLReqControlModeType(
                eVPresentActivePower: presentActivePower)
        }

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

        // The station's ask for the next iteration, read before this one is counted: it changes
        // nothing about what this request already said.
        let sent = presentActivePower
        dischargeTarget = dischargeTargetOf(response)

        // The one place in this project where the EV's own inlet power is a field on the wire: -20 AC
        // has EVPresentActivePower in the request, so the vehicle's view needs no deriving and
        // nothing borrowed from the station. Read back off the rational it just sent.
        meter.sample(Self.amount(sent))

        // [V2G20-1083]: the station may ask the car to sign what this response carried. After the
        // sample, so the station's reading is paired with the car's count of the same iteration.
        try confirmMeteringIfAsked(response)
    }

    /// What the station last asked this vehicle to give back, as the `EVSETargetActivePower` it sent,
    /// or nil while it is not asking. A port of the C# `Evcc20Ac._dischargeTarget`.
    ///
    /// [V2G20-1825]: the vehicle adjusts to the setpoints the station sends. A negative target in a
    /// bidirectional session is the station asking for energy back, and this vehicle reports exactly
    /// that figure as its `EVPresentActivePower` from the next iteration on, the rational passed
    /// through as received. A positive target is left as it always was: our station's Dynamic target
    /// is its 22 kW offer, and the vehicle draws what it wants inside it.
    private var dischargeTarget: ExiIso20AC.RationalNumberType?

    /// The power this request reports: the station's discharge ask when there is one, else 22 kW.
    private var presentActivePower: ExiIso20AC.RationalNumberType {
        dischargeTarget ?? Self.presentActivePowerDefault
    }

    /// The discharge the station asks for in `response`, held inside this vehicle's own 22 kW; nil in
    /// a charge-only session, in Scheduled mode, or for a target ≥ 0.
    private func dischargeTargetOf(_ response: AC_ChargeLoopRes) -> ExiIso20AC.RationalNumberType? {
        guard bidirectionalService,
              let dynamic = response.cLResControlMode as? Dynamic_AC_CLResControlModeType else { return nil }

        let target = Self.amount(dynamic.eVSETargetActivePower)
        if target >= 0 { return nil }
        if target >= -Self.presentActivePowerW { return dynamic.eVSETargetActivePower }
        return Self.rat(-22_000)   // as C#'s Watts(-22 000)
    }

    /// A RationalNumber as a plain number: value x 10^exponent.
    private static func amount(_ v: ExiIso20AC.RationalNumberType) -> Double {
        Double(v.value) * pow(10, Double(v.exponent))
    }

    /// The EV's present active power, 22 kW. One constant rather than the same literal in two
    /// control-mode branches and again at the meter: those three drifting apart would mean the
    /// vehicle's counter no longer counted what the vehicle said.
    private static var presentActivePowerDefault: ExiIso20AC.RationalNumberType { rat(2_200, 1) }
    private static let presentActivePowerW: Double = 22_000

    /// (value, exponent) as in the C# helper; the generated type takes (exponent, value).
    private static func rat(_ value: Int16, _ exponent: Int8 = 0) -> ExiIso20AC.RationalNumberType {
        ExiIso20AC.RationalNumberType(exponent: exponent, value: value)
    }
}
