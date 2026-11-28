package cloud.charging.v2g.evcc

import java.security.PrivateKey

import cloud.charging.v2g.metering.EvMeter
import cloud.charging.v2g.metering.MeteringDeviation
import cloud.charging.v2g.metering.MeteringDeviationLimit
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
 * ## What each part is held to, since it is not all the same kind of evidence
 *
 * Plug & Charge, contract provisioning, service renegotiation and **resume** are held to recorded
 * sessions, because each is a *sequence* and a sequence is only ever on the wire. Price-schedule
 * signature verification is held to `PriceSchedule.signature.vectors.json` instead, and the
 * provisioning key transport to `Contract.provisioning.vectors.json`, because a verdict and an
 * unwrapped key never travel at all.
 *
 * The resume pair is the sharpest case of the first kind. A resumed session is defined by what it
 * does **not** send — no AuthorizationSetup, no AuthorizationReq, no service discovery, detail or
 * selection — and no corpus of frames can state an absence.
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

        /** Whether [serviceId] is a bidirectional (BPT) service: AC_BPT, DC_BPT or MCS_BPT. A port of
         *  the C# `EnergyTransferService.IsBidirectional`; the EVCC asks it to decide which
         *  charge-parameter and control-mode types to send. */
        fun isBidirectional(serviceId: UShort): Boolean = serviceId == 5u.toUShort() ||
                                                          serviceId == 6u.toUShort() ||
                                                          serviceId == 9u.toUShort()
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

    /**
     * Whether the negotiated service is bidirectional, and so whether [Evcc20Ac] / [Evcc20Dc] must
     * build the `BPT_*` charge-parameter and control-mode types instead of the charge-only ones.
     *
     * Derived from [selectedEnergyServiceId], not a knob of its own: -20 carries the direction in
     * the type, and a station enforces that the selected service and every later request agree.
     * EVerest refused a charge-only `DC_ChargeParameterDiscoveryReq` under service 9 with
     * `FAILED_WrongChargeParameter` (ISO15118ConformanceTests,
     * `docs/interop-runs/2026-08-05-everest-mcs-bpt/`). Until 2026-11-28 this port could select 5 or
     * 6 from a station offering only those, and then sent exactly that.
     */
    protected val bidirectionalService: Boolean
        get() = isBidirectional(selectedEnergyServiceId)

    /**
     * Ask for the **bidirectional** entry of whatever catalogue this vehicle wants: AC_BPT (5) ahead
     * of AC (1), DC_BPT (6) ahead of DC (2), MCS_BPT (9) ahead of MCS (8). Off by default, which is
     * what every recorded session before `iso20-*-eim-bpt*` contains.
     *
     * Selecting a BPT service is itself what makes the session bidirectional; see
     * [bidirectionalService]. A station advertising no bidirectional service is unaffected. Held to the
     * `iso20-dc-eim-bpt`, `iso20-ac-eim-bpt` and their `-dynamic` twins, recorded with the C# EVCC's
     * `PreferBidirectionalService` on 2026-11-28.
     */
    var preferBidirectionalService: Boolean = false

    /**
     * The `SupportedServiceIDs` filter this car puts in `ServiceDiscoveryReq` (Table 38 of
     * [V2G20-1248]). Null (the default) omits the element, which asks the station for everything and
     * is what every other recorded session contains. Held to `iso20-dc-eim-filter`.
     *
     * The C# car gained it for eVDriveFlow's station, which dereferences the optional element
     * unconditionally and so ended every session without it at the fifth message.
     */
    var supportedServiceIds: List<UShort>? = null

    /** How the session ends: Terminate (default) or Pause. */
    var stopMode: ChargingSession = ChargingSession.Terminate

    /** The station's SessionSetup verdict. */
    /**
     * The §7.9.2.5 verdict over a signed `AbsolutePriceSchedule`; null when the offer carried none —
     * which is not a failure, see [Iso20PriceScheduleCheck].
     */
    var tariff: Iso20TariffResult? = null
        private set

    /**
     * The eMSP's public key, when the app has one. Without it the digest half is still checked and
     * reported; the ECDSA half is not attempted.
     */
    var tariffVerifyKey: java.security.PublicKey? = null

    var sessionSetupCode: ResponseCode? = null
        private set

    /** The session id in effect, station-assigned. */
    val sessionId: ByteArray get() = sessionCtx.sessionId

    /** Contract credentials. When set and the station offers PnC with a challenge, the session
     *  authorizes with a signed AuthorizationReq instead of EIM. */
    var pnc: PncEvccOptions? = null

    /** OEM-provisioning credentials. When set — and the station announces
     *  `CertificateInstallationService` — the EVCC runs the contract-provisioning exchange before
     *  authorization. Null (the default) skips it. */
    var certInstallRequest: CertInstallEvccOptions? = null

    /**
     * A paused predecessor's session id: the opening `SessionSetupReq` carries it so the SECC rejoins
     * the old session instead of assigning a new one.
     *
     * The rejoin is where -20 stops resembling -2. A -2 resume repeats the whole opening sequence;
     * this one repeats **none** of it — no AuthorizationSetup, no AuthorizationReq, no service
     * discovery, detail or selection. The session opens at ChargeParameterDiscovery.
     */
    var resumeSessionId: ByteArray? = null

    /**
     * The paused session's binding to the *station* — the car's half of the same mechanism the SECC
     * applies to the car, and its obligation rather than a courtesy: an EV that resumes has to
     * establish it is still talking to the SECC it paused with.
     *
     * Asymmetric with the station's, on purpose. Where the station cannot verify a resume it must
     * refuse it, because failing open there hands one EV's authorization to another. Here failing open
     * only risks the car continuing at a station it cannot confirm — a risk it bears itself — so an
     * unverifiable resume proceeds and is recorded in [resumedStationVerified], while an actual
     * *mismatch* ends the session. The distinction matters to a harness that deliberately speaks plain
     * TCP, where no binding can exist on either side.
     */
    var resumeBinding: ByteArray? = null

    /** The energy-transfer service the paused session settled on; a resumed session does not repeat
     *  service negotiation and would otherwise forget it. */
    var resumeEnergyServiceId: UShort = 0u

    /** The station's TLS leaf certificate (DER). Settable for callers that drive the machine over
     *  something that is not an authenticated TLS stream — which is what the trace corpus is. */
    var seccLeafCertificate: ByteArray? = null

    /** The binding of the session in effect — keep it with [sessionId] for a resume. */
    var sessionBinding: ByteArray? = null
        private set

    /** Whether a resumed session was confirmed to be with the same station: `true` on a match, `null`
     *  when it could not be checked (no binding on one side or the other), and never `false` — a
     *  mismatch ends the session rather than being reported. */
    var resumedStationVerified: Boolean? = null
        private set

    /** Whether a resume was offered and the station started a fresh session instead. Not an error: the
     *  refusal is indistinguishable from a first visit by design, so this is the only place the car can
     *  say it asked. */
    var resumeRefused: Boolean = false
        private set

    /** Resume a paused predecessor — all three values at once; see [resumeBinding]. */
    fun resumeFrom(paused: ResumableSession?) {
        resumeSessionId       = paused?.sessionId
        resumeBinding         = paused?.binding
        resumeEnergyServiceId = paused?.energyServiceId ?: 0u
    }

    /** What this session hands on when it pauses. */
    val pausedSession: ResumableSession
        get() = ResumableSession(sessionId, sessionBinding, selectedEnergyServiceId)

    /** The contract certificate (DER) installed via CertificateInstallation, once recovered. */
    var installedContractCertificate: ByteArray? = null
        private set

    /** The unwrapped contract private key (P-521). GCM's tag check already refused a wrong one, so
     *  unlike -2 there is no certificate comparison standing behind this. */
    var installedContractKey: PrivateKey? = null
        private set

    /** The full verdict over the CertificateInstallationRes, once one has arrived. */
    var installedContractVerdict: Iso20ContractVerdict? = null
        private set

    /** Whether that response's CPS signature held — both halves of [installedContractVerdict]. */
    var installedContractSignatureOk: Boolean = false
        private set

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
    /**
     * Runs the -20 contract-provisioning exchange: sends the signed OEM provisioning chain (Id "id1",
     * Josev-interop signature form over the chain's EXI fragment), then judges the response's CPS
     * signature over `SignedInstallationData` and ECDH-unwraps the issued contract private key.
     *
     * One reference where -2 has four, and no certificate check behind the unwrap: -20 signs the whole
     * `SignedInstallationData` as a unit, and its AES-GCM tag refuses a wrong key outright. See
     * [Iso20ContractCheck] and [ContractProvisioning20].
     */
    private fun runCertificateInstallation(oem: CertInstallEvccOptions) {

        val chain = SignedCertificateChainType(
            "id1", oem.oemCertificate,
            if (oem.oemSubCertificates.isEmpty()) null else SubCertificatesType(oem.oemSubCertificates))

        val signature = XmlDsigInterop.sign20(
            "id1", CommonMessagesCodec.encodeFragment_OEMProvisioningCertificateChain(chain),
            oem.oemSignKey, oem.suite)

        val res = exchange<CertificateInstallationRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(CertificateInstallationReq(
                sessionCtx.toCommonHeader().copy(signature = signature),
                chain,
                ListOfRootCertificateIDsType(listOf(X509IssuerSerialType("CN=V2GRootCA (dev)", 1L))),
                maximumContractCertificateChains = 1u,
                prioritizedEMAIDs = null)))

        val verdict = Iso20ContractCheck.evaluate(res, res.header.signature)
        installedContractVerdict     = verdict
        installedContractSignatureOk = verdict.digestOk && verdict.signatureOk

        res.signedInstallationData.sECP521_EncryptedPrivateKey?.let { wrapped ->
            installedContractKey = ContractProvisioning20.recoverContractKey(
                oem.oemKeyAgreement, res.signedInstallationData.dHPublicKey, wrapped)
            installedContractCertificate = res.signedInstallationData.contractCertificateChain.certificate
        }
    }

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
                credentials.contractKey, credentials.suite)

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
    /**
     * ServiceDiscovery -> ServiceDetail -> ServiceSelection.
     *
     * Its own method because a **service renegotiation** re-enters the session exactly here
     * ([V2G20-1477]) — the station puts the phase back to ServiceDiscovery, not to the top.
     * Authorization has already happened and is emphatically not repeated: a car that re-authorized
     * mid-session would be telling the station it might be a different car.
     *
     * Service negotiation is dynamic: select the service and parameter set the station actually
     * advertises rather than assuming fixed ids. A live Josev run caught the old hardcoded
     * ServiceID=1/ParameterSetID=1 — its DC catalogue offers neither, and our loopback SECC happened
     * to advertise exactly those, which masked it.
     */
    private fun runServiceSelection() {

        val discovery = exchange<ServiceDiscoveryRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(ServiceDiscoveryReq(sessionCtx.toCommonHeader(),
                supportedServiceIds?.takeIf { it.isNotEmpty() }?.let(::ServiceIDListType))))
        val serviceId = selectEnergyTransferService(discovery)
        selectedEnergyServiceId = serviceId

        val detail = exchange<ServiceDetailRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(ServiceDetailReq(sessionCtx.toCommonHeader(), serviceId)))
        val parameterSetId = selectParameterSet(detail)

        exchange<ServiceSelectionRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(ServiceSelectionReq(sessionCtx.toCommonHeader(),
                SelectedServiceType(serviceId, parameterSetId), null)))
    }

    /** How many service renegotiations this session ran ([V2G20-1477]). */
    var renegotiations: Int = 0
        private set

    private var renegotiationRequested = false

    /**
     * Record that a charge-loop response asked for a **service renegotiation** — the station puts
     * `EvseNotification.ServiceRenegotiation` in its EVSEStatus ([V2G20-1477]).
     *
     * Called by the AC and DC loops: the EVSEStatus is a different generated type in each message set,
     * and this class imports neither. Acted on where the charging phase ends rather than here — a
     * renegotiation has to finish the iteration and open the contactor before it can go anywhere.
     */
    protected fun noteRenegotiationRequest(requested: Boolean) {
        if (requested) renegotiationRequested = true
    }

    /**
     * Whether this car answers a station's `MeteringConfirmation` notification with a signed
     * `MeteringConfirmationReq` — which [V2G20-1621] says it shall. True by default; false is the
     * fault switch for a station that has to cope with a car that stays silent.
     */
    var confirmMetering: Boolean = true

    private val _meteringConfirmations = mutableListOf<MeteringConfirmationSent>()

    /** Every `MeteringConfirmationReq` this car signed and sent, with the station's answer. */
    val meteringConfirmations: List<MeteringConfirmationSent> get() = _meteringConfirmations

    /**
     * How often a station asked for a confirmation this car could not give: without a contract key,
     * because the session was authorised by EIM ([V2G20-2704] says a station shall not ask then), or
     * without the `MeterInfo` [V2G20-1083] makes part of asking.
     */
    var meteringConfirmationsImpossible: Int = 0
        private set

    /** Called for each confirmation once the station has answered it. */
    var onMeteringConfirmation: ((MeteringConfirmationSent) -> Unit)? = null

    /**
     * When set, this car **ends the charging** once a station reading strays further from its own count
     * than the limit allows, and says why in the `SessionStopReq`: `EVTerminationCode`
     * [MeteringDeviation.TERMINATION_CODE] and the four figures in `EVTerminationExplanation`. Null, the
     * default, never stops over the meter. The C# counterpart is `Evcc20Base.StopOnMeteringDeviation`.
     *
     * It keeps confirming what the station asks it to confirm: [V2G20-1084] makes that a shall, and the
     * confirmation only says the reading was received.
     */
    var stopOnMeteringDeviation: MeteringDeviationLimit? = null

    /** The reading this car ended the charging over, or null if it did not. */
    var meteringDeviation: MeteringDeviation? = null
        private set

    /** Called once, when a reading crosses [stopOnMeteringDeviation]. */
    var onMeteringDeviation: ((MeteringDeviation) -> Unit)? = null

    /**
     * Compares a station reading with this car's own count. Called by the AC and DC loops **after** they
     * sampled [meter], so the pair describes one iteration; a bidirectional reading is compared net,
     * charged minus discharged.
     */
    protected fun noteStationReading(chargedWh: ULong, dischargedWh: ULong?) {

        val limit = stopOnMeteringDeviation ?: return
        if (meteringDeviation != null) return

        val stationNetWh = chargedWh.toDouble() - (dischargedWh?.toDouble() ?: 0.0)
        if (limit.exceededBy(stationNetWh, meter.energy)) {
            val deviation = MeteringDeviation(stationNetWh, meter.energy, limit.limitWh(stationNetWh))
            meteringDeviation = deviation
            onMeteringDeviation?.invoke(deviation)
        }
    }

    /** The schedule tuple PowerDelivery(Start) selected, or null in Dynamic control mode. */
    private var selectedScheduleTupleId: UInt? = null

    /**
     * Answers a station's `MeteringConfirmation` notification: signs what the charge-loop response just
     * carried and sends it on the side stream. Called by the AC and DC loops, which see the reading in
     * their own generated types and translate it ([MeteringConfirmation.toCommon]).
     *
     * Sent at once, after the iteration that asked and before the next charge-loop request. What is
     * signed is what the response carried: the SessionID, the reading, the receipt if there was one
     * ([V2G20-2236]) and the schedule tuple this car selected ([V2G20-776]). The signature acknowledges
     * receipt; it does not say the reading is right. A `FAILED` answer ends the session through
     * [refuseOnFailure], like any other response. The C# counterpart is `Evcc20Base.ConfirmMeteringAsync`.
     */
    protected fun confirmMetering(reading: MeterInfoType?, receipt: ReceiptType?) {

        if (!confirmMetering) return

        val credentials = pnc
        if (reading == null || credentials == null || authorizationMode != "pnc-signed") {
            meteringConfirmationsImpossible++
            return
        }

        val tuple = selectedScheduleTupleId
        val data  = SignedMeteringDataType(
            MeteringConfirmation.REFERENCE_ID, sessionCtx.sessionId, reading, receipt,
            dynamic_SMDTControlMode   = if (tuple == null) Dynamic_SMDTControlModeType() else null,
            scheduled_SMDTControlMode = tuple?.let { Scheduled_SMDTControlModeType(it) })

        val request = MeteringConfirmation.sign(
            sessionCtx.toCommonHeader(), data, credentials.contractKey, credentials.suite)

        val response = exchangeSideStream(MeteringConfirmation.PAYLOAD_TYPE, CommonMessagesCodec.encode(request))
                           as? MeteringConfirmationRes
            ?: throw SessionAborted("expected a MeteringConfirmationRes on the side stream.")

        val sent = MeteringConfirmationSent(reading.meterID, reading.chargedEnergyReadingWh, meter.energy,
                                            receipt != null, response.responseCode)
        _meteringConfirmations += sent
        onMeteringConfirmation?.invoke(sent)
    }

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

        resumeSessionId?.let { sessionCtx.sessionId = it }   // rejoin: SessionSetupReq carries it

        val setupRes = exchange<SessionSetupRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(SessionSetupReq(sessionCtx.toCommonHeader(), "EVCC01")))
        sessionSetupCode = setupRes.responseCode

        // Adopt the station-assigned SessionID: every subsequent request header must carry it, not
        // the all-zero id SessionSetup opens with (§7.9.2.4). A live Josev run caught this — its
        // SECC strictly rejects a mismatched session id where our loopback one did not.
        sessionCtx.sessionId = setupRes.header.sessionID

        if (setupRes.responseCode == ResponseCode.OK_OldSessionJoined) joinOldSession()
        else                                                          openNewSession()

        runChargingPhase()
    }

    /**
     * Picks up a session the station agreed to rejoin: nothing is negotiated, because nothing was
     * forgotten. The one thing the car still owes is the mirror of what the station did to it —
     * establishing that this is the same SECC.
     */
    private fun joinOldSession() {

        // A mismatch is not a warning: the paused session is purged and terminated, because if this is
        // a different station then something is wrong that continuing cannot improve.
        val presented = SessionBinding20.compute(sessionCtx.sessionId, seccLeafCertificate)
        val stored    = resumeBinding

        if (stored != null && stored.isNotEmpty() && presented != null && presented.isNotEmpty()) {
            if (!SessionBinding20.matches(stored, presented)) {
                resumeSessionId       = null
                resumeEnergyServiceId = 0u
                throw SessionAborted(
                    "Resumed session is with a different SECC than the one that paused it — " +
                    "session purged and terminated.")
            }
            resumedStationVerified = true
        }
        // else: nothing to check against on one side or the other. See `resumeBinding` for why the car
        // proceeds where the station would refuse.

        sessionBinding          = resumeBinding
        selectedEnergyServiceId = resumeEnergyServiceId
    }

    /**
     * Opens a fresh session: authorization setup, authorization, then the service negotiation that
     * settles which energy-transfer service and parameter set this session runs.
     *
     * Reached either because this is a new session, or because a resume was **refused** — in which case
     * everything the paused session carried, authorization included, is dropped first and the sequence
     * below runs from scratch, which is what the standard prescribes.
     */
    private fun openNewSession() {

        if (resumeSessionId != null) {
            resumeRefused          = true
            resumeSessionId        = null
            resumeBinding          = null
            resumeEnergyServiceId  = 0u
            resumedStationVerified = null
        }

        val authSetup = exchange<AuthorizationSetupRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(AuthorizationSetupReq(sessionCtx.toCommonHeader())))

        // A car that needs a contract asks for one here — before it authorizes, and before service
        // discovery. That position is the whole difference from -2, where provisioning is a
        // value-added service to be discovered and selected first, and it is the one thing about this
        // exchange that only a recorded session can state.
        certInstallRequest?.let { oem ->
            if (authSetup.certificateInstallationService) runCertificateInstallation(oem)
        }

        // Plug & Charge only if we have credentials AND the station offers it with a challenge;
        // anything else falls back to EIM. Built once — the challenge does not change across polls,
        // so re-signing per poll would only burn entropy.
        val buildAuthorizationReq = buildAuthorizationReq(authSetup)

        val authGuard = OngoingGuard("Authorization", ongoingTimeoutMillis)
        while (true) {
            val res = exchange<AuthorizationRes>(MessageSet.Iso20CommonMessages, buildAuthorizationReq())
            stopOnAuthorizationWarning(res)
            if (res.eVSEProcessing == Processing.Finished) break
            authGuard.tick()
            pollDelay(POLL_INTERVAL_MS)
        }

        runServiceSelection()

        // The binding this session would be resumed under, computed once the id is settled. Kept even
        // when the session goes on to terminate: whether it pauses is decided at the very end, and a
        // value computed only then would need the leaf and the id to still be around.
        sessionBinding = SessionBinding20.compute(sessionCtx.sessionId, seccLeafCertificate)
    }

    /**
     * The charging phase, which a service renegotiation sends round again — and which a resumed
     * session enters directly, having negotiated nothing.
     */
    private fun runChargingPhase() {

        // ── the charging phase, which a service renegotiation sends round again ────────────────
        //
        // [V2G20-1477]: the station asks by putting EvseNotification.ServiceRenegotiation in a
        // charge-loop response's EVSEStatus. The EV stops power delivery, sends
        // SessionStopReq(ServiceRenegotiation) — which does NOT end the session — and both sides
        // return to ServiceDiscovery. Everything from service selection down runs again, with charge
        // parameters and the schedule offer negotiated afresh; authorization does not, and must not.
        //
        // A loop rather than a single re-entry: our station signals once, but nothing in the standard
        // says a station may only ask once.
        while (true) {

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

        // §7.9.2.5's -20 half. Stays null when the offer carries no AbsolutePriceSchedule at all,
        // which is the ordinary case — most stations send the compact PriceLevelSchedule instead, and
        // reporting an unsigned verdict for them would accuse them of failing a check nobody asked
        // them to pass.
        tariff = Iso20PriceScheduleCheck.evaluate(
            scheduleRes, scheduleRes.header.signature, tariffVerifyKey)

        runPreChargeSequence()

        // PowerDelivery(Start) must carry an EVPowerProfile referencing a schedule tuple the station
        // offered (§7.9.2.4). A live Josev run rejected the earlier absent profile; ours did not. EVerest
        // 2026.10.0 rejected one anchored at 1970 — see Iso20EvPowerProfile.
        val evPowerProfile = buildEvPowerProfile(scheduleRes)
        selectedScheduleTupleId = evPowerProfile.scheduled_EVPPTControlMode?.selectedScheduleTupleID
        exchange<PowerDeliveryRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(PowerDeliveryReq(sessionCtx.toCommonHeader(),
                Processing.Finished, ChargeProgress.Start, evPowerProfile, null)))

        // Left after the iteration whose reading crossed the limit, as the C# car leaves it.
        for (cycle in 0 until CHARGE_CYCLES) {
            runChargeLoopIteration()
            if (meteringDeviation != null) break
            pollDelay(POLL_INTERVAL_MS)
        }

        // Power off either way: a renegotiation stops delivery too, and the contactor must be open
        // before the session goes back to talking about services.
        exchange<PowerDeliveryRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(PowerDeliveryReq(sessionCtx.toCommonHeader(),
                Processing.Finished, ChargeProgress.Stop, null, null)))

        if (!renegotiationRequested || meteringDeviation != null) break

        renegotiationRequested = false
        renegotiations++

        // The one SessionStopReq that does not stop the session. Sending stopMode here instead would
        // end it for real, which is the single most consequential thing to get wrong here.
        exchange<SessionStopRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(SessionStopReq(
                sessionCtx.toCommonHeader(), ChargingSession.ServiceRenegotiation, null, null)))

        runServiceSelection()

        }

        runPostChargeSequence()

        // Stopped over the meter: ended for good, whatever stopMode says, and with the reason.
        val reason = meteringDeviation
        exchange<SessionStopRes>(MessageSet.Iso20CommonMessages,
            CommonMessagesCodec.encode(SessionStopReq(sessionCtx.toCommonHeader(),
                if (reason != null) ChargingSession.Terminate else stopMode,
                reason?.let { MeteringDeviation.TERMINATION_CODE },
                reason?.explanation)))
    }


    /**
     * Sends one already-encoded request and awaits its reply. The undiscriminated pair, because the
     * AC/DC subclasses need their own result types — [expect] is what narrows it.
     */
    protected fun exchangeRaw(expectedSet: MessageSet, payload: ByteArray): Pair<MessageSet, Any> {
        stream.writeFrame(expectedSet, payload)
        val reply = stream.readFrame()
        refuseOnFailure(reply.second)
        timeStampOf(reply.second)?.let { seccTimeStamp = it }
        exchanges++
        return reply
    }

    /**
     * One exchange on a side stream ([V2G20-1084]): a CommonMessages document framed with
     * [payloadType] rather than with its message set's, answered on the same type ([V2G20-1085]). The
     * dispatcher does not route side-stream payload types, so the answer is decoded here; one on any
     * other payload type is the station answering on the wrong stream, and ends the session.
     */
    private fun exchangeSideStream(payloadType: UShort, payload: ByteArray): Any {
        stream.writeRawFrame(payloadType, payload)
        val (frame, answeredOn) = stream.readRawFrame()
        if (answeredOn != payloadType)
            throw SessionAborted(
                "the station answered a side-stream request (payload type 0x%04x) on payload type 0x%04x."
                    .format(payloadType.toInt(), answeredOn.toInt()))
        val reply = CommonMessagesCodec.decodeAny(frame.copyOfRange(cloud.charging.v2g.tp.V2GTP.HEADER_SIZE, frame.size))
        refuseOnFailure(reply)
        timeStampOf(reply)?.let { seccTimeStamp = it }
        exchanges++
        return reply
    }

    /**
     * Ends the session when `AuthorizationRes` carries a `WARNING` code: the one response where a
     * warning means "not authorized", not "carry on".
     *
     * [V2G20-1583] gives the car four options after such a warning: authorize again, switch between
     * PnC and EIM, install a contract, or stop. Going on to ServiceDiscovery is not one of them. This
     * car takes "stop". EVerest 2026.10.0 answered a P-256 contract with
     * `WARNING_CertificateValidationError` and `Finished`. Our C# car read only `Finished`, asked for
     * services, and was refused with `FAILED_SequenceError` (2026-11-27). This port had the same gap.
     * The C# counterpart is `Evcc20Base.StopOnAuthorizationWarning`.
     */
    internal fun stopOnAuthorizationWarning(res: AuthorizationRes) {   // internal: Evcc20FailureTest
        if (res.responseCode.ordinal >= ResponseCode.WARNING_AuthorizationSelectionInvalid.ordinal &&
            res.responseCode.ordinal <  ResponseCode.FAILED.ordinal)
            throw SessionAborted(
                "the station answered AuthorizationRes with ${res.responseCode.name}: not authorized, so " +
                "this car stops instead of continuing to ServiceDiscovery ([V2G20-1583]).")
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
     * session goes on" — and `FAILED*` terminates. `AuthorizationRes` is the exception, see
     * [stopOnAuthorizationWarning]. The comparison is on `ordinal` because the
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

        // preferBidirectionalService, applied here rather than in each list, so it holds for the
        // overridden ones too. sortedByDescending is stable, which is the whole trick: entries keep
        // their order within each half, so this promotes the BPT services without inventing an
        // order among them, and is a no-op on a list that has none.
        fun ranked(ids: List<UShort>): List<UShort> =
            if (preferBidirectionalService) ids.sortedByDescending(::isBidirectional) else ids

        // Both lookups walk *our* ranking and ask whether the station offers that id, as the C#
        // EVCC does since the 2026-08-05 EVerest MCS_BPT run. Walking the station's catalogue instead
        // takes the first entry we happen to accept, so our order is never read and the station
        // decides; until 2026-11-28 this port did that.
        val offeredIds = offered.map { it.serviceID }.toSet()
        val match = ranked(preferredEnergyServiceIds).firstOrNull { it in offeredIds }
                 ?: ranked(drivableEnergyServiceIds).firstOrNull { it in offeredIds }

        return match ?: throw SessionAborted(
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

    /** The EVPowerProfile that PowerDelivery(Start) must carry: anchored at the station's latest
     *  `TimeStamp` and following the first schedule tuple it offered — see [Iso20EvPowerProfile] for
     *  the rules, and for why the anchor used to be 1970. */
    private fun buildEvPowerProfile(scheduleRes: ScheduleExchangeRes): EVPowerProfileType =
        Iso20EvPowerProfile.build(scheduleRes,
                                  seccTimeStamp ?: sessionCtx.toCommonHeader().timeStamp,
                                  preferDynamicControlMode)

    /** SECC time as the station last stated it: the `TimeStamp` of its latest response, in seconds;
     *  null until the first. `[V2G20-1536]` makes the station's header the reference for SECC time, and
     *  taking it from the response rather than our clock keeps a replayed session a pure function of
     *  the responses. */
    var seccTimeStamp: ULong? = null
        private set

    private fun timeStampOf(message: Any): ULong? = when (message) {
        is V2GResponseType                          -> message.header.timeStamp
        is cloud.charging.v2g.iso20.ac.V2GResponseType -> message.header.timeStamp
        is cloud.charging.v2g.iso20.dc.V2GResponseType -> message.header.timeStamp
        else                                        -> null
    }
}
