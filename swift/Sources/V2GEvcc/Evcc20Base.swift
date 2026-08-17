import CryptoKit
import Foundation
import V2GMetering
import ExiIso20AC
import ExiIso20Common
import ExiIso20DC
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
/// **Contract provisioning (CertificateInstallation).** Signature work with no recorded oracle yet;
/// named here rather than silently absent, exactly as in ``Evcc2``.
///
/// Price-schedule signature verification *is* here now — ``Iso20PriceScheduleCheck``, held to
/// `PriceSchedule.signature.vectors.json` rather than to a trace, because that verdict never reaches
/// the wire. Plug & Charge is here too, held to the signed traces, and Dynamic control mode to the
/// `iso20-*-eim-dynamic` ones.
open class Evcc20Base {

    internal static let pollIntervalMs: UInt64 = 50
    private static let chargeCycles = 3

    // ISO 15118-20 energy-transfer service ids (Table 204): AC=1, DC=2, AC_BPT=5, DC_BPT=6, MCS=8,
    // MCS_BPT=9. MCS is the DC message set under different ids, so it is *drivable* by a DC EVCC
    // even when it is not what that EVCC would ask for first — which is the difference the two
    // pairs of lists carry.
    private static let dcServiceIds: [UInt16] = [2, 6]
    private static let acServiceIds: [UInt16] = [1, 5]
    private static let dcDrivableIds: [UInt16] = [2, 6, 8, 9]
    private static let acDrivableIds: [UInt16] = [1, 5]

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
    // Qualified since this file now also imports the AC and DC message sets, each of which has a
    // ResponseCode of its own.
    /// How long a phase may keep answering `EVSEProcessing = Ongoing` before the session ends —
    /// 60 s, ISO 15118's EVCC ongoing timeout. See `OngoingGuard` for the live run that required it.
    public var ongoingTimeoutMillis: UInt64 = 60_000

    public private(set) var sessionSetupCode: ExiIso20Common.ResponseCode?

    /// The §7.9.2.5 verdict over a signed `AbsolutePriceSchedule`; nil when the offer carried none —
    /// which is not a failure, see ``Iso20PriceScheduleCheck``.
    public private(set) var tariff: Iso20TariffResult?

    /// How many service renegotiations this session ran ([V2G20-1477]).
    public private(set) var renegotiations = 0

    fileprivate var renegotiationRequested = false

    /// Record that a charge-loop response asked for a **service renegotiation** — the station puts
    /// `EvseNotification.ServiceRenegotiation` in its EVSEStatus ([V2G20-1477]).
    ///
    /// Called by the AC and DC loops: the EVSEStatus is a different generated type in each message
    /// set, and this class imports neither. Acted on where the charging phase ends rather than here —
    /// a renegotiation has to finish the iteration and open the contactor before it can go anywhere.
    public func noteRenegotiationRequest(_ requested: Bool) {
        if requested { renegotiationRequested = true }
    }

    /// The eMSP's public key, when the app has one. Without it the digest half is still checked and
    /// reported; the ECDSA half is not attempted.
    public var tariffVerifyKey: P521.Signing.PublicKey?

    /// The session id in effect, station-assigned.
    public var sessionId: [UInt8] { sessionCtx.sessionId }

    /// Contract credentials. When set and the station offers PnC with a challenge, the session
    /// authorizes with a signed AuthorizationReq instead of EIM.
    public var pnc: PncEvccOptions?

    /// OEM-provisioning credentials. When set — and the station announces
    /// `CertificateInstallationService` — the EVCC runs the contract-provisioning exchange before
    /// authorization. Nil (the default) skips it.
    public var certInstallRequest: CertInstallEvccOptions?

    /// The contract certificate (DER) installed via CertificateInstallation, once recovered.
    public private(set) var installedContractCertificate: [UInt8]?

    /// The unwrapped contract private key (P-521). GCM's tag check already refused a wrong one, so
    /// unlike -2 there is no certificate comparison standing behind this.
    public private(set) var installedContractKey: P521.Signing.PrivateKey?

