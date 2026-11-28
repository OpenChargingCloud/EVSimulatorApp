package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso20.ac.AC_CPDReqEnergyTransferModeType
import cloud.charging.v2g.iso20.ac.BPT_AC_CPDReqEnergyTransferModeType
import cloud.charging.v2g.iso20.ac.BPT_Dynamic_AC_CLReqControlModeType
import cloud.charging.v2g.iso20.ac.BPT_Scheduled_AC_CLReqControlModeType
import cloud.charging.v2g.iso20.ac.AC_ChargeLoopReq
import cloud.charging.v2g.iso20.ac.AC_ChargeLoopRes
import cloud.charging.v2g.iso20.ac.AC_ChargeParameterDiscoveryReq
import cloud.charging.v2g.iso20.ac.AC_ChargeParameterDiscoveryRes
import cloud.charging.v2g.iso20.ac.ACCodec
import cloud.charging.v2g.iso20.ac.Dynamic_AC_CLReqControlModeType
import cloud.charging.v2g.iso20.ac.Dynamic_AC_CLResControlModeType
import cloud.charging.v2g.iso20.ac.RationalNumberType
import cloud.charging.v2g.iso20.ac.Scheduled_AC_CLReqControlModeType
import cloud.charging.v2g.tp.MessageSet

/**
 * The AC hooks: charge-parameter discovery and one charge-loop iteration. No pre- or post-charge
 * sequence — a cable check, a pre-charge and a welding detection are DC-only, and an AC session that
 * ran one would be wrong rather than merely unusual.
 *
 * A port of the C# `Evcc20Ac`.
 */
