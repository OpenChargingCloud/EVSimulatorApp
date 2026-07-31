import ExiIso20DC
import V2GDispatch

/// The DC hooks: charge-parameter discovery, CableCheck + PreCharge, one charge-loop iteration, and
/// WeldingDetection.
///
/// A port of the C# `Evcc20Dc`. Note that `Processing` here is the **DC module's** enum, not
/// CommonMessages' — the three -20 sets each carry their own copy, which is exactly the duplication
/// ``SessionContext`` exists to manage on the header side.
public class Evcc20Dc: Evcc20Base {

    public override var energyMode: PowerMode { .dc }

    public override func runChargeParameterDiscovery() throws {

        let request = DC_ChargeParameterDiscoveryReq(
            header: sessionCtx.toDcHeader(),
            dC_CPDReqEnergyTransferMode: DC_CPDReqEnergyTransferModeType(
                eVMaximumChargePower:   Self.rat(5_000, 1),
                eVMinimumChargePower:   Self.rat(0),
                eVMaximumChargeCurrent: Self.rat(200),
                eVMinimumChargeCurrent: Self.rat(0),
                eVMaximumVoltage:       Self.rat(500),
                eVMinimumVoltage:       Self.rat(50),
                targetSOC: 80))

        let (set, message) = try exchangeRaw(.iso20DC, DCCodec.encode(request))
        let _: DC_ChargeParameterDiscoveryRes = try expect(set, message, .iso20DC)
    }

    public override func runPreChargeSequence() throws {

        while true {
            let (set, message) = try exchangeRaw(.iso20DC,
                DCCodec.encode(DC_CableCheckReq(header: sessionCtx.toDcHeader())))
            let res: DC_CableCheckRes = try expect(set, message, .iso20DC)
            if res.eVSEProcessing == ExiIso20DC.Processing.Finished { break }
            pollDelay(Self.pollIntervalMs)
        }

        let preCharge = DC_PreChargeReq(header: sessionCtx.toDcHeader(),
                                        eVProcessing: ExiIso20DC.Processing.Finished,
                                        eVPresentVoltage: Self.rat(0),
                                        eVTargetVoltage: Self.rat(400))
        let (set, message) = try exchangeRaw(.iso20DC, DCCodec.encode(preCharge))
        let _: DC_PreChargeRes = try expect(set, message, .iso20DC)
    }

    public override func runChargeLoopIteration() throws {

        let request = DC_ChargeLoopReq(
            header: sessionCtx.toDcHeader(),
            meterInfoRequested: false,
            eVPresentVoltage: Self.rat(400),
            cLReqControlMode: Scheduled_DC_CLReqControlModeType(
                eVTargetCurrent: Self.rat(120),
                eVTargetVoltage: Self.rat(400)))

        let (set, message) = try exchangeRaw(.iso20DC, DCCodec.encode(request))
        let _: DC_ChargeLoopRes = try expect(set, message, .iso20DC)
    }

    public override func runPostChargeSequence() throws {
        let request = DC_WeldingDetectionReq(header: sessionCtx.toDcHeader(),
                                             eVProcessing: ExiIso20DC.Processing.Finished)
        let (set, message) = try exchangeRaw(.iso20DC, DCCodec.encode(request))
        let _: DC_WeldingDetectionRes = try expect(set, message, .iso20DC)
    }

    /// (value, exponent) as in the C# helper; the generated type takes (exponent, value).
    private static func rat(_ value: Int16, _ exponent: Int8 = 0) -> ExiIso20DC.RationalNumberType {
        ExiIso20DC.RationalNumberType(exponent: exponent, value: value)
    }
}
