import ExiIso20Common
import V2GDispatch

/// Asserts that an exchange came back as the message it was supposed to. A free generic function
/// rather than a method, because the AC and DC subclasses need it for types from *their* modules,
/// which this one does not import.
internal func expect<T>(_ actualSet: MessageSet, _ message: Any, _ expectedSet: MessageSet) throws -> T {
    guard actualSet == expectedSet, let typed = message as? T else {
        throw SessionAborted("expected a \(T.self) on \(expectedSet), got \(type(of: message)) on \(actualSet).")
    }
    return typed
}

/// The EVCC side of an ISO 15118-20 session, shared between AC and DC. It drives the CommonMessages
/// phases directly and calls the overridable hooks below for the diverging middle, which
/// ``Evcc20Ac``/``Evcc20Dc`` implement — they are the ones that know which codec and which concrete
/// request types their energy-transfer mode uses.
///
/// A port of the C# `Evcc20Base`, checked the same way ``Evcc2`` is: `Evcc20TraceTests` replays the
/// recorded -20 sessions and requires byte-identical requests.
///
/// ## What is not here
///
/// **Plug & Charge, contract provisioning (CertificateInstallation), and price-schedule signature
/// verification.** All three are signature work; the corpus is EIM and a randomised ECDSA signature
/// cannot be compared byte for byte, so porting them now would mean writing crypto against no
/// oracle. Named here rather than silently absent, exactly as in ``Evcc2``.
///
/// **Dynamic control mode.** This EVCC drives Scheduled mode, as the C# original does — it asks for
/// a Scheduled parameter set and sends a Scheduled ScheduleExchange. [V2G20-1600] requires a
/// response's control mode to match the request's, so the two halves are one decision, not two.
open class Evcc20Base {

    internal static let pollIntervalMs: UInt64 = 50
    private static let chargeCycles = 3

    // ISO 15118-20 energy-transfer service ids (Table 204): AC=1, DC=2, AC_BPT=5, DC_BPT=6.
    private static let dcServiceIds: [UInt16] = [2, 6]
    private static let acServiceIds: [UInt16] = [1, 5]

    private let stream: V2GTPStream
    internal let pollDelay: (UInt64) -> Void
    internal let sessionCtx: SessionContext

    /// How many request/response exchanges this session ran.
    public private(set) var exchanges = 0

    /// The energy-transfer service actually negotiated (Table 204); 0 before that phase. Exposed
    /// because which service a session settled on is otherwise invisible from outside — it is what
    /// distinguishes an MCS session from a DC one, the two being identical on the wire otherwise.
    public private(set) var selectedEnergyServiceId: UInt16 = 0

    /// How the session ends: `.Terminate` (default) or `.Pause`.
    public var stopMode: ChargingSession = .Terminate

    /// The station's SessionSetup verdict.
    public private(set) var sessionSetupCode: ResponseCode?

    /// The session id in effect, station-assigned.
    public var sessionId: [UInt8] { sessionCtx.sessionId }

    public init(_ stream: V2GTPStream, clock: @escaping () -> UInt64,
                pollDelay: @escaping (UInt64) -> Void = { _ in }) {
        self.stream = stream
        self.pollDelay = pollDelay
        self.sessionCtx = SessionContext(clock: clock)
    }

    // ── the diverging middle ──────────────────────────────────────────────

    /// Charge-parameter discovery. Runs once, not polled: -20's CPD response carries no
    /// EVSEProcessing field to poll on.
    open func runChargeParameterDiscovery() throws { fatalError("subclass responsibility") }

    /// DC: CableCheck + PreCharge. AC: nothing.
    open func runPreChargeSequence() throws {}

    /// One charge-loop request/response; the base class loops this a fixed number of times.
    open func runChargeLoopIteration() throws { fatalError("subclass responsibility") }

    /// DC: WeldingDetection. AC: nothing.
    open func runPostChargeSequence() throws {}

    /// Which mode this EVCC drives — picks the matching service from the station's catalogue.
    open var energyMode: PowerMode { fatalError("subclass responsibility") }

    /// Energy-transfer service ids this EVCC accepts, best first. Overridable so an MCS vehicle can
    /// ask for the megawatt services instead.
    open var preferredEnergyServiceIds: [UInt16] {
        energyMode == .dc ? Self.dcServiceIds : Self.acServiceIds
    }

    // ── the session ───────────────────────────────────────────────────────

    public func run() throws {

        let setupRes: SessionSetupRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(SessionSetupReq(header: sessionCtx.toCommonHeader(),
                                                       eVCCID: "EVCC01")))
        sessionSetupCode = setupRes.responseCode

        // Adopt the station-assigned SessionID: every subsequent request header must carry it, not
        // the all-zero id SessionSetup opens with (§7.9.2.4). A live Josev run caught this — its
        // SECC strictly rejects a mismatched session id where our loopback one did not.
        sessionCtx.sessionId = setupRes.header.sessionID

