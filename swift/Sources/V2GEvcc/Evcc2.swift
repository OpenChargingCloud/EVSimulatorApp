import CryptoKit
import Foundation
import V2GMetering
import ExiIso2
import V2GCertificates
import V2GDispatch

/// The EV's smart-charging verdict over the SASchedule offer: how many tuples were offered, whether
/// the SalesTariffs carried a header signature and it verified (digest per tariff + ECDSA,
/// dual-grammar), which tuple the EV chose (lowest average EPriceLevel), and how many ChargingProfile
/// entries it derived from that tuple's PMaxSchedule.
public struct Iso2TariffResult: Equatable, Sendable {

    public let tuplesOffered: Int

    /// The §7.9.2.5 verdict. See ``Iso2TariffCheck`` — and note that only ``Evcc2/tariffVerifyKey``
    /// makes the signature half answerable at all.
    public let signaturePresent: Bool
    public let digestOk: Bool
    public let signatureOk: Bool
    public let signatureGrammar: String

    public let chosenTupleId: UInt8
    public let profileEntries: Int
}

/// The vehicle (EVCC) side of an ISO 15118-2 session: it drives the session over an
/// already-connected and already-SAP-negotiated ``V2GTPStream``. Each step is one request/response
/// exchange; the poll loops (Authorization, ChargeParameterDiscovery) back off through `pollDelay`.
///
/// A port of the C# `Evcc2`, and checked against it rather than against itself: `Evcc2TraceTests`
/// replays the sessions recorded in `Vectors/Session.iso2-*.trace.json` and requires this
/// implementation to emit byte-identical requests. That corpus is the only reason to believe the
/// two agree — there is no reference EVCC, and a state machine that merely runs to completion has
/// proved nothing about *what* it said.
///
/// ## Pause and resume
///
/// Both are here ([V2G2-740]), and a -2 resume is smaller than it sounds: ``resumeSessionId`` in the
/// opening header and ``alreadyChargedWh`` off the energy request, and then the whole session runs
/// again exactly as a first visit would. `Session.iso2-ac-eim-pause` and its successor are the pair
/// that pins it — a resumed -20 session, by contrast, skips its entire opening block.
public final class Evcc2 {

    private static let pollIntervalMs: UInt64 = 50
    private static let chargeCycles = 3

    private let stream: V2GTPStream
    private let mode: PowerMode
    private let pollDelay: (UInt64) -> Void

    private var sessionId = [UInt8](repeating: 0, count: 8)   // 0 until SessionSetupRes assigns one

    /// How many request/response exchanges this session ran.
    public private(set) var exchanges = 0

    /// When set, the EV initiates one renegotiation on its own after the first charging-status
    /// cycle. Independent of that, it always reacts to a station-side `.ReNegotiation`.
    public var renegotiate = false

    /// How many renegotiation cycles this session ran (own + station-requested).
    public private(set) var renegotiations = 0

    /// Skips the DC isolation sequence on the way back from a renegotiation. Off by default, because
    /// the standard has no such exception — it exists for stations that refuse the conformant path,
    /// and mirrors C#'s `RenegotiationSkipsIsolationSequence`.
    public var renegotiationSkipsIsolationSequence = false

    /// How the session ends: `.Terminate` (default) or `.Pause`.
    public var stopMode: ChargingSession = .Terminate

    /// A paused predecessor's session id. When set, the opening `SessionSetupReq` carries it instead
    /// of the all-zero id, and the station rejoins the old session rather than assigning a new one
    /// ([V2G2-740]).
    ///
    /// That single field is the whole of a -2 resume on the wire. Everything else runs again —
    /// service discovery, payment selection, authorization — which is where -2 and -20 part company:
    /// a resumed -20 session repeats none of it. See ``Evcc20Base/resumeSessionId``.
    public var resumeSessionId: [UInt8]?

    /// What a paused predecessor already charged, so this session asks for the remainder:
    /// [V2G2-743] requires a resumed session's `EAmount` to be reduced by the energy already taken.
    ///
    /// **Read only when there is no ``battery``, and that is the whole contract.** A pack carried
    /// across the pause already holds the better answer — its state of charge moved, so
    /// `energyNeededWh` is the remainder by construction — and subtracting this on top would count
    /// the same energy twice. A real car cannot be in the second case: its pack does not forget when
    /// the cable comes out.
    public var alreadyChargedWh: Double = 0

