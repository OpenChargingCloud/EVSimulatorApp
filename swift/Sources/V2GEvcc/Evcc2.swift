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
/// **Plug & Charge, and tariff-signature verification.** The C# original also does PaymentDetails
/// with a contract chain, a signed AuthorizationReq, signed MeteringReceiptReq, and the §7.9.2.5
/// check over signed SalesTariffs. All four are signature work, and the trace corpus is EIM — ECDSA
/// signing is randomised, so a signed request cannot be compared byte for byte at all and needs a
/// signature-aware comparison first. Porting them now would mean writing crypto with no oracle,
/// which is the one thing this repository has repeatedly found it should not do. The EIM path below
/// is complete, and the missing half is named rather than silently absent.
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

    /// The station's SessionSetup verdict.
    public private(set) var sessionSetupCode: ResponseCode?

    /// The smart-charging verdict over the (last) offer; nil until ChargeParameterDiscovery ended.
    public private(set) var tariff: Iso2TariffResult?

    private var chosenTupleId: UInt8 = 1
    private var chargingProfile: ChargingProfileType?

    public init(_ stream: V2GTPStream, _ mode: PowerMode,
                pollDelay: @escaping (UInt64) -> Void = { _ in }) {
        self.stream = stream
        self.mode = mode
        self.pollDelay = pollDelay
    }

    public func run() throws {

        // ── SETUP ──────────────────────────────────────────────────────────
        let setup: SessionSetupResType = try send(SessionSetupReqType(
            eVCCID: [0xAB, 0xCD, 0xEF, 0x01, 0x02, 0x03]))
        sessionSetupCode = setup.responseCode

        let _: ServiceDiscoveryResType = try send(ServiceDiscoveryReqType())

        // EIM only — see the type comment on Plug & Charge.
        let _: PaymentServiceSelectionResType = try send(PaymentServiceSelectionReqType(
            selectedPaymentOption: .ExternalPayment,
            selectedServiceList: SelectedServiceListType(selectedService: [
                SelectedServiceType(serviceID: 1)
            ])))

        // ── AUTH (poll until authorised) ───────────────────────────────────
        while true {
            let res: AuthorizationResType = try send(AuthorizationReqType())
            if res.eVSEProcessing == .Finished { break }
            pollDelay(Self.pollIntervalMs)
        }

        // ── CHARGE PARAMETERS (+ DC cable check / pre-charge) ──────────────
        try runChargeParameterDiscovery()

        if mode == .dc {
            while true {
                let res: CableCheckResType = try send(CableCheckReqType(dC_EVStatus: Self.evStatus()))
                if res.eVSEProcessing == .Finished { break }
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

            let notification: EVSENotification
            if mode == .dc {
                let res: CurrentDemandResType = try send(Self.currentDemand())
                notification = res.dC_EVSEStatus.eVSENotification
            } else {
                let res: ChargingStatusResType = try send(ChargingStatusReqType())
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
    /// renegotiation, because the offer may have changed.
    private func runChargeParameterDiscovery() throws {
        while true {
            let res: ChargeParameterDiscoveryResType = try send(chargeParameterDiscovery())
            if res.eVSEProcessing == .Finished {
                evaluateSchedules(res)
                return
            }
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

    private func send<T>(_ requestBody: BodyBaseType) throws -> T {

        let header  = MessageHeaderType(sessionID: sessionId)
        let request = V2G_Message(header: header, body: BodyType(bodyElement: requestBody))

        try stream.writeFrame(.iso15118_2, Iso15118_2Codec.encode(request))

        let (set, message) = try stream.readFrame()
        guard set == .iso15118_2, let reply = message as? V2G_Message else {
            throw SessionAborted("expected an ISO 15118-2 reply, got \(set).")
        }

        exchanges += 1
        sessionId = reply.header.sessionID   // adopt the station-assigned session id

        guard let body = reply.body.bodyElement as? T else {
            throw SessionAborted("expected a \(T.self), got \(String(describing: reply.body.bodyElement)).")
        }
        return body
    }

    // ── request builders ──────────────────────────────────────────────────

    /// Start carries the smart-charging outcome — the chosen tuple and the PMax-shaped profile;
    /// Renegotiate/Stop reference the tuple without a profile.
    private func powerDelivery(_ progress: ChargeProgress) -> PowerDeliveryReqType {
        PowerDeliveryReqType(chargeProgress: progress,
                             sAScheduleTupleID: chosenTupleId,
                             chargingProfile: progress == .Start ? chargingProfile : nil)
    }

    private func chargeParameterDiscovery() -> ChargeParameterDiscoveryReqType {
        mode == .dc
            ? ChargeParameterDiscoveryReqType(
                requestedEnergyTransferMode: .DC_extended,
                eVChargeParameter: DC_EVChargeParameterType(
                    dC_EVStatus: Self.evStatus(),
                    eVMaximumCurrentLimit: Self.amp(200),
                    eVMaximumVoltageLimit: Self.volt(500),
                    fullSOC: 100, bulkSOC: 80))
            : ChargeParameterDiscoveryReqType(
                requestedEnergyTransferMode: .AC_three_phase_core,
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
