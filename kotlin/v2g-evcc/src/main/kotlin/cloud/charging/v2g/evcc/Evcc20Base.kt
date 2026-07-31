package cloud.charging.v2g.evcc

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
 * **Plug & Charge, contract provisioning (CertificateInstallation), and price-schedule signature
 * verification.** All three are signature work; the corpus is EIM and a randomised ECDSA signature
 * cannot be compared byte for byte, so porting them now would mean writing crypto against no oracle.
 * Named here rather than silently absent, exactly as in [Evcc2].
 *
 * **Dynamic control mode.** This EVCC drives Scheduled mode, as the C# original does — it asks for a
 * Scheduled parameter set and sends a Scheduled ScheduleExchange. [V2G20-1600] requires a response's
 * control mode to match the request's, so the two halves are one decision, not two.
 */
abstract class Evcc20Base(
    private val stream: V2GTPStream,
    clock: () -> ULong,
    protected val pollDelay: (Long) -> Unit,
) {

    protected companion object {
        const val POLL_INTERVAL_MS = 50L
        const val CHARGE_CYCLES    = 3

        // ISO 15118-20 energy-transfer service ids (Table 204): AC=1, DC=2, AC_BPT=5, DC_BPT=6.
        val DC_SERVICE_IDS: List<UShort> = listOf(2u, 6u)
        val AC_SERVICE_IDS: List<UShort> = listOf(1u, 5u)
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
    var authorizationMode: String = "eim"
        private set


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

        while (true) {
            val res = exchange<AuthorizationRes>(MessageSet.Iso20CommonMessages, buildAuthorizationReq())
            if (res.eVSEProcessing == Processing.Finished) break
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
                    dynamic_SEReqControlMode = null,
                    scheduled_SEReqControlMode = Scheduled_SEReqControlModeType(null, null, null, null, null))))
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
        exchanges++
        return reply
    }

    private inline fun <reified T : Any> exchange(expectedSet: MessageSet, payload: ByteArray): T {
        val (set, message) = exchangeRaw(expectedSet, payload)
        return expect(set, message, expectedSet)
    }


    /** The first advertised service whose id matches this EVCC's mode (DC → 2/6, AC → 1/5), else the
     *  first offered — a simplified station may advertise a single generic id. */
    private fun selectEnergyTransferService(res: ServiceDiscoveryRes): UShort {
        val offered = res.energyTransferServiceList.service
        if (offered.isEmpty())
            throw SessionAborted("ServiceDiscovery: the station advertised no energy-transfer service.")
        return (offered.firstOrNull { it.serviceID in preferredEnergyServiceIds } ?: offered[0]).serviceID
    }

    /** Prefers a Scheduled control-mode set (ControlMode=1, matching the Scheduled ScheduleExchange
     *  this EVCC drives), else the first offered. */
    private fun selectParameterSet(res: ServiceDetailRes): UShort {
        val sets = res.serviceParameterList.parameterSet
        if (sets.isEmpty())
            throw SessionAborted("ServiceDetail: the station advertised no parameter set.")
        val scheduled = sets.firstOrNull { set ->
            set.parameter.any { it.name == "ControlMode" && it.intValue == 1 }
        }
        return (scheduled ?: sets[0]).parameterSetID
    }

    /** The Scheduled-mode EVPowerProfile that PowerDelivery(Start) must carry: the first schedule
     *  tuple the station returned, and one echoed power-schedule entry. Falls back to tuple 1 if the
     *  station returned no Scheduled control mode. */
    private fun buildEvPowerProfile(scheduleRes: ScheduleExchangeRes): EVPowerProfileType {

        val tupleId = scheduleRes.scheduled_SEResControlMode?.scheduleTuple?.firstOrNull()?.scheduleTupleID ?: 1u

        return EVPowerProfileType(
            timeAnchor = 0u,
            dynamic_EVPPTControlMode = null,
            // PowerToleranceAcceptance is schema-optional but Josev's model requires it — its SECC
            // rejects an absent one, and a live run needed it set.
            scheduled_EVPPTControlMode = Scheduled_EVPPTControlModeType(
                tupleId, PowerToleranceAcceptance.PowerToleranceConfirmed),
            eVPowerProfileEntries = EVPowerProfileEntryListType(listOf(
                // one 1-hour entry at 10 kW (Power = 10 × 10³ W)
                PowerScheduleEntryType(duration = 3600u, power = RationalNumberType(3, 10),
                                       power_L2 = null, power_L3 = null))))
    }
}