    /// Contract credentials. When set and the station offers Contract, the session runs Plug & Charge
    /// instead of external payment.
    public var pnc: PncEvccOptions?

    /// Provisioning credentials. When set — and the station advertises the certificate service — the
    /// EVCC selects that service and asks for a contract before authorizing. Nil (the default) skips
    /// the whole exchange, which is what a car that already holds a contract does.
    public var certInstallRequest: Iso2CertInstallOptions?

    /// The parameter-set id this car names when it selects the certificate service, overriding the
    /// conformant pairing (Installation is set 1, Update is set 2). Nil keeps the pairing, which is
    /// what a real car does and what every recorded run used. Present because a station in the field
    /// advertises set 1 alone — see C#'s `Evcc2.CertificateParameterSetId` for the whole story.
    public var certificateParameterSetId: Int16?

    /// The contract certificate (DER) the provisioning exchange installed, once one has arrived.
    public private(set) var installedContractCertificate: [UInt8]?

    /// The unwrapped contract private key, checked against the certificate it arrived with.
    public private(set) var installedContractKey: P256.Signing.PrivateKey?

    /// The eMAID the operator issued the contract under.
    public private(set) var installedEmaid: String?

    /// The full §7.9.2.4.2 verdict over the provisioning response, once one has arrived.
    public private(set) var installedContractVerdict: Iso2ContractVerdict?

    /// Whether that response's four-reference signature held — both halves of
    /// ``installedContractVerdict``, since a response whose digests do not hold is not signed for what
    /// it carries.
    public private(set) var installedContractSignatureOk = false

    /// How this session authorized: `"eim"`, or `"pnc-signed"` after a signed AuthorizationReq.
    public private(set) var authorizationMode = "eim"

    /// How many signed MeteringReceiptReq this session sent. Contract only — an EIM station never
    /// asks, so a non-zero count is also the clearest single sign that PnC really ran.
    public private(set) var meteringReceiptsSent = 0

    /// The station's SessionSetup verdict.
    public private(set) var sessionSetupCode: ResponseCode?

    /// The smart-charging verdict over the (last) offer; nil until ChargeParameterDiscovery ended.
    public private(set) var tariff: Iso2TariffResult?

    /// The Mobility Operator's public key, when the app has one. Without it the §7.9.2.5 digest half is
    /// still checked and reported; the ECDSA half is not attempted, and ``Iso2TariffResult/signatureOk``
    /// stays `false` meaning *not established* rather than *failed* — which is why
    /// ``Iso2TariffResult/signatureGrammar`` exists to tell those apart.
    public var tariffVerifyKey: P256.Signing.PublicKey?

    /// The header of the last response. Kept only for its Signature: the tariff check needs it, and it
    /// arrives one layer above the body that `evaluateSchedules` is handed.
    private var lastHeader: MessageHeaderType?

    private var chosenTupleId: UInt8 = 1
    /// The vehicle's own energy counter — what this EV thinks it took, kept independently of what
    /// the station reports (`docs/CONCEPT.md` §4.2/§4.3).
    public let meter = EvMeter()

    /// A battery that fills up, and the goal that ends the charge loop. Nil — the default — keeps the
    /// fixed three iterations every recorded interop run was taken with, which is why it is opt-in
    /// here exactly as it is in C#.
    public var battery: EvBattery?

    /// Why the charge loop ended; nil while it has not finished.
    public private(set) var batteryStop: ChargeStop?

    private var chargingProfile: ChargingProfileType?
    private var energyTransferMode: EnergyTransferMode?   // chosen from what the station offered

    /// How long a phase may keep answering `EVSEProcessing = Ongoing` before the session ends —
    /// 60 s, ISO 15118's EVCC ongoing timeout. See `OngoingGuard` for the live run that required it.
    public var ongoingTimeoutMillis: UInt64 = 60_000

    public init(_ stream: V2GTPStream, _ mode: PowerMode,
                pollDelay: @escaping (UInt64) -> Void = { _ in }) {
        self.stream = stream
        self.mode = mode
        self.pollDelay = pollDelay
    }