        let _: AuthorizationSetupRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(AuthorizationSetupReq(header: sessionCtx.toCommonHeader())))

        // EIM — see the type comment on Plug & Charge.
        while true {
            let request = AuthorizationReq(header: sessionCtx.toCommonHeader(),
                                           selectedAuthorizationService: .EIM,
                                           eIM_AReqAuthorizationMode: EIM_AReqAuthorizationModeType())
            let res: AuthorizationRes = try exchange(.iso20CommonMessages,
                                                     CommonMessagesCodec.encode(request))
            if res.eVSEProcessing == .Finished { break }
            pollDelay(Self.pollIntervalMs)
        }

        // Service negotiation is dynamic: select the service and parameter set the station actually
        // advertises rather than assuming fixed ids. A live Josev run caught the old hardcoded
        // ServiceID=1/ParameterSetID=1 — its DC catalogue offers neither, and our loopback SECC
        // happened to advertise exactly those, which masked it.
        let discovery: ServiceDiscoveryRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(ServiceDiscoveryReq(header: sessionCtx.toCommonHeader())))
        let serviceId = try selectEnergyTransferService(discovery)
        selectedEnergyServiceId = serviceId

        let detail: ServiceDetailRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(ServiceDetailReq(header: sessionCtx.toCommonHeader(),
                                                        serviceID: serviceId)))
        let parameterSetId = try selectParameterSet(detail)

        let _: ServiceSelectionRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(ServiceSelectionReq(
                header: sessionCtx.toCommonHeader(),
                selectedEnergyTransferService: SelectedServiceType(serviceID: serviceId,
                                                                   parameterSetID: parameterSetId))))

        try runChargeParameterDiscovery()

        // MaximumSupportingPoints is schema-bounded to [12, 1024] (the encoder biases by 12); a
        // smaller value underflows on the wire. A live Josev run rejected the earlier 1, which our
        // more lenient SECC had accepted.
        var scheduleRes: ScheduleExchangeRes
        while true {
            scheduleRes = try exchange(.iso20CommonMessages,
                CommonMessagesCodec.encode(ScheduleExchangeReq(
                    header: sessionCtx.toCommonHeader(),
                    maximumSupportingPoints: 12,
                    scheduled_SEReqControlMode: Scheduled_SEReqControlModeType())))
            if scheduleRes.eVSEProcessing == .Finished { break }
            pollDelay(Self.pollIntervalMs)
        }

        try runPreChargeSequence()

        // PowerDelivery(Start) must carry an EVPowerProfile referencing a schedule tuple the station
        // offered (§7.9.2.4). A live Josev run rejected the earlier absent profile; ours did not.
        let _: PowerDeliveryRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(PowerDeliveryReq(
                header: sessionCtx.toCommonHeader(), eVProcessing: .Finished,
                chargeProgress: .Start, eVPowerProfile: Self.buildEvPowerProfile(scheduleRes))))

        for _ in 0 ..< Self.chargeCycles {
            try runChargeLoopIteration()
            pollDelay(Self.pollIntervalMs)
        }

        let _: PowerDeliveryRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(PowerDeliveryReq(
                header: sessionCtx.toCommonHeader(), eVProcessing: .Finished,
                chargeProgress: .Stop)))

        try runPostChargeSequence()

        let _: SessionStopRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(SessionStopReq(header: sessionCtx.toCommonHeader(),
                                                      chargingSession: stopMode)))
    }

    /// Sends one already-encoded request and awaits its reply. The undiscriminated pair, because the
    /// AC/DC subclasses need their own result types — ``expect(_:_:_:)`` is what narrows it.
    internal func exchangeRaw(_ expectedSet: MessageSet,
                              _ payload: [UInt8]) throws -> (set: MessageSet, message: Any) {
        try stream.writeFrame(expectedSet, payload)
        let reply = try stream.readFrame()
        exchanges += 1
        return reply
    }

    private func exchange<T>(_ expectedSet: MessageSet, _ payload: [UInt8]) throws -> T {
        let (set, message) = try exchangeRaw(expectedSet, payload)
        return try expect(set, message, expectedSet)
    }

    /// The first advertised service whose id matches this EVCC's mode (DC → 2/6, AC → 1/5), else the
    /// first offered — a simplified station may advertise a single generic id.
    private func selectEnergyTransferService(_ res: ServiceDiscoveryRes) throws -> UInt16 {
        let offered = res.energyTransferServiceList.service
        guard !offered.isEmpty else {
            throw SessionAborted("ServiceDiscovery: the station advertised no energy-transfer service.")
        }
        let preferred = preferredEnergyServiceIds
        return (offered.first { preferred.contains($0.serviceID) } ?? offered[0]).serviceID
    }

    /// Prefers a Scheduled control-mode set (ControlMode=1, matching the Scheduled ScheduleExchange
    /// this EVCC drives), else the first offered.
    private func selectParameterSet(_ res: ServiceDetailRes) throws -> UInt16 {
        let sets = res.serviceParameterList.parameterSet
        guard !sets.isEmpty else {
            throw SessionAborted("ServiceDetail: the station advertised no parameter set.")
        }
        let scheduled = sets.first { set in
            set.parameter.contains { $0.name == "ControlMode" && $0.intValue == 1 }
        }
        return (scheduled ?? sets[0]).parameterSetID
    }

    /// The Scheduled-mode EVPowerProfile that PowerDelivery(Start) must carry: the first schedule
    /// tuple the station returned, and one echoed power-schedule entry. Falls back to tuple 1 if the
    /// station returned no Scheduled control mode.
    private static func buildEvPowerProfile(_ scheduleRes: ScheduleExchangeRes) -> EVPowerProfileType {

        let tupleId = scheduleRes.scheduled_SEResControlMode?.scheduleTuple.first?.scheduleTupleID ?? 1

        return EVPowerProfileType(
            timeAnchor: 0,
            // PowerToleranceAcceptance is schema-optional but Josev's model requires it — its SECC
            // rejects an absent one, and a live run needed it set.
            scheduled_EVPPTControlMode: Scheduled_EVPPTControlModeType(
                selectedScheduleTupleID: tupleId,
                powerToleranceAcceptance: .PowerToleranceConfirmed),
            eVPowerProfileEntries: EVPowerProfileEntryListType(eVPowerProfileEntry: [
                // one 1-hour entry at 10 kW (Power = 10 × 10³ W)
                PowerScheduleEntryType(duration: 3600, power: RationalNumberType(exponent: 3, value: 10))
            ]))
    }
}
