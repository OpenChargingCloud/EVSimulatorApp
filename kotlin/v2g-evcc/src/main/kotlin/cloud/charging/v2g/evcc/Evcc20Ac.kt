package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso20.ac.AC_CPDReqEnergyTransferModeType
import cloud.charging.v2g.iso20.ac.AC_ChargeLoopReq
import cloud.charging.v2g.iso20.ac.AC_ChargeLoopRes
import cloud.charging.v2g.iso20.ac.AC_ChargeParameterDiscoveryReq
import cloud.charging.v2g.iso20.ac.AC_ChargeParameterDiscoveryRes
import cloud.charging.v2g.iso20.ac.ACCodec
import cloud.charging.v2g.iso20.ac.Dynamic_AC_CLReqControlModeType
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

        val request = AC_ChargeParameterDiscoveryReq(sessionCtx.toAcHeader(),
            AC_CPDReqEnergyTransferModeType(
                eVMaximumChargePower    = rat(2_200, 1),
                eVMaximumChargePower_L2 = null,
                eVMaximumChargePower_L3 = null,
                eVMinimumChargePower    = rat(0),
                eVMinimumChargePower_L2 = null,
                eVMinimumChargePower_L3 = null))

        val (set, message) = exchangeRaw(MessageSet.Iso20AC, ACCodec.encode(request))
        expect<AC_ChargeParameterDiscoveryRes>(set, message, MessageSet.Iso20AC)
    }

    override fun runPreChargeSequence() = Unit    // AC: not applicable

    override fun runChargeLoopIteration() {

        // Asking in kind, the mirror of [V2G20-1600] — see [Evcc20Dc] for the same split and why.
        val controlMode =
            if (preferDynamicControlMode)
                Dynamic_AC_CLReqControlModeType(
                    departureTime             = departureTime,
                    eVTargetEnergyRequest     = rat(30, 3),     // 30 kWh
                    eVMaximumEnergyRequest    = rat(60, 3),     // 60 kWh
                    eVMinimumEnergyRequest    = rat(10, 3),     // 10 kWh
                    eVMaximumChargePower      = rat(11, 3),     // 11 kW
                    eVMaximumChargePower_L2   = null, eVMaximumChargePower_L3 = null,
                    eVMinimumChargePower      = rat(1, 3),
                    eVMinimumChargePower_L2   = null, eVMinimumChargePower_L3 = null,
                    eVPresentActivePower      = PRESENT_ACTIVE_POWER,
                    eVPresentActivePower_L2   = null, eVPresentActivePower_L3 = null,
                    eVPresentReactivePower    = rat(0),
                    eVPresentReactivePower_L2 = null, eVPresentReactivePower_L3 = null)
            else
                Scheduled_AC_CLReqControlModeType(
                    null, null, null, null, null, null, null, null, null,
                    eVPresentActivePower = PRESENT_ACTIVE_POWER,
                    null, null, null, null, null)

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

        // The one place in this project where the EV's own inlet power is a field on the wire: -20 AC
        // has EVPresentActivePower in the request, so the vehicle's view needs no deriving and
        // nothing borrowed from the station.
        meter.sample(PRESENT_ACTIVE_POWER_W)
    }

    override fun runPostChargeSequence() = Unit   // AC: not applicable

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
