package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso2.*
import cloud.charging.v2g.tp.MessageSet

/**
 * The EV's smart-charging verdict over the SASchedule offer: how many tuples were offered, which one
 * the EV chose (lowest average EPriceLevel), and how many ChargingProfile entries it derived from
 * that tuple's PMaxSchedule.
 *
 * The C# record carries four more fields describing the tariff *signature*; see [Evcc2] on why that
 * half is not ported yet.
 */
data class Iso2TariffResult(
    val tuplesOffered: Int,
    val chosenTupleId: UByte,
    val profileEntries: Int,
)

/**
 * The vehicle (EVCC) side of an ISO 15118-2 session: it drives the session over an already-connected
 * and already-SAP-negotiated [V2GTPStream]. Each step is one request/response exchange; the poll
 * loops (Authorization, ChargeParameterDiscovery) back off through [pollDelay].
 *
 * A port of the C# `Evcc2`, and checked against it rather than against itself: `Evcc2TraceTest`
 * replays the sessions recorded in `Vectors/Session.iso2-*.trace.json` and requires this
 * implementation to emit byte-identical requests. That corpus is the only reason to believe the two
 * agree — there is no reference EVCC, and a state machine that merely runs to completion has proved
 * nothing about *what* it said.
 *
 * ## What is not here yet
 *
 * **Plug & Charge, and tariff-signature verification.** The C# original also does PaymentDetails
 * with a contract chain, a signed AuthorizationReq, signed MeteringReceiptReq, and the §7.9.2.5
 * check over signed SalesTariffs. All four are signature work, and the trace corpus is EIM — ECDSA
 * signing is randomised, so a signed request cannot be compared byte for byte at all and needs a
 * signature-aware comparison first. Porting them now would mean writing crypto with no oracle, which
 * is the one thing this repository has repeatedly found it should not do. The EIM path below is
 * complete, and the missing half is named rather than silently absent.
 *
 * **Pause/resume** ([V2G2-740]) is likewise unported: it needs a trace that pauses and rejoins.
 */