class Evcc20Ac(
    stream: V2GTPStream,
    clock: () -> ULong,
    pollDelay: (Long) -> Unit = { Thread.sleep(it) },
) : Evcc20Base(stream, clock, pollDelay) {

    override val energyMode get() = PowerMode.Ac

    override fun runChargeParameterDiscovery() {

        // Asking in kind on the direction axis, see [Evcc20Dc]. AC's BPT subtype is the charge-only
        // one plus a discharge power per phase; this EV is single-phase and symmetric, so L2/L3 stay
        // null on both halves and the discharge envelope mirrors the charge one.
        val transferMode =
            if (bidirectionalService)
                BPT_AC_CPDReqEnergyTransferModeType(
                    eVMaximumChargePower       = rat(2_200, 1),
                    eVMaximumChargePower_L2    = null,
                    eVMaximumChargePower_L3    = null,
                    eVMinimumChargePower       = rat(0),
                    eVMinimumChargePower_L2    = null,
                    eVMinimumChargePower_L3    = null,
                    eVMaximumDischargePower    = rat(2_200, 1),
                    eVMaximumDischargePower_L2 = null,
                    eVMaximumDischargePower_L3 = null,
                    eVMinimumDischargePower    = rat(0),
                    eVMinimumDischargePower_L2 = null,
                    eVMinimumDischargePower_L3 = null)
            else
                AC_CPDReqEnergyTransferModeType(
                    eVMaximumChargePower    = rat(2_200, 1),
                    eVMaximumChargePower_L2 = null,
                    eVMaximumChargePower_L3 = null,
                    eVMinimumChargePower    = rat(0),
                    eVMinimumChargePower_L2 = null,
                    eVMinimumChargePower_L3 = null)

        val request = AC_ChargeParameterDiscoveryReq(sessionCtx.toAcHeader(), transferMode)

        val (set, message) = exchangeRaw(MessageSet.Iso20AC, ACCodec.encode(request))
        expect<AC_ChargeParameterDiscoveryRes>(set, message, MessageSet.Iso20AC)
    }

    override fun runPreChargeSequence() = Unit    // AC: not applicable

    override fun runChargeLoopIteration() {

        // Asking in kind, the mirror of [V2G20-1600], on both axes — see [Evcc20Dc] for the same
        // split and why.
        val controlMode =
            when {
                // The Dynamic discharge pair is *mandatory* in the BPT subtype: a station steering a
                // bidirectional session needs to know how far either way.
                preferDynamicControlMode && bidirectionalService ->
                    BPT_Dynamic_AC_CLReqControlModeType(
                        departureTime              = departureTime,
                        eVTargetEnergyRequest      = rat(30, 3),     // 30 kWh
                        eVMaximumEnergyRequest     = rat(60, 3),     // 60 kWh
                        eVMinimumEnergyRequest     = rat(10, 3),     // 10 kWh
                        eVMaximumChargePower       = rat(11, 3),     // 11 kW
                        eVMaximumChargePower_L2    = null, eVMaximumChargePower_L3 = null,
                        eVMinimumChargePower       = rat(1, 3),
                        eVMinimumChargePower_L2    = null, eVMinimumChargePower_L3 = null,
                        eVPresentActivePower       = presentActivePower,
                        eVPresentActivePower_L2    = null, eVPresentActivePower_L3 = null,
                        eVPresentReactivePower     = rat(0),
                        eVPresentReactivePower_L2  = null, eVPresentReactivePower_L3 = null,
                        eVMaximumDischargePower    = rat(11, 3),     // the charge request mirrored
                        eVMaximumDischargePower_L2 = null, eVMaximumDischargePower_L3 = null,
                        eVMinimumDischargePower    = rat(1, 3),
                        eVMinimumDischargePower_L2 = null, eVMinimumDischargePower_L3 = null,
                        eVMaximumV2XEnergyRequest  = null, eVMinimumV2XEnergyRequest = null)
                preferDynamicControlMode ->
                    Dynamic_AC_CLReqControlModeType(
                        departureTime             = departureTime,
                        eVTargetEnergyRequest     = rat(30, 3),     // 30 kWh
                        eVMaximumEnergyRequest    = rat(60, 3),     // 60 kWh
                        eVMinimumEnergyRequest    = rat(10, 3),     // 10 kWh
                        eVMaximumChargePower      = rat(11, 3),     // 11 kW
                        eVMaximumChargePower_L2   = null, eVMaximumChargePower_L3 = null,
                        eVMinimumChargePower      = rat(1, 3),
                        eVMinimumChargePower_L2   = null, eVMinimumChargePower_L3 = null,
                        eVPresentActivePower      = presentActivePower,
                        eVPresentActivePower_L2   = null, eVPresentActivePower_L3 = null,
                        eVPresentReactivePower    = rat(0),
                        eVPresentReactivePower_L2 = null, eVPresentReactivePower_L3 = null)
                // Scheduled's limits are all optional, so these two arms differ only in the discharge
                // envelope, which is the whole of what makes the request bidirectional, hence stated.
                bidirectionalService ->
                    BPT_Scheduled_AC_CLReqControlModeType(
                        null, null, null, null, null, null, null, null, null,
                        eVPresentActivePower       = presentActivePower,
                        null, null, null, null, null,
                        eVMaximumDischargePower    = rat(2_200, 1),  // 22 kW: what the car can give back
                        eVMaximumDischargePower_L2 = null, eVMaximumDischargePower_L3 = null,
                        eVMinimumDischargePower    = rat(0),
                        eVMinimumDischargePower_L2 = null, eVMinimumDischargePower_L3 = null)
                else ->
                    Scheduled_AC_CLReqControlModeType(
                        null, null, null, null, null, null, null, null, null,
                        eVPresentActivePower = presentActivePower,
                        null, null, null, null, null)
            }

        val request = AC_ChargeLoopReq(sessionCtx.toAcHeader(),
            displayParameters = null, meterInfoRequested = false,
            cLReqControlMode = controlMode)

        val (set, message) = exchangeRaw(MessageSet.Iso20AC, ACCodec.encode(request))
        val response = expect<AC_ChargeLoopRes>(set, message, MessageSet.Iso20AC)

        // [V2G20-1477]: the station asks for a service renegotiation through the otherwise absent
        // EVSEStatus. The base acts on it once this iteration is finished and the contactor is
        // open — it cannot see this type, which is why the loop reports it.
        noteRenegotiationRequest(
            response.eVSEStatus?.eVSENotification == cloud.charging.v2g.iso20.ac.EvseNotification.ServiceRenegotiation)

        // The station's ask for the next iteration, read before this one is counted: it changes
        // nothing about what this request already said.
        val sent = presentActivePower
        dischargeTarget = dischargeTargetOf(response)

        // The one place in this project where the EV's own inlet power is a field on the wire: -20 AC
        // has EVPresentActivePower in the request, so the vehicle's view needs no deriving and
        // nothing borrowed from the station. Read back off the rational it just sent.
        meter.sample(amount(sent))

        // [V2G20-1083]: the station asks the car to sign what this response carried. After the
        // sample, so the station's reading is paired with the car's count of the same iteration.
        if (response.eVSEStatus?.eVSENotification == cloud.charging.v2g.iso20.ac.EvseNotification.MeteringConfirmation)
            confirmMetering(response.meterInfo?.let(MeteringConfirmation::toCommon),
                            response.receipt?.let(MeteringConfirmation::toCommon))
    }

    override fun runPostChargeSequence() = Unit   // AC: not applicable

    /**
     * What the station last asked this vehicle to give back, as the `EVSETargetActivePower` it sent,
     * or null while it is not asking. A port of the C# `Evcc20Ac._dischargeTarget`.
     *
     * [V2G20-1825]: the vehicle adjusts to the setpoints the station sends. A negative target in a
     * bidirectional session is the station asking for energy back, and this vehicle reports exactly
     * that figure as its `EVPresentActivePower` from the next iteration on, the rational passed through
     * as received. A positive target is left as it always was: our station's Dynamic target is its
     * 22 kW offer, and the vehicle draws what it wants inside it.
     */
    private var dischargeTarget: RationalNumberType? = null

    /** The power this request reports: the station's discharge ask when there is one, else 22 kW. */
    private val presentActivePower: RationalNumberType get() = dischargeTarget ?: PRESENT_ACTIVE_POWER

    /** The discharge the station asks for in [response], held inside this vehicle's own 22 kW; null in
     *  a charge-only session, in Scheduled mode, or for a target ≥ 0. */
    private fun dischargeTargetOf(response: AC_ChargeLoopRes): RationalNumberType? {
        val dynamic = response.cLResControlMode as? Dynamic_AC_CLResControlModeType
        if (!bidirectionalService || dynamic == null) return null

        val target = amount(dynamic.eVSETargetActivePower)
        return when {
            target >= 0                     -> null
            target >= -PRESENT_ACTIVE_POWER_W -> dynamic.eVSETargetActivePower
            else                            -> RationalNumberType(0, (-22_000).toShort())   // as C#'s Watts(-22 000)
        }
    }

    /** A RationalNumber as a plain number: value x 10^exponent. */
    private fun amount(v: RationalNumberType): Double =
        v.value.toDouble() * Math.pow(10.0, v.exponent.toDouble())

    /** Note the argument order: the C# helper takes (value, exponent) and the generated record takes
     *  (exponent, value). Keeping the helper's order identical to C#'s is what makes the two call
     *  sites read the same. */
    private fun rat(value: Short, exponent: Byte = 0) = RationalNumberType(exponent, value)

    companion object {

        /** The EV's present active power, 22 kW. One constant rather than the same literal in two
         *  control-mode branches and again at the meter: those three drifting apart would mean the
         *  vehicle's counter no longer counted what the vehicle said. */
        private val PRESENT_ACTIVE_POWER = RationalNumberType(1, 2_200)
        private const val PRESENT_ACTIVE_POWER_W = 22_000.0
    }
}