    public func run() throws {

        // Check the credential before opening the session, not four exchanges in: a station that has
        // already assigned a session id and been asked for its payment options should not then be
        // abandoned over something knowable before the first byte.
        if let credentials = pnc, credentials.emaid == nil {
            throw SessionAborted(
                "the contract certificate's Common Name is not a usable eMAID; ISO 15118-2 allows " +
                "14 or 15 characters, so this credential cannot authorize a -2 Plug & Charge session.")
        }

        // ── SETUP ──────────────────────────────────────────────────────────
        if let resumeSessionId {
            sessionId = resumeSessionId   // rejoin: the SessionSetupReq header carries the paused id
        }

        let setup: SessionSetupResType = try send(SessionSetupReqType(
            eVCCID: [0xAB, 0xCD, 0xEF, 0x01, 0x02, 0x03]))
        sessionSetupCode = setup.responseCode

        let discovery: ServiceDiscoveryResType = try send(ServiceDiscoveryReqType())
        energyTransferMode = try selectEnergyTransferMode(discovery)

        // The service id is the station's, not a constant. Ours has always been 1 and so has every
        // counterparty's so far, which is exactly why this was a literal until the sweep of
        // 2026-08-03 (the schema makes ChargeService mandatory, so unlike C# there is no nil case).
        let chargeServiceId = discovery.chargeService.serviceID

        // Plug & Charge only if we have credentials AND the station offers it; otherwise EIM.
        let credentials = pnc
        let contract = credentials != nil
            && discovery.paymentOptionList.paymentOption.contains(.Contract)

        // Contract provisioning is a value-added service in -2: it has to be *found* in the station's
        // ServiceList and then selected by id, where -20 needs only a flag in AuthorizationSetupRes.
        let certificateService = certInstallRequest == nil
            ? nil
            : discovery.serviceList?.service.first { $0.serviceCategory == .ContractCertificate }

        var selected = [SelectedServiceType(serviceID: chargeServiceId)]
        if let certificateService, let request = certInstallRequest {
            selected.append(SelectedServiceType(
                serviceID: certificateService.serviceID,
                parameterSetID: certificateParameterSetId
                    ?? (request.action == .update ? 2 : 1)))
        }

        let _: PaymentServiceSelectionResType = try send(PaymentServiceSelectionReqType(
            selectedPaymentOption: contract ? .Contract : .ExternalPayment,
            selectedServiceList: SelectedServiceListType(selectedService: selected)))

        if certificateService != nil, let request = certInstallRequest {
            try runCertificateProvisioning(request)
        }

        // ── AUTH (poll until authorised) ───────────────────────────────────
        // Contract: PaymentDetails first (chain in, GenChallenge out), then a signed AuthorizationReq
        // echoing the challenge. Signed once — the challenge does not change across polls.
        var authRequest = AuthorizationReqType()
        var authSignature: SignatureType?

        if let credentials, contract {
            let details: PaymentDetailsResType = try send(PaymentDetailsReqType(
                eMAID: credentials.emaid!,   // checked at the top of run()
                contractSignatureCertChain: CertificateChainType(
                    certificate: credentials.contractCertificate,
                    subCertificates: SubCertificatesType(certificate: credentials.subCertificates))))

            authRequest = AuthorizationReqType(id: "id1", genChallenge: details.genChallenge)
            authSignature = try XmlDsigInterop.sign2(
                "id1", Iso15118_2Codec.encodeFragment_AuthorizationReq(authRequest),
                credentials.contractKey)
            authorizationMode = "pnc-signed"
        }

        let authGuard = OngoingGuard("Authorization", limitMillis: ongoingTimeoutMillis)
        while true {
            let res: AuthorizationResType = try send(authRequest, authSignature)
            if res.eVSEProcessing == .Finished { break }
            try authGuard.tick()
            pollDelay(Self.pollIntervalMs)
        }

        // ── CHARGE PARAMETERS (+ DC cable check / pre-charge) ──────────────
        try runChargeParameterDiscovery()

        if mode == .dc {
            try runDcIsolationSequence()
        }

        // ── CHARGE ─────────────────────────────────────────────────────────
        let _: PowerDeliveryResType = try send(powerDelivery(.Start))

        var renegotiated = false
        // Three iterations stand in for a session when there is no battery; with one, the loop ends
        // when the car is done. Same rule as C#, and the same reason it is opt-in: every recorded run
        // was taken at three.
        var cycle = 0
        while (battery == nil ? cycle < Self.chargeCycles : batteryStop == nil) {

            let energyBefore = meter.energy

            // A Contract station may demand a receipt in its status response — answer with a signed
            // MeteringReceiptReq echoing its MeterInfo, as a real EV does.
            let notification: EVSENotification
            if mode == .dc {
                let demand = currentDemand()
                let res: CurrentDemandResType = try send(demand)

                // The EV's own view, from the EV's own request: ISO 15118-2 gives a DC vehicle no
                // field for a *measured* inlet power, so what it asked for is the closest thing it
                // owns — and taking the station's EVSEPresent* would make this counter an echo of the
                // very number it exists to be compared against.
                meter.sample(volts:   Self.amount(demand.eVTargetVoltage),
                             amperes: Self.amount(demand.eVTargetCurrent))
                if res.receiptRequired == true, let meterInfo = res.meterInfo {
                    try sendMeteringReceipt(meterInfo, res.sAScheduleTupleID)
                }
                notification = res.dC_EVSEStatus.eVSENotification
            } else {
                let res: ChargingStatusResType = try send(ChargingStatusReqType())

                // AC carries no power in either direction, so the EV's own view is the profile it
                // committed to in PowerDeliveryReq — derived by the EV itself from the tuple it
                // chose, and validated by the station against its own PMax.
                meter.sample(committedPowerW())

                if res.receiptRequired == true, let meterInfo = res.meterInfo {
                    try sendMeteringReceipt(meterInfo, res.sAScheduleTupleID)
                }
                notification = res.aC_EVSEStatus.eVSENotification
            }

            // Renegotiation ([V2G2-841]) — reactive (the station notified) or proactive (once):
            // PowerDelivery(Renegotiate) → fresh ChargeParameterDiscovery → PowerDelivery(Start).
            if !renegotiated && (notification == .ReNegotiation || renegotiate) {
                renegotiated = true
                renegotiations += 1
                let _: PowerDeliveryResType = try send(powerDelivery(.Renegotiate))
                try runChargeParameterDiscovery()

                // DC returns through the isolation sequence, exactly as it did on the way in: the
                // station's state table admits CableCheckReq after ChargeParameterDiscoveryReq and
                // nothing else ([V2G2-565], [V2G2-582]), with no renegotiation exception. The C# car
                // learned this on 2026-08-15, against a station that refused the shortcut; this port
                // went straight to PowerDelivery(Start) until the recording said otherwise.
                if mode == .dc && !renegotiationSkipsIsolationSequence {
                    try runDcIsolationSequence()
                }

                let _: PowerDeliveryResType = try send(powerDelivery(.Start))
            }

            if let pack = battery {
                pack.add(meter.energy - energyBefore)
                let stop = pack.stop
                if stop != .running { batteryStop = stop }
            }

            pollDelay(Self.pollIntervalMs)
            cycle += 1
        }

        let _: PowerDeliveryResType = try send(powerDelivery(.Stop))

        // ── STOP ───────────────────────────────────────────────────────────
        if mode == .dc {
            let _: WeldingDetectionResType = try send(WeldingDetectionReqType(dC_EVStatus: evStatus()))
        }

        let _: SessionStopResType = try send(SessionStopReqType(chargingSession: stopMode))
    }