class Evcc2(
    private val stream: V2GTPStream,
    private val mode: PowerMode,
    private val pollDelay: (Long) -> Unit = { Thread.sleep(it) },
) {

    private companion object {
        const val POLL_INTERVAL_MS = 50L
        const val CHARGE_CYCLES    = 3
    }

    private var sessionId = ByteArray(8)   // 0 until SessionSetupRes assigns one

    /** How many request/response exchanges this session ran. */
    var exchanges: Int = 0
        private set

    /**
     * When set, the EV initiates one renegotiation on its own after the first charging-status cycle.
     * Independent of that, the EV always reacts to a station-side `EVSENotification.ReNegotiation`.
     */
    var renegotiate: Boolean = false

    /** How many renegotiation cycles this session ran (own + station-requested). */
    var renegotiations: Int = 0
        private set

    /** How the session ends: Terminate (default) or Pause. */
    var stopMode: ChargingSession = ChargingSession.Terminate

    /** The station's SessionSetup verdict. */
    var sessionSetupCode: ResponseCode? = null
        private set

    /** The smart-charging verdict over the (last) offer; null until ChargeParameterDiscovery ended. */
    var tariff: Iso2TariffResult? = null
        private set

    private var chosenTupleId: UByte = 1u
    private var chargingProfile: ChargingProfileType? = null


    fun run() {

        // ── SETUP ──────────────────────────────────────────────────────────
        val setup = send<SessionSetupResType>(
            SessionSetupReqType(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x01, 0x02, 0x03)))
        sessionSetupCode = setup.responseCode

        send<ServiceDiscoveryResType>(ServiceDiscoveryReqType(serviceScope = null, serviceCategory = null))

        // EIM only — see the class comment on Plug & Charge.
        send<PaymentServiceSelectionResType>(PaymentServiceSelectionReqType(
            PaymentOption.ExternalPayment,
            SelectedServiceListType(listOf(SelectedServiceType(serviceID = 1u, parameterSetID = null)))))

        // ── AUTH (poll until authorised) ───────────────────────────────────
        val authRequest = AuthorizationReqType(id = null, genChallenge = null)
        while (send<AuthorizationResType>(authRequest).eVSEProcessing != EVSEProcessing.Finished)
            pollDelay(POLL_INTERVAL_MS)

        // ── CHARGE PARAMETERS (+ DC cable check / pre-charge) ──────────────
        runChargeParameterDiscovery()

        if (mode == PowerMode.Dc) {
            while (send<CableCheckResType>(CableCheckReqType(evStatus())).eVSEProcessing != EVSEProcessing.Finished)
                pollDelay(POLL_INTERVAL_MS)
            send<PreChargeResType>(PreChargeReqType(evStatus(),
                eVTargetVoltage = volt(400), eVTargetCurrent = amp(2)))
        }

        // ── CHARGE ─────────────────────────────────────────────────────────
        send<PowerDeliveryResType>(powerDelivery(ChargeProgress.Start))

        var renegotiated = false
        repeat(CHARGE_CYCLES) {

            val notification =
                if (mode == PowerMode.Dc) send<CurrentDemandResType>(currentDemand()).dC_EVSEStatus.eVSENotification
                else                      send<ChargingStatusResType>(ChargingStatusReqType()).aC_EVSEStatus.eVSENotification

            // Renegotiation ([V2G2-841]) — reactive (the station notified) or proactive (once):
            // PowerDelivery(Renegotiate) → fresh ChargeParameterDiscovery → PowerDelivery(Start).
            if (!renegotiated && (notification == EVSENotification.ReNegotiation || renegotiate)) {
                renegotiated = true
                renegotiations++
                send<PowerDeliveryResType>(powerDelivery(ChargeProgress.Renegotiate))
                runChargeParameterDiscovery()
                send<PowerDeliveryResType>(powerDelivery(ChargeProgress.Start))
            }
            pollDelay(POLL_INTERVAL_MS)
        }

        send<PowerDeliveryResType>(powerDelivery(ChargeProgress.Stop))

        // ── STOP ───────────────────────────────────────────────────────────
        if (mode == PowerMode.Dc)
            send<WeldingDetectionResType>(WeldingDetectionReqType(evStatus()))

        send<SessionStopResType>(SessionStopReqType(stopMode))
    }


    /** Polls ChargeParameterDiscovery until Finished, then evaluates the offer. Runs again after a
     *  renegotiation, because the offer may have changed. */
    private fun runChargeParameterDiscovery() {
        var response: ChargeParameterDiscoveryResType
        while (true) {
            response = send(chargeParameterDiscovery())
            if (response.eVSEProcessing == EVSEProcessing.Finished) break
            pollDelay(POLL_INTERVAL_MS)
        }
        evaluateSchedules(response)
    }


    /**
     * The EV-side smart-charging step: choose the tuple with the lowest average EPriceLevel, and
     * shape the ChargingProfile to that tuple's PMaxSchedule entry for entry — this simulated EV can
     * always draw PMax, where a weaker one would cap at its own limit.
     *
     * Both outputs travel in the next `PowerDeliveryReq(Start)`, so this is not bookkeeping: get the
     * tuple choice or the profile wrong and the trace diverges at that message.
     */
    private fun evaluateSchedules(response: ChargeParameterDiscoveryResType) {

        val offer = response.sASchedules as? SAScheduleListType
        if (offer == null || offer.sAScheduleTuple.isEmpty()) {
            // No offer. [V2G2-905] makes this a station bug, EVSEProcessing games aside.
            tariff = null
            chosenTupleId = 1u
            chargingProfile = null
            return
        }

        // Lowest average EPriceLevel; tariff-less tuples rank last, ties keep the offer's order.
        val chosen = offer.sAScheduleTuple.minByOrNull(::averagePriceLevel)!!
        chosenTupleId = chosen.sAScheduleTupleID

        val profile = ChargingProfileType(chosen.pMaxSchedule.pMaxScheduleEntry.map { entry ->
            ProfileEntryType(
                chargingProfileEntryStart = (entry.timeInterval as? RelativeTimeIntervalType)?.start ?: 0u,
                chargingProfileEntryMaxPower = entry.pMax,
                chargingProfileEntryMaxNumberOfPhasesInUse = null)
        })
        chargingProfile = profile

        tariff = Iso2TariffResult(offer.sAScheduleTuple.size, chosenTupleId, profile.profileEntry.size)
    }

    private fun averagePriceLevel(tuple: SAScheduleTupleType): Double {
        val entries = tuple.salesTariff?.salesTariffEntry ?: return Double.MAX_VALUE
        if (entries.isEmpty()) return Double.MAX_VALUE
        return entries.map { (it.ePriceLevel ?: UByte.MAX_VALUE).toDouble() }.average()
    }


    private inline fun <reified T : BodyBaseType> send(requestBody: BodyBaseType): T {

        val header  = MessageHeaderType(sessionId, notification = null, signature = null)
        val request = V2G_Message(header, BodyType(requestBody))

        stream.writeFrame(MessageSet.Iso15118_2, Iso15118_2Codec.encode(request))

        val (set, message) = stream.readFrame()
        if (set != MessageSet.Iso15118_2 || message !is V2G_Message)
            throw SessionAborted("expected an ISO 15118-2 reply, got $set.")

        exchanges++
        sessionId = message.header.sessionID   // adopt the station-assigned session id

        val body = message.body.bodyElement
        if (body !is T)
            throw SessionAborted(
                "expected a ${T::class.simpleName}, got ${body?.let { it::class.simpleName } ?: "an empty body"}.")

        return body
    }


    // ── request builders ──────────────────────────────────────────────────

    /** Start carries the smart-charging outcome — the chosen tuple and the PMax-shaped profile;
     *  Renegotiate/Stop reference the tuple without a profile. */
    private fun powerDelivery(progress: ChargeProgress) =
        PowerDeliveryReqType(
            chargeProgress = progress,
            sAScheduleTupleID = chosenTupleId,
            chargingProfile = if (progress == ChargeProgress.Start) chargingProfile else null,
            eVPowerDeliveryParameter = null)

    private fun chargeParameterDiscovery() =
        if (mode == PowerMode.Dc)
            ChargeParameterDiscoveryReqType(null, EnergyTransferMode.DC_extended,
                DC_EVChargeParameterType(
                    departureTime = null, dC_EVStatus = evStatus(),
                    eVMaximumCurrentLimit = amp(200), eVMaximumPowerLimit = null,
                    eVMaximumVoltageLimit = volt(500), eVEnergyCapacity = null,
                    eVEnergyRequest = null, fullSOC = 100, bulkSOC = 80))
        else
            ChargeParameterDiscoveryReqType(null, EnergyTransferMode.AC_three_phase_core,
                AC_EVChargeParameterType(
                    departureTime = null,
                    eAmount = PhysicalValueType(0, UnitSymbol.Wh, 22_000),
                    eVMaxVoltage = volt(400), eVMaxCurrent = amp(32), eVMinCurrent = amp(6)))

    private fun currentDemand() =
        CurrentDemandReqType(
            dC_EVStatus = evStatus(), eVTargetCurrent = amp(120),
            eVMaximumVoltageLimit = null, eVMaximumCurrentLimit = null, eVMaximumPowerLimit = null,
            bulkChargingComplete = null, chargingComplete = false,
            remainingTimeToFullSoC = null, remainingTimeToBulkSoC = null,
            eVTargetVoltage = volt(400))

    private fun evStatus() = DC_EVStatusType(eVReady = true, eVErrorCode = DC_EVErrorCode.NO_ERROR, eVRESSSOC = 50)
    private fun volt(v: Short) = PhysicalValueType(0, UnitSymbol.V, v)
    private fun amp(a: Short)  = PhysicalValueType(0, UnitSymbol.A, a)
}
