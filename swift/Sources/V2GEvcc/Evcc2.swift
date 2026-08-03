import ExiIso2
import V2GDispatch

/// The EV's smart-charging verdict over the SASchedule offer: how many tuples were offered, which
/// one the EV chose (lowest average EPriceLevel), and how many ChargingProfile entries it derived
/// from that tuple's PMaxSchedule.
///
/// The C# record carries four more fields describing the tariff *signature*; see ``Evcc2`` for why
/// that half is not ported.
public struct Iso2TariffResult: Equatable, Sendable {
    public let tuplesOffered: Int
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
/// ## What is not here
///
/// **Tariff-signature verification.** The C# original also runs the §7.9.2.5 check over signed
/// SalesTariffs (digest per tariff + ECDSA, dual-grammar); the tuple *choice* is ported, the
/// signature check is not — ``Iso2TariffResult`` carries three fields where C#'s carries seven,
/// and nothing here takes a tariff verify key.
///
/// **Pause/resume** ([V2G2-740]) is likewise unported: it needs a trace that pauses and rejoins.
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

    /// How the session ends: `.Terminate` (default) or `.Pause`.
    public var stopMode: ChargingSession = .Terminate

    /// Contract credentials. When set and the station offers Contract, the session runs Plug & Charge
    /// instead of external payment.
    public var pnc: PncEvccOptions?

    /// How this session authorized: `"eim"`, or `"pnc-signed"` after a signed AuthorizationReq.
    public private(set) var authorizationMode = "eim"

    /// How many signed MeteringReceiptReq this session sent. Contract only — an EIM station never
    /// asks, so a non-zero count is also the clearest single sign that PnC really ran.
    public private(set) var meteringReceiptsSent = 0

    /// The station's SessionSetup verdict.
    public private(set) var sessionSetupCode: ResponseCode?

    /// The smart-charging verdict over the (last) offer; nil until ChargeParameterDiscovery ended.
    public private(set) var tariff: Iso2TariffResult?

    private var chosenTupleId: UInt8 = 1
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

        let _: PaymentServiceSelectionResType = try send(PaymentServiceSelectionReqType(
            selectedPaymentOption: contract ? .Contract : .ExternalPayment,
            selectedServiceList: SelectedServiceListType(selectedService: [
                SelectedServiceType(serviceID: chargeServiceId)
            ])))

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
            let cableGuard = OngoingGuard("CableCheck", limitMillis: ongoingTimeoutMillis)
            while true {
                let res: CableCheckResType = try send(CableCheckReqType(dC_EVStatus: Self.evStatus()))
                if res.eVSEProcessing == .Finished { break }
                try cableGuard.tick()
                pollDelay(Self.pollIntervalMs)
            }
            let _: PreChargeResType = try send(PreChargeReqType(
                dC_EVStatus: Self.evStatus(),
                eVTargetVoltage: Self.volt(400), eVTargetCurrent: Self.amp(2)))
        }

        // ── CHARGE ─────────────────────────────────────────────────────────
        let _: PowerDeliveryResType = try send(powerDelivery(.Start))

        var renegotiated = false
        for _ in 0 ..< Self.chargeCycles {

            // A Contract station may demand a receipt in its status response — answer with a signed
            // MeteringReceiptReq echoing its MeterInfo, as a real EV does.
            let notification: EVSENotification
            if mode == .dc {
                let res: CurrentDemandResType = try send(Self.currentDemand())
                if res.receiptRequired == true, let meterInfo = res.meterInfo {
                    try sendMeteringReceipt(meterInfo, res.sAScheduleTupleID)
                }
                notification = res.dC_EVSEStatus.eVSENotification
            } else {
                let res: ChargingStatusResType = try send(ChargingStatusReqType())
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
                let _: PowerDeliveryResType = try send(powerDelivery(.Start))
            }
            pollDelay(Self.pollIntervalMs)
        }

        let _: PowerDeliveryResType = try send(powerDelivery(.Stop))

        // ── STOP ───────────────────────────────────────────────────────────
        if mode == .dc {
            let _: WeldingDetectionResType = try send(WeldingDetectionReqType(dC_EVStatus: Self.evStatus()))
        }

        let _: SessionStopResType = try send(SessionStopReqType(chargingSession: stopMode))
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

        tariff = Iso2TariffResult(tuplesOffered: offer.sAScheduleTuple.count,
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
        sessionId = reply.header.sessionID   // adopt the station-assigned session id

        guard let body = reply.body.bodyElement as? T else {
            throw SessionAborted("expected a \(T.self), got \(String(describing: reply.body.bodyElement)).")
        }
        return body
    }

    /// Ends the session when the station answers with a code from the `FAILED` family.
    ///
    /// The -2 half of the gap eVDriveFlow exposed in the -20 EVCC on 2026-08-01
    /// (`libs/Vanaheimr.V2G.Exi/docs/interop-runs/2026-08-01-edf-iso20-dc-notls/`, finding 3). Same
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
                    dC_EVStatus: Self.evStatus(),
                    eVMaximumCurrentLimit: Self.amp(200),
                    eVMaximumVoltageLimit: Self.volt(500),
                    fullSOC: 100, bulkSOC: 80))
            : ChargeParameterDiscoveryReqType(
                requestedEnergyTransferMode: transferMode,
                eVChargeParameter: AC_EVChargeParameterType(
                    eAmount: PhysicalValueType(multiplier: 0, unit: .Wh, value: 22_000),
                    eVMaxVoltage: Self.volt(400),
                    eVMaxCurrent: Self.amp(32),
                    eVMinCurrent: Self.amp(6)))
    }

    private static func currentDemand() -> CurrentDemandReqType {
        CurrentDemandReqType(dC_EVStatus: evStatus(),
                             eVTargetCurrent: amp(120),
                             chargingComplete: false,
                             eVTargetVoltage: volt(400))
    }

    private static func evStatus() -> DC_EVStatusType {
        DC_EVStatusType(eVReady: true, eVErrorCode: .NO_ERROR, eVRESSSOC: 50)
    }
    private static func volt(_ v: Int16) -> PhysicalValueType { PhysicalValueType(multiplier: 0, unit: .V, value: v) }
    private static func amp(_ a: Int16) -> PhysicalValueType  { PhysicalValueType(multiplier: 0, unit: .A, value: a) }
}
