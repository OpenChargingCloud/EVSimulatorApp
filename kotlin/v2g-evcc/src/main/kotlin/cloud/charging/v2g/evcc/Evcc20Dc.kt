package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso20.dc.BPT_DC_CPDReqEnergyTransferModeType
import cloud.charging.v2g.iso20.dc.BPT_Dynamic_DC_CLReqControlModeType
import cloud.charging.v2g.iso20.dc.BPT_Scheduled_DC_CLReqControlModeType
import cloud.charging.v2g.iso20.dc.DCCodec
import cloud.charging.v2g.iso20.dc.DC_CPDReqEnergyTransferModeType
import cloud.charging.v2g.iso20.dc.DC_CableCheckReq
import cloud.charging.v2g.iso20.dc.DC_CableCheckRes
import cloud.charging.v2g.iso20.dc.DC_ChargeLoopReq
import cloud.charging.v2g.iso20.dc.DC_ChargeLoopRes
import cloud.charging.v2g.iso20.dc.DC_ChargeParameterDiscoveryReq
import cloud.charging.v2g.iso20.dc.DC_ChargeParameterDiscoveryRes
import cloud.charging.v2g.iso20.dc.DC_PreChargeReq
import cloud.charging.v2g.iso20.dc.DC_PreChargeRes
import cloud.charging.v2g.iso20.dc.DC_WeldingDetectionReq
import cloud.charging.v2g.iso20.dc.DC_WeldingDetectionRes
import cloud.charging.v2g.iso20.dc.Dynamic_DC_CLReqControlModeType
import cloud.charging.v2g.iso20.dc.Processing
import cloud.charging.v2g.iso20.dc.RationalNumberType
import cloud.charging.v2g.iso20.dc.Scheduled_DC_CLReqControlModeType
import cloud.charging.v2g.tp.MessageSet

/**
 * The DC hooks: charge-parameter discovery, CableCheck + PreCharge, one charge-loop iteration, and
 * WeldingDetection.
 *
 * A port of the C# `Evcc20Dc`. Note that `Processing` here is the **DC module's** enum, not
 * CommonMessages' — the three -20 sets each carry their own copy, which is exactly the duplication
 * [SessionContext] exists to manage on the header side.
 */