    /// CableCheck until Finished, then PreCharge — the DC isolation sequence, which a session runs
    /// twice: once on the way in, and once more on the way back from a renegotiation. One definition
    /// because the second caller is exactly why the first had to stop being inline.
    private func runDcIsolationSequence() throws {
        let cableGuard = OngoingGuard("CableCheck", limitMillis: ongoingTimeoutMillis)
        while true {
            let res: CableCheckResType = try send(CableCheckReqType(dC_EVStatus: evStatus()))
            if res.eVSEProcessing == .Finished { break }
            try cableGuard.tick()
            pollDelay(Self.pollIntervalMs)
        }
        let _: PreChargeResType = try send(PreChargeReqType(
            dC_EVStatus: evStatus(),
            eVTargetVoltage: Self.volt(400), eVTargetCurrent: Self.amp(2)))
    }

    /// Polls ChargeParameterDiscovery until Finished, then evaluates the offer. Runs again after a
    /// renegotiation, because the offer may have changed. Deadline-guarded like the other Ongoing
    /// poll loops — this one had quietly missed the ongoing-deadline port.
    private func runChargeParameterDiscovery() throws {
        let cpdGuard = OngoingGuard("ChargeParameterDiscovery", limitMillis: ongoingTimeoutMillis)
        while true {
            let res: ChargeParameterDiscoveryResType = try send(chargeParameterDiscovery())
            if res.eVSEProcessing == .Finished {
                evaluateSchedules(res)
                return
            }
            try cpdGuard.tick()
            pollDelay(Self.pollIntervalMs)
        }
    }

