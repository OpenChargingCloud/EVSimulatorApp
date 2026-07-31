package cloud.charging.v2g.evcc

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

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

        /** ISO 15118-2 `eMAIDType`: 14 characters without the check digit, 15 with it. */
        val EMAID_LENGTH = 14..15
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

    /** Contract credentials. When set and the station offers Contract, the session runs Plug & Charge
     *  instead of external payment. */
    var pnc: PncEvccOptions? = null

    /** How this session authorized: `"eim"`, or `"pnc-signed"` after a signed AuthorizationReq. */
    var authorizationMode: String = "eim"
        private set

    /** How many signed MeteringReceiptReq this session sent. Contract only — an EIM station never
     *  asks, so a non-zero count here is also the clearest single sign that PnC really ran. */
    var meteringReceiptsSent: Int = 0
        private set

    /** The station's SessionSetup verdict. */
    var sessionSetupCode: ResponseCode? = null
        private set

    /** The smart-charging verdict over the (last) offer; null until ChargeParameterDiscovery ended. */
    var tariff: Iso2TariffResult? = null
        private set

    private var chosenTupleId: UByte = 1u
    private var chargingProfile: ChargingProfileType? = null


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

        // Plug & Charge only if we have credentials AND the station offers it; otherwise EIM.
        // Read once into a local: `pnc` is settable, and Kotlin will not smart-cast a mutable
        // property across the calls below.
        val credentials = pnc
        val contract = credentials != null &&
                       discovery.paymentOptionList.paymentOption.contains(PaymentOption.Contract)

        send<PaymentServiceSelectionResType>(PaymentServiceSelectionReqType(
            if (contract) PaymentOption.Contract else PaymentOption.ExternalPayment,
            SelectedServiceListType(listOf(SelectedServiceType(serviceID = 1u, parameterSetID = null)))))

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

        while (send<AuthorizationResType>(authRequest, authSignature).eVSEProcessing != EVSEProcessing.Finished)
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

            // A Contract station may demand a receipt in its status response — answer with a signed
            // MeteringReceiptReq echoing its MeterInfo, as a real EV does.
            val notification =
                if (mode == PowerMode.Dc) {
                    val res = send<CurrentDemandResType>(currentDemand())
                    if (res.receiptRequired == true && res.meterInfo != null)
                        sendMeteringReceipt(res.meterInfo!!, res.sAScheduleTupleID)
                    res.dC_EVSEStatus.eVSENotification
                } else {
                    val res = send<ChargingStatusResType>(ChargingStatusReqType())
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


    /** Signs and sends one MeteringReceiptReq for the station's MeterInfo. */
    private fun sendMeteringReceipt(meterInfo: MeterInfoType, saScheduleTupleId: UByte?) {

        val receipt   = MeteringReceiptReqType("id2", sessionId, saScheduleTupleId, meterInfo)
        val signature = XmlDsigInterop.sign2(
            "id2", Iso15118_2Codec.encodeFragment_MeteringReceiptReq(receipt), pnc!!.contractKey)

        send<MeteringReceiptResType>(receipt, signature)
        meteringReceiptsSent++
    }


    /**
     * The eMAID for PaymentDetails — the contract certificate's CN, checked against the one rule the
     * schema states.
     *
     * C# reads the CN with `GetNameInfo(X509NameType.SimpleName, …)`; the JVM has no direct
     * equivalent, so the RFC 2253 subject is parsed for it. Equivalent for the single-CN subjects
     * this ever sees, and it fails loudly rather than sending an empty eMAID if that ever breaks.
     *
     * ISO 15118-2 constrains `eMAIDType` to **14 or 15 characters** (`V2G_CI_MsgDataTypes.xsd`), and
     * that is checked here because it was missing: a corpus certificate with a 19-character CN
     * travelled in a recorded PnC session and nothing objected, in any of the three back ends. The
     * generated codec does not enforce string-length facets — reasonably, since an EXI encoder
     * assumes schema-valid input — so nothing else was going to catch it.
     *
     * It is a **-2** rule, not a certificate-profile rule: ISO 15118-20 never sends the eMAID from
     * the certificate, so the same credential can be perfectly usable there. Hence the check lives
     * on this path and not on [PncEvccOptions].
     */
    private fun contractEmaid(certificateDer: ByteArray): String {

        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateDer.inputStream()) as X509Certificate

        val commonName = certificate.subjectX500Principal.name
            .split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=") }
            ?.removePrefix("CN=")
            ?: throw SessionAborted(
                "the contract certificate's subject has no CN, so there is no eMAID to send: " +
                certificate.subjectX500Principal.name)

        if (commonName.length !in EMAID_LENGTH)
            throw SessionAborted(
                "the contract certificate's Common Name \"$commonName\" is ${commonName.length} " +
                "characters; ISO 15118-2 allows an eMAID of 14 or 15, so this credential cannot " +
                "authorize a -2 Plug & Charge session.")

        return commonName
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


    private inline fun <reified T : BodyBaseType> send(requestBody: BodyBaseType,
                                                       signature: SignatureType? = null): T {

        val header  = MessageHeaderType(sessionId, notification = null, signature = signature)
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
