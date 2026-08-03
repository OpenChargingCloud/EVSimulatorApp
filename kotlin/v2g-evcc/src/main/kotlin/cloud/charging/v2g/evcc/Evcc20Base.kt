package cloud.charging.v2g.evcc

import cloud.charging.v2g.metering.EvMeter
import cloud.charging.v2g.iso20.common.*
import cloud.charging.v2g.tp.MessageSet

/**
 * Asserts that an exchange came back as the message it was supposed to. Kotlin cannot make a
 * `reified` helper `protected`, so this is a module-level function the subclasses share — the same
 * role C#'s `private static Expect<T>` plays, minus the duplication it needed in each subclass.
 */
internal inline fun <reified T : Any> expect(actualSet: MessageSet, message: Any,
                                             expectedSet: MessageSet): T {
    if (actualSet != expectedSet || message !is T)
        throw SessionAborted(
            "expected a ${T::class.simpleName} on $expectedSet, " +
            "got ${message::class.simpleName} on $actualSet.")
    return message
}


/**
 * The EVCC side of an ISO 15118-20 session, shared between AC and DC. It drives the CommonMessages
 * phases directly and calls the abstract hooks below for the diverging middle, which
 * [Evcc20Ac]/[Evcc20Dc] implement — they are the ones that know which codec and which concrete
 * request types their energy-transfer mode uses.
 *
 * A port of the C# `Evcc20Base`, checked the same way [Evcc2] is: `Evcc20TraceTest` replays the
 * recorded -20 sessions and requires byte-identical requests. See `SessionTrace.cs` for what that
 * does and does not prove.
 *
 * ## What is not here yet
 *
 * **Contract provisioning (CertificateInstallation) and price-schedule signature verification.**
 * Both are signature work with no recorded oracle yet; named here rather than silently absent,
 * exactly as in [Evcc2]. (Plug & Charge itself *is* here, held to the signed traces; Dynamic
 * control mode is here too, held to the `iso20-*-eim-dynamic` traces.)
 */