    /// The EV-side smart-charging step: choose the tuple with the lowest average EPriceLevel, and
    /// shape the ChargingProfile to that tuple's PMaxSchedule entry for entry — this simulated EV
    /// can always draw PMax, where a weaker one would cap at its own limit.
    ///
    /// Both outputs travel in the next `PowerDeliveryReq(Start)`, so this is not bookkeeping: get
    /// the tuple choice or the profile wrong and the trace diverges at that message.
    private func evaluateSchedules(_ response: ChargeParameterDiscoveryResType) {

        guard let offer = response.sASchedules as? SAScheduleListType,
              !offer.sAScheduleTuple.isEmpty else {
            // No offer. [V2G2-905] makes this a station bug, EVSEProcessing games aside.
            tariff = nil
            chosenTupleId = 1
            chargingProfile = nil
            return
        }

        // Lowest average EPriceLevel; tariff-less tuples rank last, ties keep the offer's order.
        // `min(by:)` is stable in the sense that matters here — it keeps the first of equals.
        let chosen = offer.sAScheduleTuple.min { Self.averagePriceLevel($0) < Self.averagePriceLevel($1) }!
        chosenTupleId = chosen.sAScheduleTupleID

        let profile = ChargingProfileType(profileEntry: chosen.pMaxSchedule.pMaxScheduleEntry.map {
            ProfileEntryType(
                chargingProfileEntryStart: ($0.timeInterval as? RelativeTimeIntervalType)?.start ?? 0,
                chargingProfileEntryMaxPower: $0.pMax)
        })
        chargingProfile = profile

        let verdict = Iso2TariffCheck.evaluate(offer: offer,
                                               headerSignature: lastHeader?.signature,
                                               verifyKey: tariffVerifyKey)

        tariff = Iso2TariffResult(tuplesOffered: offer.sAScheduleTuple.count,
                                  signaturePresent: verdict.signaturePresent,
                                  digestOk: verdict.digestOk,
                                  signatureOk: verdict.signatureOk,
                                  signatureGrammar: verdict.signatureGrammar,
                                  chosenTupleId: chosenTupleId,
                                  profileEntries: profile.profileEntry.count)
    }

    private static func averagePriceLevel(_ tuple: SAScheduleTupleType) -> Double {
        guard let entries = tuple.salesTariff?.salesTariffEntry, !entries.isEmpty else {
            return .greatestFiniteMagnitude
        }
        let sum = entries.reduce(0.0) { $0 + Double($1.ePriceLevel ?? UInt8.max) }
        return sum / Double(entries.count)
    }

