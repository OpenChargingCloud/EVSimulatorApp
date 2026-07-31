package cloud.charging.v2g.evcc

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

        val request = DC_ChargeParameterDiscoveryReq(sessionCtx.toDcHeader(),
            DC_CPDReqEnergyTransferModeType(
                eVMaximumChargePower   = rat(5_000, 1),
                eVMinimumChargePower   = rat(0),
                eVMaximumChargeCurrent = rat(200),
                eVMinimumChargeCurrent = rat(0),
                eVMaximumVoltage       = rat(500),
                eVMinimumVoltage       = rat(50),
                targetSOC              = 80))

        val (set, message) = exchangeRaw(MessageSet.Iso20DC, DCCodec.encode(request))
        expect<DC_ChargeParameterDiscoveryRes>(set, message, MessageSet.Iso20DC)
    }

    override fun runPreChargeSequence() {

        while (true) {
            val (set, message) = exchangeRaw(MessageSet.Iso20DC,
                DCCodec.encode(DC_CableCheckReq(sessionCtx.toDcHeader())))
            val res = expect<DC_CableCheckRes>(set, message, MessageSet.Iso20DC)
            if (res.eVSEProcessing == Processing.Finished) break
            pollDelay(POLL_INTERVAL_MS)
        }

        val preCharge = DC_PreChargeReq(sessionCtx.toDcHeader(), Processing.Finished,
                                        eVPresentVoltage = rat(0), eVTargetVoltage = rat(400))
        val (set, message) = exchangeRaw(MessageSet.Iso20DC, DCCodec.encode(preCharge))
        expect<DC_PreChargeRes>(set, message, MessageSet.Iso20DC)
    }

    override fun runChargeLoopIteration() {

        val request = DC_ChargeLoopReq(sessionCtx.toDcHeader(),
            displayParameters = null, meterInfoRequested = false,
            eVPresentVoltage = rat(400),
            cLReqControlMode = Scheduled_DC_CLReqControlModeType(
                null, null, null,
                eVTargetCurrent = rat(120), eVTargetVoltage = rat(400),
                null, null, null, null, null))

        val (set, message) = exchangeRaw(MessageSet.Iso20DC, DCCodec.encode(request))
        expect<DC_ChargeLoopRes>(set, message, MessageSet.Iso20DC)
    }

    override fun runPostChargeSequence() {
        val request = DC_WeldingDetectionReq(sessionCtx.toDcHeader(), Processing.Finished)
        val (set, message) = exchangeRaw(MessageSet.Iso20DC, DCCodec.encode(request))
        expect<DC_WeldingDetectionRes>(set, message, MessageSet.Iso20DC)
    }

    /** (value, exponent) as in the C# helper; the generated record takes (exponent, value). */
    private fun rat(value: Short, exponent: Byte = 0) = RationalNumberType(exponent, value)
}
