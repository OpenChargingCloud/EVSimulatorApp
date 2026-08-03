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
                    eVPresentActivePower      = rat(2_200, 1),
                    eVPresentActivePower_L2   = null, eVPresentActivePower_L3 = null,
                    eVPresentReactivePower    = rat(0),
                    eVPresentReactivePower_L2 = null, eVPresentReactivePower_L3 = null)
            else
                Scheduled_AC_CLReqControlModeType(
                    null, null, null, null, null, null, null, null, null,
                    eVPresentActivePower = rat(2_200, 1),
                    null, null, null, null, null)

        val request = AC_ChargeLoopReq(sessionCtx.toAcHeader(),
            displayParameters = null, meterInfoRequested = false,
            cLReqControlMode = controlMode)

        val (set, message) = exchangeRaw(MessageSet.Iso20AC, ACCodec.encode(request))
        expect<AC_ChargeLoopRes>(set, message, MessageSet.Iso20AC)
    }

    override fun runPostChargeSequence() = Unit   // AC: not applicable

    /** Note the argument order: the C# helper takes (value, exponent) and the generated record takes
     *  (exponent, value). Keeping the helper's order identical to C#'s is what makes the two call
     *  sites read the same. */
    private fun rat(value: Short, exponent: Byte = 0) = RationalNumberType(exponent, value)
}