    /// Runs the -2 contract-provisioning exchange (§7.9.2.4): sends the signed request — the OEM
    /// provisioning certificate for an installation, the expiring contract for an update, signed over
    /// its own message fragment in the Josev interop form — then judges the four-reference response
    /// signature and ECDH-unwraps the issued contract private key.
    ///
    /// The verdict this reaches is checked by `Contract.provisioning.vectors.json` and not by the
    /// recorded session, for the reason ``Iso2ContractCheck`` gives: it never travels. What the
    /// recording pins is *where in the session this sits* — after service selection, before
    /// PaymentDetails — which no corpus of frames can state.
    private func runCertificateProvisioning(_ options: Iso2CertInstallOptions) throws {

        // -2 has the EV name the roots it trusts, so the operator can pick a chain the car can build.
        // Ours names the one dev root this stack uses; a real car lists what it was built with.
        let roots = ListOfRootCertificateIDsType(rootCertificateID: [
            X509IssuerSerialType(x509IssuerName: "CN=V2GRootCA (dev)", x509SerialNumber: 1)
        ])

        let request: BodyBaseType
        let signature: SignatureType

        switch options.action {

        case .update:
            guard let emaid = options.emaid else {
                throw SessionAborted("CertificateUpdateReq: the eMAID of the expiring contract is required.")
            }
            let update = CertificateUpdateReqType(
                id: "id1",
                contractSignatureCertChain: CertificateChainType(
                    certificate: options.certificate,
                    subCertificates: options.subCertificates.isEmpty
                        ? nil : SubCertificatesType(certificate: options.subCertificates)),
                eMAID: emaid,
                listOfRootCertificateIDs: roots)
            signature = try XmlDsigInterop.sign2(
                "id1", Iso15118_2Codec.encodeFragment_CertificateUpdateReq(update), options.signKey)
            request = update

        case .install:
            let install = CertificateInstallationReqType(
                id: "id1", oEMProvisioningCert: options.certificate, listOfRootCertificateIDs: roots)
            signature = try XmlDsigInterop.sign2(
                "id1", Iso15118_2Codec.encodeFragment_CertificateInstallationReq(install), options.signKey)
            request = install
        }

        // The two responses carry the same fields in the same order, bar the update's trailing
        // RetryCounter, so everything after this point is common.
        let response: BodyBaseType = options.action == .update
            ? (try send(request, signature) as CertificateUpdateResType)
            : (try send(request, signature) as CertificateInstallationResType)

        guard let payload = Iso2ContractCheck.unpack(response) else {
            throw SessionAborted("contract provisioning: unexpected response \(type(of: response)).")
        }

        let verdict = Iso2ContractCheck.evaluate(response, headerSignature: lastHeader?.signature)
        installedContractVerdict     = verdict
        installedContractSignatureOk = verdict.digestOk && verdict.signatureOk

        installedContractCertificate = payload.contractChain.certificate
        installedEmaid               = payload.emaid.value

        let recovered = try ContractProvisioning2.recoverContractKey(
            receiver: options.keyAgreement,
            dhPublicKey: payload.dhPublicKey.value,
            encryptedPrivateKey: payload.encryptedKey.value)

        // CBC authenticates nothing, so an unwrap always "succeeds". The check that it succeeded with
        // the right key is that the key belongs to the certificate it arrived with — without this a
        // car would carry on and only find out at its next AuthorizationReq, one session later.
        guard let issued = try? V2GCertificate(der: payload.contractChain.certificate),
              let issuedKey = issued.p256VerificationKey,
              ContractProvisioning2.matches(recovered, issuedKey)
        else {
            throw SessionAborted(
                "contract provisioning: the unwrapped key does not belong to the issued certificate.")
        }

        installedContractKey = recovered
    }

    /// Signs and sends one MeteringReceiptReq for the station's MeterInfo.
    private func sendMeteringReceipt(_ meterInfo: MeterInfoType, _ saScheduleTupleId: UInt8?) throws {

        guard let credentials = pnc else { return }

        let receipt = MeteringReceiptReqType(id: "id2", sessionID: sessionId,
                                             sAScheduleTupleID: saScheduleTupleId, meterInfo: meterInfo)
        let signature = try XmlDsigInterop.sign2(
            "id2", Iso15118_2Codec.encodeFragment_MeteringReceiptReq(receipt), credentials.contractKey)

        let _: MeteringReceiptResType = try send(receipt, signature)
        meteringReceiptsSent += 1
    }

    private func send<T>(_ requestBody: BodyBaseType, _ signature: SignatureType? = nil) throws -> T {

        let header  = MessageHeaderType(sessionID: sessionId, signature: signature)
        let request = V2G_Message(header: header, body: BodyType(bodyElement: requestBody))

        try stream.writeFrame(.iso15118_2, Iso15118_2Codec.encode(request))

        let (set, message) = try stream.readFrame()
        guard set == .iso15118_2, let reply = message as? V2G_Message else {
            throw SessionAborted("expected an ISO 15118-2 reply, got \(set).")
        }

        if let element = reply.body.bodyElement { try refuseOnFailure(element) }

        exchanges += 1
        sessionId  = reply.header.sessionID   // adopt the station-assigned session id
        lastHeader = reply.header             // for its Signature; see the property

        guard let body = reply.body.bodyElement as? T else {
            throw SessionAborted("expected a \(T.self), got \(String(describing: reply.body.bodyElement)).")
        }
        return body
    }