abstract class Evcc20Base(
    private val stream: V2GTPStream,
    clock: () -> ULong,
    protected val pollDelay: (Long) -> Unit,
) {

    /** How long a phase may keep answering `EVSEProcessing = Ongoing` before the session ends —
     *  60 s, ISO 15118's EVCC ongoing timeout. See [OngoingGuard] for the live run that required it. */
    var ongoingTimeoutMillis: Long = 60_000


    protected companion object {
        const val POLL_INTERVAL_MS = 50L
        const val CHARGE_CYCLES    = 3

        // ISO 15118-20 energy-transfer service ids (Table 204): AC=1, DC=2, AC_BPT=5, DC_BPT=6,
        // MCS=8, MCS_BPT=9. MCS is the DC message set under different ids, so it is *drivable* by a
        // DC EVCC even when it is not what that EVCC would ask for first — which is the difference
        // the two pairs of lists carry.
        val DC_SERVICE_IDS: List<UShort> = listOf(2u, 6u)
        val AC_SERVICE_IDS: List<UShort> = listOf(1u, 5u)
        val DC_DRIVABLE_IDS: List<UShort> = listOf(2u, 6u, 8u, 9u)
        val AC_DRIVABLE_IDS: List<UShort> = listOf(1u, 5u)
    }

    protected val sessionCtx = SessionContext(clock)

    /** How many request/response exchanges this session ran. */
    var exchanges: Int = 0
        private set

    /**
     * The energy-transfer service actually negotiated (Table 204); 0 before that phase. Exposed
     * because which service a session settled on is otherwise invisible from outside — it is what
     * distinguishes an MCS session from a DC one, the two being identical on the wire otherwise.
     */
    var selectedEnergyServiceId: UShort = 0u
        private set

    /** How the session ends: Terminate (default) or Pause. */
    var stopMode: ChargingSession = ChargingSession.Terminate

    /** The station's SessionSetup verdict. */
    var sessionSetupCode: ResponseCode? = null
        private set

    /** The session id in effect, station-assigned. */
    val sessionId: ByteArray get() = sessionCtx.sessionId

    /** Contract credentials. When set and the station offers PnC with a challenge, the session
     *  authorizes with a signed AuthorizationReq instead of EIM. */
    var pnc: PncEvccOptions? = null

    /** How this session authorized: `"eim"`, or `"pnc-signed"`. */
    /**
     * The vehicle's own energy counter — what this EV thinks it took, kept independently of what the
     * station reports (`docs/CONCEPT.md` §4.2/§4.3).
     *
     * On the base rather than per set: the counter is the vehicle's, and AC and DC differ only in
     * what a sample is worth. Each subclass takes its own sample in [runChargeLoopIteration], where
     * it knows which field carries the EV's view.
     */
    val meter: EvMeter = EvMeter()

    var authorizationMode: String = "eim"
        private set

    /**
     * Drive the session in **Dynamic** control mode (ControlMode = 2) instead of Scheduled.
     *
     * The mode is a property of the whole session, not of one message — it touches the parameter
     * set selected out of `ServiceDetailRes`, `ScheduleExchangeReq`'s control-mode arm, the
     * `EVPowerProfile` in PowerDelivery(Start), and the charge loop's request arm ([Evcc20Dc]/
     * [Evcc20Ac]). Answering in kind is [V2G20-1600]; asking in kind is the same rule read from the
     * other end. The substantive difference is who plans: in Scheduled mode the EV picks a schedule
     * tuple and commits to it, in Dynamic mode it states energy needs and a departure time and lets
     * the station steer.
     *
     * Held to the `iso20-dc-eim-dynamic` / `iso20-ac-eim-dynamic` traces — recorded the day the C#
     * EVCC learned the mode (2026-08-03), precisely so the ports could not claim it unchecked.
     */
    var preferDynamicControlMode: Boolean = false

    /** When the car leaves, as a -20 `DepartureTime` (seconds from the session's time anchor).
     *  Dynamic mode only: it is the deadline the station schedules against. */
    var departureTime: UInt = 3600u


    /**
     * Builds the AuthorizationReq encoder once, so the signature is computed once.
     *
     * The header is rebuilt on every call and the signature is not: -20 timestamps every header, so
     * a poll loop must re-render the header, while re-signing per poll would burn entropy over a
     * challenge that has not changed. The C# original makes the same split, and getting it backwards
     * is invisible until something checks the bytes.
     */
    private fun buildAuthorizationReq(authSetup: AuthorizationSetupRes): () -> ByteArray {

        val credentials = pnc
        val pncSetup    = authSetup.pnC_ASResAuthorizationMode

        if (credentials != null &&
            authSetup.authorizationServices.contains(Authorization.PnC) &&
            pncSetup != null) {

            val pncMode = PnC_AReqAuthorizationModeType(
                id = "id1",
                genChallenge = pncSetup.genChallenge,
                contractCertificateChain = ContractCertificateChainType(
                    credentials.contractCertificate,
                    SubCertificatesType(credentials.subCertificates)))

            val signature = XmlDsigInterop.sign20(
                "id1", CommonMessagesCodec.encodeFragment_PnC_AReqAuthorizationMode(pncMode),
                credentials.contractKey)

            authorizationMode = "pnc-signed"

            return {
                CommonMessagesCodec.encode(AuthorizationReq(
                    sessionCtx.toCommonHeader().copy(signature = signature),
                    Authorization.PnC, null, pncMode))
            }
        }

        // EIM is what is left, and it too has to be on offer: a station that advertises PnC only is
        // saying it cannot authorize this car, and hearing that at AuthorizationSetup is better than
        // hearing FAILED at AuthorizationReq.
        if (!authSetup.authorizationServices.contains(Authorization.EIM))
            throw SessionAborted(
                "AuthorizationSetup: the station offers no EIM authorization " +
                "(offered: ${authSetup.authorizationServices.joinToString(", ")})" +
                (if (credentials == null) " and this EVCC has no contract certificate." else "."))

        return {
            CommonMessagesCodec.encode(AuthorizationReq(
                sessionCtx.toCommonHeader(), Authorization.EIM, EIM_AReqAuthorizationModeType(), null))
        }
    }


    /** Charge-parameter discovery. Runs once, not polled: -20's CPD response carries no
     *  EVSEProcessing field to poll on. */
    protected abstract fun runChargeParameterDiscovery()

    /** DC: CableCheck + PreCharge. AC: nothing. */
    protected abstract fun runPreChargeSequence()

    /** One charge-loop request/response; the base class loops this a fixed number of times. */
    protected abstract fun runChargeLoopIteration()

    /** DC: WeldingDetection. AC: nothing. */
    protected abstract fun runPostChargeSequence()

    /** Which mode this EVCC drives — picks the matching service from the station's catalogue. */
    protected abstract val energyMode: PowerMode

    /** Energy-transfer service ids this EVCC accepts, best first. Open so an MCS vehicle can ask for
     *  the megawatt services instead. */
    protected open val preferredEnergyServiceIds: List<UShort>
        get() = if (energyMode == PowerMode.Dc) DC_SERVICE_IDS else AC_SERVICE_IDS

    /** Every service id whose messages this EVCC can actually speak — the ones on its own message
     *  set. Wider than [preferredEnergyServiceIds] on purpose: a megawatt truck at an ordinary DC
     *  charger should take the DC service rather than refuse, and a DC car at an AC-only station has
     *  nothing to take. */
    protected open val drivableEnergyServiceIds: List<UShort>
        get() = if (energyMode == PowerMode.Dc) DC_DRIVABLE_IDS else AC_DRIVABLE_IDS


    fun run() {

        val setupRes = exchange<SessionSetupRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(SessionSetupReq(sessionCtx.toCommonHeader(), "EVCC01")))
        sessionSetupCode = setupRes.responseCode

        // Adopt the station-assigned SessionID: every subsequent request header must carry it, not
        // the all-zero id SessionSetup opens with (§7.9.2.4). A live Josev run caught this — its
        // SECC strictly rejects a mismatched session id where our loopback one did not.
        sessionCtx.sessionId = setupRes.header.sessionID

        val authSetup = exchange<AuthorizationSetupRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(AuthorizationSetupReq(sessionCtx.toCommonHeader())))

        // Plug & Charge only if we have credentials AND the station offers it with a challenge;
        // anything else falls back to EIM. Built once — the challenge does not change across polls,
        // so re-signing per poll would only burn entropy.
        val buildAuthorizationReq = buildAuthorizationReq(authSetup)

        val authGuard = OngoingGuard("Authorization", ongoingTimeoutMillis)
        while (true) {
            val res = exchange<AuthorizationRes>(MessageSet.Iso20CommonMessages, buildAuthorizationReq())
            if (res.eVSEProcessing == Processing.Finished) break
            authGuard.tick()
            pollDelay(POLL_INTERVAL_MS)
        }

        // Service negotiation is dynamic: select the service and parameter set the station actually
        // advertises rather than assuming fixed ids. A live Josev run caught the old hardcoded
        // ServiceID=1/ParameterSetID=1 — its DC catalogue offers neither, and our loopback SECC
        // happened to advertise exactly those, which masked it.
        val discovery = exchange<ServiceDiscoveryRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(ServiceDiscoveryReq(sessionCtx.toCommonHeader(), null)))
        val serviceId = selectEnergyTransferService(discovery)
        selectedEnergyServiceId = serviceId

        val detail = exchange<ServiceDetailRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(ServiceDetailReq(sessionCtx.toCommonHeader(), serviceId)))
        val parameterSetId = selectParameterSet(detail)

        exchange<ServiceSelectionRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(ServiceSelectionReq(sessionCtx.toCommonHeader(),
                SelectedServiceType(serviceId, parameterSetId), null)))

        runChargeParameterDiscovery()

        // MaximumSupportingPoints is schema-bounded to [12, 1024] (the encoder biases by 12); a
        // smaller value underflows on the wire. A live Josev run rejected the earlier 1, which our
        // more lenient SECC had accepted.
        var scheduleRes: ScheduleExchangeRes
        while (true) {
            scheduleRes = exchange(MessageSet.Iso20CommonMessages,
                CommonMessagesCodec.encode(ScheduleExchangeReq(
                    sessionCtx.toCommonHeader(), maximumSupportingPoints = 12u,
                    dynamic_SEReqControlMode =
                        if (preferDynamicControlMode) dynamicScheduleRequest() else null,
                    scheduled_SEReqControlMode =
                        if (preferDynamicControlMode) null
                        else Scheduled_SEReqControlModeType(null, null, null, null, null))))
            if (scheduleRes.eVSEProcessing == Processing.Finished) break
            pollDelay(POLL_INTERVAL_MS)
        }

        runPreChargeSequence()

        // PowerDelivery(Start) must carry an EVPowerProfile referencing a schedule tuple the station
        // offered (§7.9.2.4). A live Josev run rejected the earlier absent profile; ours did not.
        val evPowerProfile = buildEvPowerProfile(scheduleRes)
        exchange<PowerDeliveryRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(PowerDeliveryReq(sessionCtx.toCommonHeader(),
                Processing.Finished, ChargeProgress.Start, evPowerProfile, null)))

        repeat(CHARGE_CYCLES) {
            runChargeLoopIteration()
            pollDelay(POLL_INTERVAL_MS)
        }

        exchange<PowerDeliveryRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(PowerDeliveryReq(sessionCtx.toCommonHeader(),
                Processing.Finished, ChargeProgress.Stop, null, null)))

        runPostChargeSequence()

        exchange<SessionStopRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(SessionStopReq(sessionCtx.toCommonHeader(), stopMode, null, null)))
    }


    /**
     * Sends one already-encoded request and awaits its reply. The undiscriminated pair, because the
     * AC/DC subclasses need their own result types — [expect] is what narrows it.
     */
    protected fun exchangeRaw(expectedSet: MessageSet, payload: ByteArray): Pair<MessageSet, Any> {
        stream.writeFrame(expectedSet, payload)
        val reply = stream.readFrame()
        refuseOnFailure(reply.second)
        exchanges++
        return reply
    }

    /**
     * Ends the session when the station answers with a code from the `FAILED` family.
     *
     * **Found live, not by reasoning.** Until 2026-08-01 no -20 EVCC in this repository looked at a
     * response code at all — `expect` checks the message set and the type, and the cable-check loop
     * watched only `evseProcessing`. eVDriveFlow answered `DC_CableCheckRes` with `FAILED` and the C#
     * car went on to PreCharge, PowerDelivery and into the charge loop; this port had the same hole,
     * and the trace corpus could not show it, because our own SECC never says FAILED.
     *
     * `OK*` and `WARNING*` continue — a warning is explicitly the code for "something is off and the
     * session goes on" — and `FAILED*` terminates. The comparison is on `ordinal` because the
     * enumeration is ordered by family in the schema (OK, then WARNING, then FAILED), which
     * `Evcc20FailureTest.theResponseCodeFamiliesAreContiguousAndOrdered` pins.
     *
     * Aborts rather than sending SessionStop: a FAILED response is the station saying it is done, and
     * a further message invites a second error on a session that already has one.
     */
    internal fun refuseOnFailure(message: Any) {   // internal: exercised directly by Evcc20FailureTest

        val failure = when (message) {
            is V2GResponseType ->
                message.responseCode.takeIf { it.ordinal >= ResponseCode.FAILED.ordinal }?.name
            is cloud.charging.v2g.iso20.ac.V2GResponseType ->
                message.responseCode.takeIf {
                    it.ordinal >= cloud.charging.v2g.iso20.ac.ResponseCode.FAILED.ordinal }?.name
            is cloud.charging.v2g.iso20.dc.V2GResponseType ->
                message.responseCode.takeIf {
                    it.ordinal >= cloud.charging.v2g.iso20.dc.ResponseCode.FAILED.ordinal }?.name
            else -> null
        }

        if (failure != null)
            throw SessionAborted(
                "the station answered ${message::class.simpleName} with $failure; the session ends here.")

    }

    private inline fun <reified T : Any> exchange(expectedSet: MessageSet, payload: ByteArray): T {
        val (set, message) = exchangeRaw(expectedSet, payload)
        return expect(set, message, expectedSet)
    }


    /** Picks the energy-transfer service to select from the station's advertised list: the best one
     *  this EVCC asks for, else any other it can actually drive, else a refusal.
     *
     *  The old fallback was `offered[0]`, which for a DC car at an AC-only station selects the AC
     *  service and then sends the next request on the DC set — refused two exchanges later, for a
     *  reason that no longer names the cause. Falling back *within* the message set keeps the case
     *  this is really for (a megawatt truck at an ordinary DC charger) and drops the one it never
     *  was (found in the C# sweep of 2026-08-03). */
    private fun selectEnergyTransferService(res: ServiceDiscoveryRes): UShort {

        val offered = res.energyTransferServiceList.service
        if (offered.isEmpty())
            throw SessionAborted("ServiceDiscovery: the station advertised no energy-transfer service.")

        val match = offered.firstOrNull { it.serviceID in preferredEnergyServiceIds }
                 ?: offered.firstOrNull { it.serviceID in drivableEnergyServiceIds }

        return match?.serviceID ?: throw SessionAborted(
            "ServiceDiscovery: the station offers no ${if (energyMode == PowerMode.Dc) "DC" else "AC"} " +
            "energy-transfer service (wanted ${preferredEnergyServiceIds.joinToString("/")}, " +
            "offered ${offered.joinToString(", ") { it.serviceID.toString() }}).")
    }

    /** Picks the parameter set whose `ControlMode` matches the mode this EVCC is about to drive
     *  (1 = Scheduled, 2 = Dynamic), else the first offered. A Dynamic EV at a Scheduled-only
     *  station is refused by name instead: the selected set is what the station answers in kind
     *  against for the rest of the session, so a silent fallback would negotiate one mode and then
     *  ask in the other. */
    private fun selectParameterSet(res: ServiceDetailRes): UShort {

        val sets = res.serviceParameterList.parameterSet
        if (sets.isEmpty())
            throw SessionAborted("ServiceDetail: the station advertised no parameter set.")

        val wanted = if (preferDynamicControlMode) 2 else 1
        val match  = sets.firstOrNull { set ->
            set.parameter.any { it.name == "ControlMode" && it.intValue == wanted }
        }

        if (match == null && preferDynamicControlMode)
            throw SessionAborted("ServiceDetail: Dynamic control mode was requested, but the station " +
                                 "offers no parameter set with ControlMode = 2.")

        return (match ?: sets[0]).parameterSetID
    }

    /** The Dynamic-mode ScheduleExchange request: a departure time and what the battery needs,
     *  instead of a schedule to choose from. The three energy fields are **mandatory** in this arm
     *  (they are optional in the Scheduled one), which is the schema saying the same thing: a
     *  station can only steer if it knows the target. */
    private fun dynamicScheduleRequest() =
        Dynamic_SEReqControlModeType(
            departureTime            = departureTime,
            minimumSOC               = 30,
            targetSOC                = 80,
            eVTargetEnergyRequest    = RationalNumberType(3, 30),   // 30 kWh
            eVMaximumEnergyRequest   = RationalNumberType(3, 60),   // 60 kWh
            eVMinimumEnergyRequest   = RationalNumberType(3, 10),   // 10 kWh
            eVMaximumV2XEnergyRequest = null,
            eVMinimumV2XEnergyRequest = null)

    /** The EVPowerProfile that PowerDelivery(Start) must carry. Scheduled mode selects the first
     *  schedule tuple the station returned and echoes one power-schedule entry (falling back to
     *  tuple 1 if the station returned no Scheduled control mode); Dynamic mode has no tuple to
     *  point at, so its control-mode element is empty — the profile is then only the EV's own power
     *  curve. */
    private fun buildEvPowerProfile(scheduleRes: ScheduleExchangeRes): EVPowerProfileType {

        val tupleId = scheduleRes.scheduled_SEResControlMode?.scheduleTuple?.firstOrNull()?.scheduleTupleID ?: 1u

        return EVPowerProfileType(
            timeAnchor = 0u,
            dynamic_EVPPTControlMode =
                if (preferDynamicControlMode) Dynamic_EVPPTControlModeType() else null,
            // PowerToleranceAcceptance is schema-optional but Josev's model requires it — its SECC
            // rejects an absent one, and a live run needed it set.
            scheduled_EVPPTControlMode =
                if (preferDynamicControlMode) null
                else Scheduled_EVPPTControlModeType(
                    tupleId, PowerToleranceAcceptance.PowerToleranceConfirmed),
            eVPowerProfileEntries = EVPowerProfileEntryListType(listOf(
                // one 1-hour entry at 10 kW (Power = 10 × 10³ W)
                PowerScheduleEntryType(duration = 3600u, power = RationalNumberType(3, 10),
                                       power_L2 = null, power_L3 = null))))
    }
}
