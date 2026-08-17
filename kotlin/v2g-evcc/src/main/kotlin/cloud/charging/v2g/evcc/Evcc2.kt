package cloud.charging.v2g.evcc

import cloud.charging.v2g.metering.EvMeter
import cloud.charging.v2g.certificates.V2GCertificate
import cloud.charging.v2g.iso2.*
import cloud.charging.v2g.tp.MessageSet

/**
 * The EV's smart-charging verdict over the SASchedule offer: how many tuples were offered, which one
 * the EV chose (lowest average EPriceLevel), and how many ChargingProfile entries it derived from
 * that tuple's PMaxSchedule.
 *
 * The §7.9.2.5 signature fields come from [Iso2TariffCheck]; only [Evcc2.tariffVerifyKey] makes the
 * signature half answerable at all.
 */
data class Iso2TariffResult(
    val tuplesOffered: Int,
    val signaturePresent: Boolean,
    val digestOk: Boolean,
    val signatureOk: Boolean,
    val signatureGrammar: String,
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
 * **Pause/resume** ([V2G2-740]) is unported: it needs a trace that pauses and rejoins.
 */
class Evcc2(
    private val stream: V2GTPStream,
    private val mode: PowerMode,
    private val pollDelay: (Long) -> Unit = { Thread.sleep(it) },
) {

    /** How long a phase may keep answering `EVSEProcessing = Ongoing` before the session ends —
     *  60 s, ISO 15118's EVCC ongoing timeout. See [OngoingGuard] for the live run that required it. */
    var ongoingTimeoutMillis: Long = 60_000

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

    /** Skips the DC isolation sequence on the way back from a renegotiation. Off by default, because
     *  the standard has no such exception — it exists for stations that refuse the conformant path,
     *  and mirrors C#'s `RenegotiationSkipsIsolationSequence`. */
    var renegotiationSkipsIsolationSequence: Boolean = false
        private set

    /** How the session ends: Terminate (default) or Pause. */
    var stopMode: ChargingSession = ChargingSession.Terminate

    /** Contract credentials. When set and the station offers Contract, the session runs Plug & Charge
     *  instead of external payment. */
    var pnc: PncEvccOptions? = null

    /** How this session authorized: `"eim"`, or `"pnc-signed"` after a signed AuthorizationReq. */
    var authorizationMode: String = "eim"
        private set

    /** How many signed MeteringReceiptReq this session sent. Contract only — an EIM station never
     *  asks, so a non-zero count here is also the clearest single sign that PnC really ran. */
    /**
     * The vehicle's own energy counter — what this EV thinks it took, kept independently of what the
     * station reports (`docs/CONCEPT.md` §4.2/§4.3).
     */
    val meter: EvMeter = EvMeter()

    /**
     * A battery that fills up, and the goal that ends the charge loop. Null — the default — keeps the
     * fixed three iterations every recorded interop run was taken with, which is why it is opt-in
     * here exactly as it is in C#.
     */
    var battery: EvBattery? = null

    /** Why the charge loop ended; null while it has not finished. */
    var batteryStop: ChargeStop? = null
        private set

    var meteringReceiptsSent: Int = 0
        private set

    /** The station's SessionSetup verdict. */
    var sessionSetupCode: ResponseCode? = null
        private set

    /** The smart-charging verdict over the (last) offer; null until ChargeParameterDiscovery ended. */
    var tariff: Iso2TariffResult? = null
        private set

    /**
     * The Mobility Operator's public key, when the app has one. Without it the §7.9.2.5 digest half is
     * still checked and reported; the ECDSA half is not attempted, and [Iso2TariffResult.signatureOk]
     * stays `false` meaning *not established* rather than *failed* — which is why
     * [Iso2TariffResult.signatureGrammar] exists to tell those apart.
     */
    var tariffVerifyKey: java.security.PublicKey? = null

    /**
     * The header of the last response. Kept only for its Signature: the tariff check needs it, and it
     * arrives one layer above the body that [evaluateSchedules] is handed.
     */
    private var lastHeader: MessageHeaderType? = null

    private var chosenTupleId: UByte = 1u
    private var chargingProfile: ChargingProfileType? = null
    private var energyTransferMode: EnergyTransferMode? = null   // chosen from what the station offered


    fun run() {

        // Check the credential before opening the session, not four exchanges in: a station that has
        // already assigned a session id and been asked for its payment options should not then be
        // abandoned over something knowable before the first byte.
        pnc?.let { contractEmaid(it.contractCertificate) }

        // ── SETUP ──────────────────────────────────────────────────────────
        val setup = send<SessionSetupResType>(
            SessionSetupReqType(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x01, 0x02, 0x03)))
        sessionSetupCode = setup.responseCode

        val discovery = send<ServiceDiscoveryResType>(
            ServiceDiscoveryReqType(serviceScope = null, serviceCategory = null))
        energyTransferMode = selectEnergyTransferMode(discovery)

        // The service id is the station's, not a constant. Ours has always been 1 and so has every
        // counterparty's so far, which is exactly why this was a literal until the sweep of
        // 2026-08-03 (the schema makes ChargeService mandatory, so unlike C# there is no null case).
        val chargeServiceId = discovery.chargeService.serviceID

        // Plug & Charge only if we have credentials AND the station offers it; otherwise EIM.
        // Read once into a local: `pnc` is settable, and Kotlin will not smart-cast a mutable
        // property across the calls below.
        val credentials = pnc
        val contract = credentials != null &&
                       discovery.paymentOptionList.paymentOption.contains(PaymentOption.Contract)

        send<PaymentServiceSelectionResType>(PaymentServiceSelectionReqType(
            if (contract) PaymentOption.Contract else PaymentOption.ExternalPayment,
            SelectedServiceListType(listOf(SelectedServiceType(chargeServiceId, parameterSetID = null)))))

        // ── AUTH (poll until authorised) ───────────────────────────────────
        // Contract: PaymentDetails first (chain in, GenChallenge out), then a signed AuthorizationReq
        // echoing the challenge. Signed once — the challenge does not change across polls.
        var authRequest = AuthorizationReqType(id = null, genChallenge = null)
        var authSignature: SignatureType? = null

        if (contract) {
            val details = send<PaymentDetailsResType>(PaymentDetailsReqType(
                eMAID = contractEmaid(credentials!!.contractCertificate),
                contractSignatureCertChain = CertificateChainType(
                    id = null,
                    certificate = credentials.contractCertificate,
                    subCertificates = SubCertificatesType(credentials.subCertificates))))

            authRequest = AuthorizationReqType(id = "id1", genChallenge = details.genChallenge)
            authSignature = XmlDsigInterop.sign2(
                "id1", Iso15118_2Codec.encodeFragment_AuthorizationReq(authRequest), credentials.contractKey)
            authorizationMode = "pnc-signed"
        }

        val authGuard = OngoingGuard("Authorization", ongoingTimeoutMillis)
        while (send<AuthorizationResType>(authRequest, authSignature).eVSEProcessing != EVSEProcessing.Finished) {
            authGuard.tick()
            pollDelay(POLL_INTERVAL_MS)
        }

        // ── CHARGE PARAMETERS (+ DC cable check / pre-charge) ──────────────
        runChargeParameterDiscovery()

        if (mode == PowerMode.Dc)
            runDcIsolationSequence()

        // ── CHARGE ─────────────────────────────────────────────────────────
        send<PowerDeliveryResType>(powerDelivery(ChargeProgress.Start))

        var renegotiated = false
        // Three iterations stand in for a session when there is no battery; with one, the loop ends
        // when the car is done. Same rule as C#, and the same reason it is opt-in: every recorded run
        // was taken at three.
        var cycle = 0
        while (if (battery == null) cycle < CHARGE_CYCLES else batteryStop == null) {

            val energyBefore = meter.energy

            // A Contract station may demand a receipt in its status response — answer with a signed
            // MeteringReceiptReq echoing its MeterInfo, as a real EV does.
            val notification =
                if (mode == PowerMode.Dc) {
                    val demand = currentDemand()
                    val res = send<CurrentDemandResType>(demand)

                    // The EV's own view, from the EV's own request: ISO 15118-2 gives a DC vehicle no
                    // field for a *measured* inlet power, so what it asked for is the closest thing it
                    // owns — and taking the station's EVSEPresent* would make this counter an echo of
                    // the very number it exists to be compared against.
                    meter.sample(watts(demand.eVTargetVoltage, demand.eVTargetCurrent))

                    if (res.receiptRequired == true && res.meterInfo != null)
                        sendMeteringReceipt(res.meterInfo!!, res.sAScheduleTupleID)
                    res.dC_EVSEStatus.eVSENotification
                } else {
                    val res = send<ChargingStatusResType>(ChargingStatusReqType())

                    // AC carries no power in either direction, so the EV's own view is the profile it
                    // committed to in PowerDeliveryReq — derived by the EV itself from the tuple it
                    // chose, and validated by the station against its own PMax.
                    meter.sample(committedPowerW())

                    if (res.receiptRequired == true && res.meterInfo != null)
                        sendMeteringReceipt(res.meterInfo!!, res.sAScheduleTupleID)
                    res.aC_EVSEStatus.eVSENotification
                }

            // Renegotiation ([V2G2-841]) — reactive (the station notified) or proactive (once):
            // PowerDelivery(Renegotiate) → fresh ChargeParameterDiscovery → PowerDelivery(Start).
            if (!renegotiated && (notification == EVSENotification.ReNegotiation || renegotiate)) {
                renegotiated = true
                renegotiations++
                send<PowerDeliveryResType>(powerDelivery(ChargeProgress.Renegotiate))
                runChargeParameterDiscovery()

                // DC returns through the isolation sequence, exactly as it did on the way in: the
                // station's state table admits CableCheckReq after ChargeParameterDiscoveryReq and
                // nothing else ([V2G2-565], [V2G2-582]), with no renegotiation exception. The C# car
                // learned this on 2026-08-15, against a station that refused the shortcut; this port
                // went straight to PowerDelivery(Start) until the recording said otherwise.
                if (mode == PowerMode.Dc && !renegotiationSkipsIsolationSequence)
                    runDcIsolationSequence()

                send<PowerDeliveryResType>(powerDelivery(ChargeProgress.Start))
            }

            battery?.let { pack ->
                pack.add(meter.energy - energyBefore)
                val stop = pack.stop
                if (stop != ChargeStop.Running) batteryStop = stop
            }

            pollDelay(POLL_INTERVAL_MS)
            cycle++
        }

        send<PowerDeliveryResType>(powerDelivery(ChargeProgress.Stop))

        // ── STOP ───────────────────────────────────────────────────────────
        if (mode == PowerMode.Dc)
            send<WeldingDetectionResType>(WeldingDetectionReqType(evStatus()))

        send<SessionStopResType>(SessionStopReqType(stopMode))
    }


    /** Signs and sends one MeteringReceiptReq for the station's MeterInfo. */
    private fun sendMeteringReceipt(meterInfo: MeterInfoType, saScheduleTupleId: UByte?) {

        val receipt   = MeteringReceiptReqType("id2", sessionId, saScheduleTupleId, meterInfo)
        val signature = XmlDsigInterop.sign2(
            "id2", Iso15118_2Codec.encodeFragment_MeteringReceiptReq(receipt), pnc!!.contractKey)

        send<MeteringReceiptResType>(receipt, signature)
        meteringReceiptsSent++
    }


    /**
     * The eMAID for PaymentDetails — the contract certificate's Common Name, checked against the one
     * rule the schema states.
     *
     * ISO 15118-2 constrains `eMAIDType` to 14–15 characters (`V2G_CI_MsgDataTypes.xsd`), and that is
     * checked because it was checked nowhere: the generated codec does not apply string-length
     * facets, so a 19-character CN travelled in this repository's own recorded PnC session, accepted
     * by all three back ends. It is a **-2** rule — ISO 15118-20 never sends the eMAID from the
     * certificate — so it lives here and not on [PncEvccOptions].
     *
     * The reading itself is [V2GCertificate]'s job, not this file's. It used to parse the RFC 2253
     * subject by hand here; one reader for the whole app is the point of `v2g-certificates`.
     */
    private fun contractEmaid(certificateDer: ByteArray): String {

        val certificate = V2GCertificate(certificateDer)

        return certificate.emaid ?: throw SessionAborted(
            certificate.commonName?.let {
                "the contract certificate's Common Name \"$it\" is ${it.length} characters; " +
                "ISO 15118-2 allows an eMAID of 14 or 15, so this credential cannot authorize a -2 " +
                "Plug & Charge session."
            } ?: "the contract certificate has no Common Name, so there is no eMAID to send.")
    }


    /** Polls ChargeParameterDiscovery until Finished, then evaluates the offer. Runs again after a
     *  renegotiation, because the offer may have changed. Deadline-guarded like the other Ongoing
     *  poll loops — this one had quietly missed the ongoing-deadline port. */
    /**
     * The DC isolation sequence: CableCheck until the station says Finished, then one PreCharge.
     *
     * Extracted because it is reached from two places — once on the way in, and once on the way back
     * from a renegotiation. It used to exist only at the first, which is how this port sent
     * `PowerDelivery(Start)` where the recording sends `CableCheckReq`.
     */
    private fun runDcIsolationSequence() {
        val cableGuard = OngoingGuard("CableCheck", ongoingTimeoutMillis)
        while (send<CableCheckResType>(CableCheckReqType(evStatus())).eVSEProcessing != EVSEProcessing.Finished) {
            cableGuard.tick()
            pollDelay(POLL_INTERVAL_MS)
        }
        send<PreChargeResType>(PreChargeReqType(evStatus(),
            eVTargetVoltage = volt(400), eVTargetCurrent = amp(2)))
    }

    private fun runChargeParameterDiscovery() {
        var response: ChargeParameterDiscoveryResType
        val guard = OngoingGuard("ChargeParameterDiscovery", ongoingTimeoutMillis)
        while (true) {
            response = send(chargeParameterDiscovery())
            if (response.eVSEProcessing == EVSEProcessing.Finished) break
            guard.tick()
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

        val verdict = Iso2TariffCheck.evaluate(offer, lastHeader?.signature, tariffVerifyKey)

        tariff = Iso2TariffResult(
            tuplesOffered    = offer.sAScheduleTuple.size,
            signaturePresent = verdict.signaturePresent,
            digestOk         = verdict.digestOk,
            signatureOk      = verdict.signatureOk,
            signatureGrammar = verdict.signatureGrammar,
            chosenTupleId    = chosenTupleId,
            profileEntries   = profile.profileEntry.size)
    }

    private fun averagePriceLevel(tuple: SAScheduleTupleType): Double {
        val entries = tuple.salesTariff?.salesTariffEntry ?: return Double.MAX_VALUE
        if (entries.isEmpty()) return Double.MAX_VALUE
        return entries.map { (it.ePriceLevel ?: UByte.MAX_VALUE).toDouble() }.average()
    }


    private inline fun <reified T : BodyBaseType> send(requestBody: BodyBaseType,
                                                       signature: SignatureType? = null): T {

        val header  = MessageHeaderType(sessionId, notification = null, signature = signature)
        val request = V2G_Message(header, BodyType(requestBody))

        stream.writeFrame(MessageSet.Iso15118_2, Iso15118_2Codec.encode(request))

        val (set, message) = stream.readFrame()
        if (set != MessageSet.Iso15118_2 || message !is V2G_Message)
            throw SessionAborted("expected an ISO 15118-2 reply, got $set.")

        val body = message.body.bodyElement
        if (body != null) refuseOnFailure(body)

        exchanges++
        sessionId  = message.header.sessionID   // adopt the station-assigned session id
        lastHeader = message.header             // for its Signature; see the property

        if (body !is T)
            throw SessionAborted(
                "expected a ${T::class.simpleName}, got ${body?.let { it::class.simpleName } ?: "an empty body"}.")

        return body
    }


    /**
     * Ends the session when the station answers with a code from the `FAILED` family.
     *
     * The -2 half of the gap eVDriveFlow exposed in the -20 EVCC on 2026-08-01
     * (`../../docs/interop-runs/2026-08-01-edf-iso20-dc-notls/`, finding 3). Same
     * hole, and invisible for the same reason: our own SECC never answers FAILED, so the trace corpus —
     * this port's whole oracle — contains no such response.
     *
     * **Why reflection rather than a `when` over the types.** ISO 15118-2 has no common response base;
     * every `*ResType` declares its own `responseCode`. A `when` would be fail-open — the branch nobody
     * wrote, or the message added later, goes unchecked, which is the failure this exists to end. The
     * getter is read by name, which covers every response type there is and will be; the C# side
     * enumerates its generated assembly to prove the property is universal
     * (`Evcc2FailureHandlingTests.EveryResponseTypeIsCheckable`), and all three back ends are emitted
     * from the same schema plan.
     *
     * -2 has only two families: four `OK*` values and then `FAILED` onwards, with no `WARNING` — unlike
     * -20.
     */
    internal fun refuseOnFailure(body: BodyBaseType) {

        val code = try {
            body.javaClass.getMethod("getResponseCode").invoke(body) as? ResponseCode
        } catch (_: NoSuchMethodException) {
            null
        }

        if (code != null && code.ordinal >= ResponseCode.FAILED.ordinal)
            throw SessionAborted(
                "the station answered ${body::class.simpleName} with ${code.name}; the session ends here.")

    }


    /**
     * The energy transfer mode to request, chosen from the ones the station advertised in
     * `ServiceDiscoveryRes`'s ChargeService rather than assumed.
     *
     * This used to be hard-coded — `DC_extended` for DC, `AC_three_phase_core` for AC — and it
     * worked against every station this stack had met, because every one of them offered exactly
     * what we happened to name. EVerest's AC SIL configuration does not: it advertises single-phase,
     * answers a three-phase request with `FAILED_WrongEnergyTransferMode`, and is right to
     * (`docs/interop-runs/2026-08-03-everest-ac/`). The trace corpus could not show the difference,
     * because our own SECC offers exactly the mode the constant named — the ports' whole blind spot,
     * one layer along.
     *
     * Preference within our own power mode is best-first — three-phase over single-phase, extended
     * over core — and a station that offers nothing in our mode is refused with the offer named.
     */
    private fun selectEnergyTransferMode(discovery: ServiceDiscoveryResType): EnergyTransferMode {

        val offered = discovery.chargeService.supportedEnergyTransferMode.energyTransferMode

        val preferred =
            if (mode == PowerMode.Dc)
                listOf(EnergyTransferMode.DC_extended, EnergyTransferMode.DC_core,
                       EnergyTransferMode.DC_combo_core, EnergyTransferMode.DC_unique)
            else
                listOf(EnergyTransferMode.AC_three_phase_core, EnergyTransferMode.AC_single_phase_core)

        preferred.firstOrNull { it in offered }?.let { return it }

        // Nothing in our power mode. Say what was offered: it is the one line that turns "the
        // station refused" into "the station is a DC charger and we are an AC car".
        throw SessionAborted(
            "ServiceDiscovery: the station offers no ${if (mode == PowerMode.Dc) "DC" else "AC"} " +
            "energy transfer mode (offered: " +
            (if (offered.isEmpty()) "none" else offered.joinToString(", ")) + ").")
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

    private fun chargeParameterDiscovery(): ChargeParameterDiscoveryReqType {
        val transferMode = energyTransferMode
            ?: throw IllegalStateException("ChargeParameterDiscovery before ServiceDiscovery")
        return if (mode == PowerMode.Dc)
            ChargeParameterDiscoveryReqType(null, transferMode,
                DC_EVChargeParameterType(
                    departureTime = null, dC_EVStatus = evStatus(),
                    eVMaximumCurrentLimit = amp(200), eVMaximumPowerLimit = null,
                    eVMaximumVoltageLimit = volt(500),
                    // Both optional and both absent until there is a pack to describe. -2 DC is the
                    // one place a car states its capacity outright, which is what a station needs to
                    // turn "40 kWh wanted" into a schedule rather than a number.
                    eVEnergyCapacity = battery?.let { wattHours(it.capacityWh) },
                    eVEnergyRequest  = battery?.let { wattHours(it.energyNeededWh) },
                    fullSOC = 100, bulkSOC = 80))
        else
            ChargeParameterDiscoveryReqType(null, transferMode,
                AC_EVChargeParameterType(
                    departureTime = null,
                    // EAmount is -2 AC's only energy field, and it is the request: how much this
                    // session wants, not what the pack holds. 22 kWh when nothing asked. (C# also
                    // subtracts what a paused predecessor charged, [V2G2-743]; pause/resume is not
                    // ported, so there is nothing here to subtract.)
                    eAmount = battery?.let { wattHours(it.energyNeededWh) }
                        ?: PhysicalValueType(0, UnitSymbol.Wh, 22_000),
                    eVMaxVoltage = volt(400), eVMaxCurrent = amp(32), eVMinCurrent = amp(6)))
    }

    /**
     * Watt-hours as a -2 physical value, rounded to the whole watt-hour the wire and the meter both
     * count in, and scaled to whatever multiplier makes it fit the `xs:short` the field is — a 60 kWh
     * pack does not fit one otherwise.
     *
     * A local port of C#'s `PhysicalValue.Of`, which is the only caller's worth of it these ports
     * need: everything else on this side of the wire is a constant small enough to write out. C#'s
     * *negative* multipliers are not here either, and cannot be reached — the rounding above clears
     * the fractional part they exist for.
     */
    private fun wattHours(wattHours: Double): PhysicalValueType {
        // Half-to-even, matching C#'s bare Math.Round — the meter's own rule is away-from-zero, and
        // the two part company on exactly the .5 case.
        val amount = Math.rint(wattHours)
        var multiplier = 0
        var scaled = amount
        while (multiplier < 3 && (scaled > Short.MAX_VALUE || scaled < Short.MIN_VALUE)) {
            multiplier++
            scaled = amount / Math.pow(10.0, multiplier.toDouble())
        }
        require(scaled <= Short.MAX_VALUE && scaled >= Short.MIN_VALUE) {
            "PhysicalValue $amount does not fit a multiplier in [-3, 3] (|value| would exceed ${Short.MAX_VALUE})."
        }
        return PhysicalValueType(multiplier.toByte(), UnitSymbol.Wh, Math.rint(scaled).toInt().toShort())
    }

    private fun currentDemand() =
        CurrentDemandReqType(
            dC_EVStatus = evStatus(), eVTargetCurrent = amp(120),
            eVMaximumVoltageLimit = null, eVMaximumCurrentLimit = null, eVMaximumPowerLimit = null,
            bulkChargingComplete = null, chargingComplete = false,
            remainingTimeToFullSoC = null, remainingTimeToBulkSoC = null,
            eVTargetVoltage = volt(400))

    /**
     * The power this EV committed to in its ChargingProfile — its own view of an AC session, since
     * -2 puts no power on the wire in either direction.
     *
     * The first entry, because the later ones start at offsets a three-iteration charge loop never
     * reaches. Zero without a profile: a session that never agreed one has no committed power to
     * count, and inventing one would put a number on screen that nothing in the session supports.
     */
    private fun committedPowerW(): Double =
        chargingProfile?.profileEntry?.firstOrNull()?.chargingProfileEntryMaxPower?.let(::amount) ?: 0.0

    /** A PhysicalValue as a plain number: value x 10^multiplier. */
    private fun amount(v: PhysicalValueType): Double = v.value.toDouble() * Math.pow(10.0, v.multiplier.toDouble())

    private fun watts(volts: PhysicalValueType, amperes: PhysicalValueType): Double =
        amount(volts) * amount(amperes)

    /**
     * The DC status this car repeats in every request of the DC sequence — and, with a pack, the one
     * field in -2 that *moves* during a session: `EVRESSSOC` is the present state of charge, so a
     * station watching it sees the battery fill.
     *
     * A flat 50 % without one, which is what a car with no pack still sends. Worth naming because it
     * is the only per-iteration reading -2 asks the vehicle for: -2 gives a DC car no field for a
     * measured power, so this percentage is the whole of what the station learns about the vehicle's
     * own state while charging.
     */
    private fun evStatus() = DC_EVStatusType(
        eVReady     = true,
        eVErrorCode = DC_EVErrorCode.NO_ERROR,
        eVRESSSOC   = battery?.let { Math.rint(it.soC).coerceIn(0.0, 100.0).toInt().toByte() } ?: 50)
    private fun volt(v: Short) = PhysicalValueType(0, UnitSymbol.V, v)
    private fun amp(a: Short)  = PhysicalValueType(0, UnitSymbol.A, a)
}