open class Evcc20Dc(
    stream: V2GTPStream,
    clock: () -> ULong,
    pollDelay: (Long) -> Unit = { Thread.sleep(it) },
) : Evcc20Base(stream, clock, pollDelay) {

    override val energyMode get() = PowerMode.Dc

    override fun runChargeParameterDiscovery() {

        // Asking in kind on the direction axis (see [bidirectionalService]): under a BPT service the
        // EV declares what it can take *and* what it can give back, in the BPT_ subtype. The discharge
        // envelope mirrors the charge one, as in the C# EVCC: this vehicle is symmetric.
        val transferMode =
            if (bidirectionalService)
                BPT_DC_CPDReqEnergyTransferModeType(
                    eVMaximumChargePower      = rat(5_000, 1),
                    eVMinimumChargePower      = rat(0),
                    eVMaximumChargeCurrent    = rat(200),
                    eVMinimumChargeCurrent    = rat(0),
                    eVMaximumVoltage          = rat(500),
                    eVMinimumVoltage          = rat(50),
                    targetSOC                 = 80,
                    eVMaximumDischargePower   = discharging(rat(5_000, 1)),
                    eVMinimumDischargePower   = discharging(rat(0)),
                    eVMaximumDischargeCurrent = discharging(rat(200)),
                    eVMinimumDischargeCurrent = discharging(rat(0)))
            else
                DC_CPDReqEnergyTransferModeType(
                    eVMaximumChargePower   = rat(5_000, 1),
                    eVMinimumChargePower   = rat(0),
                    eVMaximumChargeCurrent = rat(200),
                    eVMinimumChargeCurrent = rat(0),
                    eVMaximumVoltage       = rat(500),
                    eVMinimumVoltage       = rat(50),
                    targetSOC              = 80)

        val request = DC_ChargeParameterDiscoveryReq(sessionCtx.toDcHeader(), transferMode)

        val (set, message) = exchangeRaw(MessageSet.Iso20DC, DCCodec.encode(request))
        expect<DC_ChargeParameterDiscoveryRes>(set, message, MessageSet.Iso20DC)
    }

    override fun runPreChargeSequence() {

        val cableGuard = OngoingGuard("DC_CableCheck", ongoingTimeoutMillis)
        while (true) {
            val (set, message) = exchangeRaw(MessageSet.Iso20DC,
                DCCodec.encode(DC_CableCheckReq(sessionCtx.toDcHeader())))
            val res = expect<DC_CableCheckRes>(set, message, MessageSet.Iso20DC)
            if (res.eVSEProcessing == Processing.Finished) break
            cableGuard.tick()
            pollDelay(POLL_INTERVAL_MS)
        }

        val preCharge = DC_PreChargeReq(sessionCtx.toDcHeader(), Processing.Finished,
                                        eVPresentVoltage = rat(0), eVTargetVoltage = rat(400))
        val (set, message) = exchangeRaw(MessageSet.Iso20DC, DCCodec.encode(preCharge))
        expect<DC_PreChargeRes>(set, message, MessageSet.Iso20DC)
    }

    override fun runChargeLoopIteration() {

        // Asking in kind, the mirror of [V2G20-1600], on both axes at once: the request's control mode
        // must be the one the session negotiated (Dynamic states what the battery needs and what the
        // car can take, and lets the station choose the setpoint; Scheduled names the setpoint
        // itself), and its direction must be the one the selected service carries.
        val controlMode =
            when {
                // The Dynamic discharge triple is *mandatory* in the BPT subtype: a station asked to
                // steer a bidirectional session cannot do it without knowing how far either way.
                preferDynamicControlMode && bidirectionalService ->
                    BPT_Dynamic_DC_CLReqControlModeType(
                        departureTime             = departureTime,
                        eVTargetEnergyRequest     = rat(30, 3),    // 30 kWh
                        eVMaximumEnergyRequest    = rat(60, 3),    // 60 kWh
                        eVMinimumEnergyRequest    = rat(10, 3),    // 10 kWh
                        eVMaximumChargePower      = rat(50, 3),    // 50 kW
                        eVMinimumChargePower      = rat(1,  3),    //  1 kW
                        eVMaximumChargeCurrent    = rat(125),
                        eVMaximumVoltage          = rat(500),
                        eVMinimumVoltage          = rat(200),
                        eVMaximumDischargePower   = discharging(rat(50, 3)),    // the charge request mirrored
                        eVMinimumDischargePower   = discharging(rat(1,  3)),
                        eVMaximumDischargeCurrent = discharging(rat(125)),
                        eVMaximumV2XEnergyRequest = null,
                        eVMinimumV2XEnergyRequest = null)
                preferDynamicControlMode ->
                    Dynamic_DC_CLReqControlModeType(
                        departureTime          = departureTime,
                        eVTargetEnergyRequest  = rat(30, 3),    // 30 kWh
                        eVMaximumEnergyRequest = rat(60, 3),    // 60 kWh
                        eVMinimumEnergyRequest = rat(10, 3),    // 10 kWh
                        eVMaximumChargePower   = rat(50, 3),    // 50 kW
                        eVMinimumChargePower   = rat(1,  3),    //  1 kW
                        eVMaximumChargeCurrent = rat(125),
                        eVMaximumVoltage       = rat(500),
                        eVMinimumVoltage       = rat(200))
                // Scheduled's limits are all optional, so these two arms differ only in the discharge
                // envelope, which is the whole of what makes the request bidirectional, hence stated.
                bidirectionalService ->
                    BPT_Scheduled_DC_CLReqControlModeType(
                        null, null, null,
                        eVTargetCurrent = rat(120), eVTargetVoltage = rat(400),
                        null, null, null, null, null,
                        eVMaximumDischargePower   = discharging(rat(5_000, 1)),   // 50 kW, the declared envelope
                        eVMinimumDischargePower   = discharging(rat(0)),
                        eVMaximumDischargeCurrent = discharging(rat(200)))
                else ->
                    Scheduled_DC_CLReqControlModeType(
                        null, null, null,
                        eVTargetCurrent = rat(120), eVTargetVoltage = rat(400),
                        null, null, null, null, null)
            }

        val request = DC_ChargeLoopReq(sessionCtx.toDcHeader(),
            displayParameters = null, meterInfoRequested = false,
            eVPresentVoltage = rat(400),
            cLReqControlMode = controlMode)

        val (set, message) = exchangeRaw(MessageSet.Iso20DC, DCCodec.encode(request))
        val response = expect<DC_ChargeLoopRes>(set, message, MessageSet.Iso20DC)

        // [V2G20-1477]: the station asks for a service renegotiation through the otherwise absent
        // EVSEStatus. The base acts on it once this iteration is finished and the contactor is
        // open — it cannot see this type, which is why the loop reports it.
        noteRenegotiationRequest(
            response.eVSEStatus?.eVSENotification == cloud.charging.v2g.iso20.dc.EvseNotification.ServiceRenegotiation)

        // The EV's own voltage — it sent EVPresentVoltage above, and a DC vehicle really does measure
        // that at its own inlet — times the current the station reports. Half-borrowed on purpose:
        // -20 DC gives the vehicle no field for a current it measured itself, and EVTargetCurrent
        // would be a *request* rather than a measurement, and does not exist in Dynamic mode at all.
        meter.sample(amount(request.eVPresentVoltage) * amount(response.eVSEPresentCurrent))

        // [V2G20-1083]: the station asks the car to sign what this response carried. After the
        // sample, so the station's reading is paired with the car's count of the same iteration.
        if (response.eVSEStatus?.eVSENotification == cloud.charging.v2g.iso20.dc.EvseNotification.MeteringConfirmation)
            confirmMetering(response.meterInfo?.let(MeteringConfirmation::toCommon),
                            response.receipt?.let(MeteringConfirmation::toCommon))
    }

    /** A RationalNumber as a plain number: value x 10^exponent. */
    private fun amount(v: RationalNumberType): Double =
        v.value.toDouble() * Math.pow(10.0, v.exponent.toDouble())

    override fun runPostChargeSequence() {
        val request = DC_WeldingDetectionReq(sessionCtx.toDcHeader(), Processing.Finished)
        val (set, message) = exchangeRaw(MessageSet.Iso20DC, DCCodec.encode(request))
        expect<DC_WeldingDetectionRes>(set, message, MessageSet.Iso20DC)
    }

    /** (value, exponent) as in the C# helper; the generated record takes (exponent, value). */
    private fun rat(value: Short, exponent: Byte = 0) = RationalNumberType(exponent, value)

    /** A limit on energy flowing from the vehicle, as it goes on the wire: negative. [V2G20-1213] sets
     *  parameters used for discharging negative, for AC and DC BPT alike, as C#'s Discharging does;
     *  EVerest 2026.10.0's EV and station send theirs that way. */
    private fun discharging(magnitude: RationalNumberType) =
        magnitude.copy(value = (-kotlin.math.abs(magnitude.value.toInt())).toShort())
}