    /// The full verdict over the CertificateInstallationRes, once one has arrived.
    public private(set) var installedContractVerdict: Iso20ContractVerdict?

    /// Whether that response's CPS signature held — both halves of ``installedContractVerdict``.
    public private(set) var installedContractSignatureOk = false

    /// How this session authorized: `"eim"`, or `"pnc-signed"`.
    public private(set) var authorizationMode = "eim"

    /// Drive the session in **Dynamic** control mode (ControlMode = 2) instead of Scheduled.
    ///
    /// The mode is a property of the whole session, not of one message — it touches the parameter
    /// set selected out of `ServiceDetailRes`, `ScheduleExchangeReq`'s control-mode arm, the
    /// `EVPowerProfile` in PowerDelivery(Start), and the charge loop's request arm (``Evcc20Dc``/
    /// ``Evcc20Ac``). Answering in kind is [V2G20-1600]; asking in kind is the same rule read from
    /// the other end. The substantive difference is who plans: in Scheduled mode the EV picks a
    /// schedule tuple and commits to it, in Dynamic mode it states energy needs and a departure
    /// time and lets the station steer.
    ///
    /// Held to the `iso20-dc-eim-dynamic` / `iso20-ac-eim-dynamic` traces — recorded the day the C#
    /// EVCC learned the mode (2026-08-03), precisely so the ports could not claim it unchecked.
    public var preferDynamicControlMode = false

    /// When the car leaves, as a -20 `DepartureTime` (seconds from the session's time anchor).
    /// Dynamic mode only: it is the deadline the station schedules against.
    public var departureTime: UInt32 = 3600

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

    /// The vehicle's own energy counter — what this EV thinks it took, kept independently of what the
    /// station reports (`docs/CONCEPT.md` §4.2/§4.3).
    ///
    /// On the base rather than per set: the counter is the vehicle's, and AC and DC differ only in
    /// what a sample is worth. Each subclass takes its own sample in ``runChargeLoopIteration()``,
    /// where it knows which field carries the EV's view.
    public let meter = EvMeter()

    /// DC: WeldingDetection. AC: nothing.
    open func runPostChargeSequence() throws {}

    /// Which mode this EVCC drives — picks the matching service from the station's catalogue.
    open var energyMode: PowerMode { fatalError("subclass responsibility") }

    /// Energy-transfer service ids this EVCC accepts, best first. Overridable so an MCS vehicle can
    /// ask for the megawatt services instead.
    open var preferredEnergyServiceIds: [UInt16] {
        energyMode == .dc ? Self.dcServiceIds : Self.acServiceIds
    }

    /// Every service id whose messages this EVCC can actually speak — the ones on its own message
    /// set. Wider than ``preferredEnergyServiceIds`` on purpose: a megawatt truck at an ordinary DC
    /// charger should take the DC service rather than refuse, and a DC car at an AC-only station
    /// has nothing to take.
    open var drivableEnergyServiceIds: [UInt16] {
        energyMode == .dc ? Self.dcDrivableIds : Self.acDrivableIds
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

        let authSetup: AuthorizationSetupRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(AuthorizationSetupReq(header: sessionCtx.toCommonHeader())))

        // A car that needs a contract asks for one here — before it authorizes, and before service
        // discovery. That position is the whole difference from -2, where provisioning is a
        // value-added service to be discovered and selected first, and it is the one thing about this
        // exchange that only a recorded session can state.
        if let oem = certInstallRequest, authSetup.certificateInstallationService {
            try runCertificateInstallation(oem)
        }

        // Plug & Charge only if we have credentials AND the station offers it with a challenge;
        // anything else falls back to EIM. Built once — the challenge does not change across polls,
        // so re-signing per poll would only burn entropy. The *header* is still rebuilt every time,
        // because -20 timestamps it; getting that split backwards is invisible until bytes are
        // checked.
        let buildAuthorizationReq = try makeAuthorizationReqBuilder(authSetup)