    /// Ends the session when the station answers with a code from the `FAILED` family.
    ///
    /// The -2 half of the gap eVDriveFlow exposed in the -20 EVCC on 2026-08-01
    /// (`../../docs/interop-runs/2026-08-01-edf-iso20-dc-notls/`, finding 3). Same
    /// hole, and invisible for the same reason: our own SECC never answers FAILED, so the trace corpus —
    /// this port's whole oracle — contains no such response.
    ///
    /// **Why a mirror rather than a switch over the types.** ISO 15118-2 has no common response base;
    /// every `*ResType` declares its own `responseCode`. A switch would be fail-open — the case nobody
    /// wrote, or the message added later, goes unchecked, which is the failure this exists to end.
    /// Reading the stored property by name covers every response type there is and will be; the C# side
    /// enumerates its generated assembly to prove the property is universal
    /// (`Evcc2FailureHandlingTests.EveryResponseTypeIsCheckable`), and all three back ends are emitted
    /// from the same schema plan.
    ///
    /// -2 has only two families: four `OK*` values and then `FAILED` onwards, with no `WARNING` — unlike
    /// -20.
    internal func refuseOnFailure(_ body: BodyBaseType) throws {

        let code = Mirror(reflecting: body).children
            .first { $0.label == "responseCode" }?
            .value as? ResponseCode

        if let code, code.rawValue >= ResponseCode.FAILED.rawValue {
            throw SessionAborted(
                "the station answered \(type(of: body)) with \(code); the session ends here.")
        }

    }


    /// The energy transfer mode to request, chosen from the ones the station advertised in
    /// `ServiceDiscoveryRes`'s ChargeService rather than assumed.
    ///
    /// This used to be hard-coded — `DC_extended` for DC, `AC_three_phase_core` for AC — and it
    /// worked against every station this stack had met, because every one of them offered exactly
    /// what we happened to name. EVerest's AC SIL configuration does not: it advertises
    /// single-phase, answers a three-phase request with `FAILED_WrongEnergyTransferMode`, and is
    /// right to (`docs/interop-runs/2026-08-03-everest-ac/`). The trace corpus could not show the
    /// difference, because our own SECC offers exactly the mode the constant named — the ports'
    /// whole blind spot, one layer along.
    ///
    /// Preference within our own power mode is best-first — three-phase over single-phase, extended
    /// over core — and a station that offers nothing in our mode is refused with the offer named.
    private func selectEnergyTransferMode(_ discovery: ServiceDiscoveryResType) throws -> EnergyTransferMode {

        let offered = discovery.chargeService.supportedEnergyTransferMode.energyTransferMode

        let preferred: [EnergyTransferMode] = mode == .dc
            ? [.DC_extended, .DC_core, .DC_combo_core, .DC_unique]
            : [.AC_three_phase_core, .AC_single_phase_core]

        if let match = preferred.first(where: { offered.contains($0) }) {
            return match
        }

        // Nothing in our power mode. Say what was offered: it is the one line that turns "the
        // station refused" into "the station is a DC charger and we are an AC car".
        throw SessionAborted(
            "ServiceDiscovery: the station offers no \(mode == .dc ? "DC" : "AC") energy transfer "
          + "mode (offered: "
          + (offered.isEmpty ? "none" : offered.map { "\($0)" }.joined(separator: ", ")) + ").")
    }


    // ── request builders ──────────────────────────────────────────────────

    /// Start carries the smart-charging outcome — the chosen tuple and the PMax-shaped profile;
    /// Renegotiate/Stop reference the tuple without a profile.
    private func powerDelivery(_ progress: ChargeProgress) -> PowerDeliveryReqType {
        PowerDeliveryReqType(chargeProgress: progress,
                             sAScheduleTupleID: chosenTupleId,
                             chargingProfile: progress == .Start ? chargingProfile : nil)
    }

    private func chargeParameterDiscovery() throws -> ChargeParameterDiscoveryReqType {
        guard let transferMode = energyTransferMode else {
            throw SessionAborted("ChargeParameterDiscovery before ServiceDiscovery")
        }
        return mode == .dc
            ? ChargeParameterDiscoveryReqType(
                requestedEnergyTransferMode: transferMode,
                eVChargeParameter: DC_EVChargeParameterType(
                    dC_EVStatus: evStatus(),
                    eVMaximumCurrentLimit: Self.amp(200),
                    eVMaximumVoltageLimit: Self.volt(500),
                    // Both optional and both absent until there is a pack to describe. -2 DC is the
                    // one place a car states its capacity outright, which is what a station needs to
                    // turn "40 kWh wanted" into a schedule rather than a number.
                    eVEnergyCapacity: battery.map { Self.wattHours($0.capacityWh) },
                    eVEnergyRequest:  battery.map { Self.wattHours($0.energyNeededWh) },
                    fullSOC: 100, bulkSOC: 80))
            : ChargeParameterDiscoveryReqType(
                requestedEnergyTransferMode: transferMode,
                eVChargeParameter: AC_EVChargeParameterType(
                    // EAmount is -2 AC's only energy field, and it is the request: how much this
                    // session wants, not what the pack holds. 22 kWh when nothing asked — less what
                    // a paused predecessor already charged, which is [V2G2-743] and is why the
                    // fallback is not a constant. With a pack there is nothing to subtract: charging
                    // moved its state of charge, so energyNeededWh is already the remainder.
                    eAmount: battery.map { Self.wattHours($0.energyNeededWh) }
                        ?? Self.wattHours(max(0, 22_000 - alreadyChargedWh)),
                    eVMaxVoltage: Self.volt(400),
                    eVMaxCurrent: Self.amp(32),
                    eVMinCurrent: Self.amp(6)))
    }