        let authGuard = OngoingGuard("Authorization", limitMillis: ongoingTimeoutMillis)
        while true {
            let res: AuthorizationRes = try exchange(.iso20CommonMessages, buildAuthorizationReq())
            if res.eVSEProcessing == .Finished { break }
            try authGuard.tick()
            pollDelay(Self.pollIntervalMs)
        }

        try runServiceSelection()

        // ── the charging phase, which a service renegotiation sends round again ────────────────
        //
        // [V2G20-1477]: the station asks by putting `EvseNotification.ServiceRenegotiation` in a
        // charge-loop response's EVSEStatus. The EV stops power delivery, sends
        // `SessionStopReq(ServiceRenegotiation)` — which does NOT end the session — and both sides
        // return to ServiceDiscovery. Everything from service selection down runs again, with charge
        // parameters and the schedule offer negotiated afresh; authorization does not, and must not.
        //
        // A loop rather than a single re-entry: our station signals once, but nothing in the standard
        // says a station may only ask once.
        while true {

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
                    dynamic_SEReqControlMode:
                        preferDynamicControlMode ? dynamicScheduleRequest() : nil,
                    scheduled_SEReqControlMode:
                        preferDynamicControlMode ? nil : Scheduled_SEReqControlModeType())))
            if scheduleRes.eVSEProcessing == .Finished { break }
            pollDelay(Self.pollIntervalMs)
        }

        // §7.9.2.5's -20 half. Stays nil when the offer carries no AbsolutePriceSchedule at all,
        // which is the ordinary case — most stations send the compact PriceLevelSchedule instead,
        // and reporting an unsigned verdict for them would accuse them of failing a check nobody
        // asked them to pass.
        tariff = Iso20PriceScheduleCheck.evaluate(scheduleRes,
                                                  headerSignature: scheduleRes.header.signature,
                                                  verifyKey: tariffVerifyKey)

        try runPreChargeSequence()

        // PowerDelivery(Start) must carry an EVPowerProfile referencing a schedule tuple the station
        // offered (§7.9.2.4). A live Josev run rejected the earlier absent profile; ours did not.
        let _: PowerDeliveryRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(PowerDeliveryReq(
                header: sessionCtx.toCommonHeader(), eVProcessing: .Finished,
                chargeProgress: .Start, eVPowerProfile: buildEvPowerProfile(scheduleRes))))

        for _ in 0 ..< Self.chargeCycles {
            try runChargeLoopIteration()
            pollDelay(Self.pollIntervalMs)
        }

        // Power off either way: a renegotiation stops delivery too, and the contactor must be open
        // before the session goes back to talking about services.
        let _: PowerDeliveryRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(PowerDeliveryReq(
                header: sessionCtx.toCommonHeader(), eVProcessing: .Finished,
                chargeProgress: .Stop)))

        if !renegotiationRequested { break }

        renegotiationRequested = false
        renegotiations += 1

        // The one SessionStopReq that does not stop the session. Sending `stopMode` here instead
        // would end it for real, which is the single most consequential thing to get wrong here.
        let _: SessionStopRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(SessionStopReq(header: sessionCtx.toCommonHeader(),
                                                      chargingSession: .ServiceRenegotiation)))

        try runServiceSelection()

        }

        try runPostChargeSequence()

        let _: SessionStopRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(SessionStopReq(header: sessionCtx.toCommonHeader(),
                                                      chargingSession: stopMode)))
    }

    /// ServiceDiscovery → ServiceDetail → ServiceSelection.
    ///
    /// Its own method because a **service renegotiation** re-enters the session exactly here
    /// ([V2G20-1477]) — the station puts the phase back to ServiceDiscovery, not to the top.
    /// Authorization has already happened and is emphatically not repeated: a car that re-authorized
    /// mid-session would be telling the station it might be a different car.
    ///
    /// Service negotiation is dynamic: select the service and parameter set the station actually
    /// advertises rather than assuming fixed ids. A live Josev run caught the old hardcoded
    /// ServiceID=1/ParameterSetID=1 — its DC catalogue offers neither, and our loopback SECC happened
    /// to advertise exactly those, which masked it.
    private func runServiceSelection() throws {

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
    }

    /// Runs the -20 contract-provisioning exchange: sends the signed OEM provisioning chain (Id
    /// "id1", Josev-interop signature form over the chain's EXI fragment), then judges the response's
    /// CPS signature over `SignedInstallationData` and ECDH-unwraps the issued contract private key.
    ///
    /// One reference where -2 has four, and no certificate check behind the unwrap: -20 signs the
    /// whole `SignedInstallationData` as a unit, and its AES-GCM tag refuses a wrong key outright.
    /// See ``Iso20ContractCheck`` and ``ContractProvisioning20``.
    private func runCertificateInstallation(_ oem: CertInstallEvccOptions) throws {

        let chain = SignedCertificateChainType(
            id: "id1", certificate: oem.oemCertificate,
            subCertificates: oem.oemSubCertificates.isEmpty
                ? nil : SubCertificatesType(certificate: oem.oemSubCertificates))

        let signature = try XmlDsigInterop.sign20(
            "id1", CommonMessagesCodec.encodeFragment_OEMProvisioningCertificateChain(chain),
            oem.oemSignKey)

        var header = sessionCtx.toCommonHeader()
        header.signature = signature

        let res: CertificateInstallationRes = try exchange(.iso20CommonMessages,
            CommonMessagesCodec.encode(CertificateInstallationReq(
                header: header,
                oEMProvisioningCertificateChain: chain,
                listOfRootCertificateIDs: ListOfRootCertificateIDsType(rootCertificateID: [
                    X509IssuerSerialType(x509IssuerName: "CN=V2GRootCA (dev)", x509SerialNumber: 1)
                ]),
                maximumContractCertificateChains: 1)))

        let verdict = Iso20ContractCheck.evaluate(res, headerSignature: res.header.signature)
        installedContractVerdict     = verdict
        installedContractSignatureOk = verdict.digestOk && verdict.signatureOk

        if let wrapped = res.signedInstallationData.sECP521_EncryptedPrivateKey {
            installedContractKey = try ContractProvisioning20.recoverContractKey(
                oemKey: oem.oemKeyAgreement,
                dhPublicKey: res.signedInstallationData.dHPublicKey,
                encryptedPrivateKey: wrapped)
            installedContractCertificate = res.signedInstallationData.contractCertificateChain.certificate
        }
    }

    private func makeAuthorizationReqBuilder(_ authSetup: AuthorizationSetupRes) throws -> () -> [UInt8] {

        if let credentials = pnc,
           authSetup.authorizationServices.contains(.PnC),
           let pncSetup = authSetup.pnC_ASResAuthorizationMode {

            let pncMode = PnC_AReqAuthorizationModeType(
                id: "id1",
                genChallenge: pncSetup.genChallenge,
                contractCertificateChain: ContractCertificateChainType(
                    certificate: credentials.contractCertificate,
                    subCertificates: SubCertificatesType(certificate: credentials.subCertificates)))

            let signature = try XmlDsigInterop.sign20(
                "id1", CommonMessagesCodec.encodeFragment_PnC_AReqAuthorizationMode(pncMode),
                credentials.contractKey)

            authorizationMode = "pnc-signed"

            return { [sessionCtx] in
                var header = sessionCtx.toCommonHeader()
                header.signature = signature
                return CommonMessagesCodec.encode(AuthorizationReq(
                    header: header, selectedAuthorizationService: .PnC,
                    pnC_AReqAuthorizationMode: pncMode))
            }
        }

        // EIM is what is left, and it too has to be on offer: a station that advertises PnC only is
        // saying it cannot authorize this car, and hearing that at AuthorizationSetup is better
        // than hearing FAILED at AuthorizationReq.
        guard authSetup.authorizationServices.contains(.EIM) else {
            throw SessionAborted(
                "AuthorizationSetup: the station offers no EIM authorization "
              + "(offered: \(authSetup.authorizationServices.map { "\($0)" }.joined(separator: ", ")))"
              + (pnc == nil ? " and this EVCC has no contract certificate." : "."))
        }

        return { [sessionCtx] in
            CommonMessagesCodec.encode(AuthorizationReq(
                header: sessionCtx.toCommonHeader(), selectedAuthorizationService: .EIM,
                eIM_AReqAuthorizationMode: EIM_AReqAuthorizationModeType()))
        }
    }

    /// Sends one already-encoded request and awaits its reply. The undiscriminated pair, because the
    /// AC/DC subclasses need their own result types — ``expect(_:_:_:)`` is what narrows it.
    internal func exchangeRaw(_ expectedSet: MessageSet,
                              _ payload: [UInt8]) throws -> (set: MessageSet, message: Any) {
        try stream.writeFrame(expectedSet, payload)
        let reply = try stream.readFrame()
        try refuseOnFailure(reply.message)
        exchanges += 1
        return reply
    }

    /// Ends the session when the station answers with a code from the `FAILED` family.
    ///
    /// **Found live, not by reasoning.** Until 2026-08-01 no -20 EVCC in this repository looked at a
    /// response code at all — `expect` checks the message set and the type, and the cable-check loop
    /// watched only `evseProcessing`. eVDriveFlow answered `DC_CableCheckRes` with `FAILED` and the C#
    /// car went on to PreCharge, PowerDelivery and into the charge loop; this port had the same hole,
    /// and the trace corpus could not show it, because our own SECC never says FAILED.
    ///
    /// `OK*` and `WARNING*` continue — a warning is explicitly the code for "something is off and the
    /// session goes on" — and `FAILED*` terminates. The comparison is on the raw value because the
    /// enumeration is ordered by family in the schema (OK, then WARNING, then FAILED), which
    /// `Evcc20FailureTests.testResponseCodeFamiliesAreContiguousAndOrdered` pins.
    ///
    /// Aborts rather than sending SessionStop: a FAILED response is the station saying it is done, and
    /// a further message invites a second error on a session that already has one.
    /// Internal rather than private: exercised directly by `Evcc20FailureTests`.
    internal func refuseOnFailure(_ message: Any) throws {

        var failure: String?

        if let response = message as? ExiIso20Common.V2GResponseType,
           response.responseCode.rawValue >= ExiIso20Common.ResponseCode.FAILED.rawValue {
            failure = String(describing: response.responseCode)
        }
        else if let response = message as? ExiIso20AC.V2GResponseType,
                response.responseCode.rawValue >= ExiIso20AC.ResponseCode.FAILED.rawValue {
            failure = String(describing: response.responseCode)
        }
        else if let response = message as? ExiIso20DC.V2GResponseType,
                response.responseCode.rawValue >= ExiIso20DC.ResponseCode.FAILED.rawValue {
            failure = String(describing: response.responseCode)
        }

        if let failure {
            throw SessionAborted(
                "the station answered \(type(of: message)) with \(failure); the session ends here.")
        }

    }

    private func exchange<T>(_ expectedSet: MessageSet, _ payload: [UInt8]) throws -> T {
        let (set, message) = try exchangeRaw(expectedSet, payload)
        return try expect(set, message, expectedSet)
    }

    /// Picks the energy-transfer service to select from the station's advertised list: the best one
    /// this EVCC asks for, else any other it can actually drive, else a refusal.
    ///
    /// The old fallback was `offered[0]`, which for a DC car at an AC-only station selects the AC
    /// service and then sends the next request on the DC set — refused two exchanges later, for a
    /// reason that no longer names the cause. Falling back *within* the message set keeps the case
    /// this is really for (a megawatt truck at an ordinary DC charger) and drops the one it never
    /// was (found in the C# sweep of 2026-08-03).
    private func selectEnergyTransferService(_ res: ServiceDiscoveryRes) throws -> UInt16 {

        let offered = res.energyTransferServiceList.service
        guard !offered.isEmpty else {
            throw SessionAborted("ServiceDiscovery: the station advertised no energy-transfer service.")
        }

        let preferred = preferredEnergyServiceIds
        let drivable  = drivableEnergyServiceIds
        let match = offered.first { preferred.contains($0.serviceID) }
                 ?? offered.first { drivable.contains($0.serviceID) }

        guard let match else {
            throw SessionAborted(
                "ServiceDiscovery: the station offers no \(energyMode == .dc ? "DC" : "AC") "
              + "energy-transfer service (wanted \(preferred.map(String.init).joined(separator: "/")), "
              + "offered \(offered.map { String($0.serviceID) }.joined(separator: ", "))).")
        }
        return match.serviceID
    }

    /// Picks the parameter set whose `ControlMode` matches the mode this EVCC is about to drive
    /// (1 = Scheduled, 2 = Dynamic), else the first offered. A Dynamic EV at a Scheduled-only
    /// station is refused by name instead: the selected set is what the station answers in kind
    /// against for the rest of the session, so a silent fallback would negotiate one mode and then
    /// ask in the other.
    private func selectParameterSet(_ res: ServiceDetailRes) throws -> UInt16 {

        let sets = res.serviceParameterList.parameterSet
        guard !sets.isEmpty else {
            throw SessionAborted("ServiceDetail: the station advertised no parameter set.")
        }

        let wanted: Int32 = preferDynamicControlMode ? 2 : 1
        let match = sets.first { set in
            set.parameter.contains { $0.name == "ControlMode" && $0.intValue == wanted }
        }

        if match == nil && preferDynamicControlMode {
            throw SessionAborted("ServiceDetail: Dynamic control mode was requested, but the station "
                               + "offers no parameter set with ControlMode = 2.")
        }

        return (match ?? sets[0]).parameterSetID
    }

    /// The Dynamic-mode ScheduleExchange request: a departure time and what the battery needs,
    /// instead of a schedule to choose from. The three energy fields are **mandatory** in this arm
    /// (they are optional in the Scheduled one), which is the schema saying the same thing: a
    /// station can only steer if it knows the target.
    private func dynamicScheduleRequest() -> Dynamic_SEReqControlModeType {
        Dynamic_SEReqControlModeType(
            departureTime:          departureTime,
            minimumSOC:             30,
            targetSOC:              80,
            eVTargetEnergyRequest:  RationalNumberType(exponent: 3, value: 30),   // 30 kWh
            eVMaximumEnergyRequest: RationalNumberType(exponent: 3, value: 60),   // 60 kWh
            eVMinimumEnergyRequest: RationalNumberType(exponent: 3, value: 10))   // 10 kWh
    }

    /// The EVPowerProfile that PowerDelivery(Start) must carry. Scheduled mode selects the first
    /// schedule tuple the station returned and echoes one power-schedule entry (falling back to
    /// tuple 1 if the station returned no Scheduled control mode); Dynamic mode has no tuple to
    /// point at, so its control-mode element is empty — the profile is then only the EV's own power
    /// curve.
    private func buildEvPowerProfile(_ scheduleRes: ScheduleExchangeRes) -> EVPowerProfileType {

        let tupleId = scheduleRes.scheduled_SEResControlMode?.scheduleTuple.first?.scheduleTupleID ?? 1

        return EVPowerProfileType(
            timeAnchor: 0,
            dynamic_EVPPTControlMode:
                preferDynamicControlMode ? Dynamic_EVPPTControlModeType() : nil,
            // PowerToleranceAcceptance is schema-optional but Josev's model requires it — its SECC
            // rejects an absent one, and a live run needed it set.
            scheduled_EVPPTControlMode:
                preferDynamicControlMode ? nil : Scheduled_EVPPTControlModeType(
                    selectedScheduleTupleID: tupleId,
                    powerToleranceAcceptance: .PowerToleranceConfirmed),
            eVPowerProfileEntries: EVPowerProfileEntryListType(eVPowerProfileEntry: [
                // one 1-hour entry at 10 kW (Power = 10 × 10³ W)
                PowerScheduleEntryType(duration: 3600, power: RationalNumberType(exponent: 3, value: 10))
            ]))
    }
}