    /// Watt-hours as a -2 physical value, rounded to the whole watt-hour the wire and the meter both
    /// count in, and scaled to whatever multiplier makes it fit the `xs:short` the field is — a 60 kWh
    /// pack does not fit one otherwise.
    ///
    /// A local port of C#'s `PhysicalValue.Of`, which is the only caller's worth of it these ports
    /// need: everything else on this side of the wire is a constant small enough to write out. C#'s
    /// *negative* multipliers are not here either, and cannot be reached — the rounding below clears
    /// the fractional part they exist for.
    private static func wattHours(_ wattHours: Double) -> PhysicalValueType {
        // Half-to-even, matching C#'s bare Math.Round — the meter's own rule is away-from-zero, and
        // the two part company on exactly the .5 case.
        let amount = wattHours.rounded(.toNearestOrEven)
        var multiplier = 0
        var scaled = amount
        while multiplier < 3 && (scaled > Double(Int16.max) || scaled < Double(Int16.min)) {
            multiplier += 1
            scaled = amount / pow(10, Double(multiplier))
        }
        precondition(scaled <= Double(Int16.max) && scaled >= Double(Int16.min),
            "PhysicalValue \(amount) does not fit a multiplier in [-3, 3] (|value| would exceed \(Int16.max)).")
        return PhysicalValueType(multiplier: Int8(multiplier), unit: .Wh,
                                 value: Int16(scaled.rounded(.toNearestOrEven)))
    }

    /// The power this EV committed to in its ChargingProfile — its own view of an AC session, since
    /// -2 puts no power on the wire in either direction.
    ///
    /// The first entry, because the later ones start at offsets a three-iteration charge loop never
    /// reaches. Zero without a profile: a session that never agreed one has no committed power to
    /// count, and inventing one would put a number on screen nothing in the session supports.
    private func committedPowerW() -> Double {
        guard let entry = chargingProfile?.profileEntry.first else { return 0 }
        return Self.amount(entry.chargingProfileEntryMaxPower)
    }

    /// A PhysicalValue as a plain number: value x 10^multiplier.
    private static func amount(_ v: PhysicalValueType) -> Double {
        Double(v.value) * pow(10, Double(v.multiplier))
    }

    private func currentDemand() -> CurrentDemandReqType {
        CurrentDemandReqType(dC_EVStatus: evStatus(),
                             eVTargetCurrent: Self.amp(120),
                             chargingComplete: false,
                             eVTargetVoltage: Self.volt(400))
    }

    /// The DC status this car repeats in every request of the DC sequence — and, with a pack, the one
    /// field in -2 that *moves* during a session: `EVRESSSOC` is the present state of charge, so a
    /// station watching it sees the battery fill.
    ///
    /// A flat 50 % without one, which is what a car with no pack still sends. Worth naming because it
    /// is the only per-iteration reading -2 asks the vehicle for: -2 gives a DC car no field for a
    /// measured power, so this percentage is the whole of what the station learns about the vehicle's
    /// own state while charging.
    private func evStatus() -> DC_EVStatusType {
        DC_EVStatusType(eVReady: true, eVErrorCode: .NO_ERROR,
                        eVRESSSOC: battery.map { Int8(min(max($0.soC.rounded(.toNearestOrEven), 0), 100)) } ?? 50)
    }
    private static func volt(_ v: Int16) -> PhysicalValueType { PhysicalValueType(multiplier: 0, unit: .V, value: v) }
    private static func amp(_ a: Int16) -> PhysicalValueType  { PhysicalValueType(multiplier: 0, unit: .A, value: a) }
}
