import XCTest
@testable import V2GEvcc

/// The Swift EVCC against the C# one, byte for byte — the third implementation held to the same
/// four recorded sessions.
///
/// Unlike the codecs, a state machine has no reference implementation and no vector corpus for
/// *behaviour*, so "it ran to completion" would prove nothing about what it actually said. Replaying
/// a recorded session and requiring identical requests turns the question into the one the codec
/// gates already answer: are these bytes the right bytes.
///
/// What it cannot do is catch a bug the C# EVCC has too — see `SessionTrace.cs`. C# is the
/// implementation that earned the live-interop fixes against Josev, which makes it a defensible
/// reference and not a correct one.
final class EvccTraceTests: XCTestCase {

    /// The instant the corpus was recorded. -20 puts a timestamp in **every** message header, so
    /// without pinning the clock to the recording's own value not one -20 frame would match. That is
    /// not a quirk of the test: it is why ``SessionContext`` takes a clock instead of reading one.
    private let recordedAt: () -> UInt64 = { 1_767_225_600 }

    // ── ISO 15118-2 ───────────────────────────────────────────────────────

    private func replay2(_ name: String, _ mode: PowerMode) throws -> (TraceReplay, Evcc2) {
        let replay = TraceReplay(try SessionTrace.load(name))
        let stream = V2GTPStream(replay)
        try SapHandshake.runEvccSide(stream, .iso15118_2, mode)
        let evcc = Evcc2(stream, mode)
        try evcc.run()
        return (replay, evcc)
    }

    func testAcIso2SessionMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay2("iso2-ac-eim", .ac)
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    func testDcIso2SessionMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay2("iso2-dc-eim", .dc)
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    /// The smart-charging outcome, read back from the state machine rather than off the wire. The
    /// bytes already pin it — `PowerDeliveryReq(Start)` carries both the tuple id and the profile —
    /// but a failure there says "byte 31 differs", and this says which decision went wrong.
    func testTheEvChoosesATupleAndShapesAProfileToIt() throws {
        let (_, evcc) = try replay2("iso2-ac-eim", .ac)
        let tariff = try XCTUnwrap(evcc.tariff, "ChargeParameterDiscovery finished without a verdict")
        XCTAssertGreaterThan(tariff.tuplesOffered, 0)
        XCTAssertGreaterThan(tariff.profileEntries, 0,
            "a chosen tuple with no profile entries would send an empty profile")
    }

    // ── ISO 15118-20 ──────────────────────────────────────────────────────

    private func replay20(_ name: String, _ mode: PowerMode,
                          preferDynamic: Bool = false) throws -> (TraceReplay, Evcc20Base) {
        let replay = TraceReplay(try SessionTrace.load(name))
        let stream = V2GTPStream(replay)
        try SapHandshake.runEvccSide(stream, .iso15118_20, mode)
        let evcc: Evcc20Base = mode == .dc
            ? Evcc20Dc(stream, clock: recordedAt)
            : Evcc20Ac(stream, clock: recordedAt)
        evcc.preferDynamicControlMode = preferDynamic
        try evcc.run()
        return (replay, evcc)
    }

    func testAcIso20SessionMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay20("iso20-ac-eim", .ac)
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) of the recorded exchanges")
    }

    func testDcIso20SessionMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay20("iso20-dc-eim", .dc)
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) of the recorded exchanges")
    }

    // ── Dynamic control mode ──────────────────────────────────────────────
    //
    // Recorded 2026-08-03, the day the C# EVCC learned the mode, precisely so the ports could not
    // claim it unchecked: the mode touches the parameter set, ScheduleExchange, the EVPowerProfile
    // and the charge loop, and every one of them is in these bytes.

    func testTheDcDynamicSessionMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay20("iso20-dc-eim-dynamic", .dc, preferDynamic: true)
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) of the recorded exchanges")
    }

    func testTheAcDynamicSessionMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay20("iso20-ac-eim-dynamic", .ac, preferDynamic: true)
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) of the recorded exchanges")
    }

    /// The negative, without which the two tests above would also pass on a flag that is read
    /// nowhere — if Scheduled and Dynamic produced the same bytes, the Dynamic traces would prove
    /// nothing. Diverges at ServiceSelectionReq, the first message the mode reaches (the
    /// ControlMode = 2 parameter set).
    func testWithoutTheFlagTheDynamicTraceDiverges() throws {
        XCTAssertThrowsError(try replay20("iso20-dc-eim-dynamic", .dc)) { error in
            XCTAssertTrue(String(describing: error).contains("ServiceSelectionReq"),
                          String(describing: error))
        }
    }

    /// Which energy-transfer service the negotiation settled on: DC=2, AC=1 (Table 204). The wire
    /// pins it inside ServiceSelectionReq; this names it.
    func testTheNegotiationSettlesOnTheServiceForTheMode() throws {
        let (_, dc) = try replay20("iso20-dc-eim", .dc)
        let (_, ac) = try replay20("iso20-ac-eim", .ac)
        XCTAssertEqual(dc.selectedEnergyServiceId, 2)
        XCTAssertEqual(ac.selectedEnergyServiceId, 1)
    }

    /// A -20 session really does leave CommonMessages. Byte-exactness already guarantees it, but
    /// only as a consequence — this says it in the terms the bug would be described in, so a future
    /// failure reads as "it never entered the DC set" rather than "byte 3 differs".
    ///
    /// Worth knowing how narrow that distinction is on the wire: 0x8003 (AC) and 0x8004 (DC) share
    /// their high byte, so two whole grammars are seven bits apart.
    func testTheDcSessionCrossesIntoTheDcMessageSet() throws {

        let trace = try SessionTrace.load("iso20-dc-eim")
        let payloadTypes = Set(trace.exchanges.map { $0.request.payloadType })

        XCTAssertTrue(payloadTypes.contains("0x8002"), "no CommonMessages frame")
        XCTAssertTrue(payloadTypes.contains("0x8004"),
                      "no DC frame — the session never left CommonMessages")
        XCTAssertFalse(payloadTypes.contains("0x8003"),
                       "an AC frame has no business in a DC session")
    }

    // ── the harness itself ────────────────────────────────────────────────

    /// The harness fails when it should. Without this, everything above being green could equally
    /// mean the comparison never compares anything — the failure mode a corpus check is most prone
    /// to, and the one that is invisible from a passing suite.
    func testAnAlteredRequestIsRejected() throws {

        let trace  = try SessionTrace.load("iso2-ac-eim")
        let replay = TraceReplay(trace)

        // Exchange 0 is the SAP handshake; tamper with the first real session message, so this
        // proves a *session* frame is compared and not merely the opening one.
        try replay.write(trace.exchanges[0].request.bytes)

        var tampered = trace.exchanges[1].request.bytes
        tampered[tampered.count - 1] ^= 0x01

        XCTAssertThrowsError(try replay.write(tampered)) { error in
            let text = String(describing: error)
            XCTAssertTrue(text.contains("exchange 1"), text)
            XCTAssertTrue(text.contains("first difference at"), text)
        }
    }

    // ── Plug & Charge ─────────────────────────────────────────────────────

    /// Plug & Charge, -2: PaymentDetails with the contract chain, a signed AuthorizationReq, and a
    /// signed MeteringReceiptReq for every receipt the station demands — three signed requests in
    /// one session, each matching the recording once its signature is substituted and each verifying
    /// on its own.
    ///
    /// `meteringReceiptsSent` is asserted because it is the one thing a session-level check can see
    /// that a byte comparison cannot express: a station only asks for receipts under Contract, so a
    /// non-zero count is the clearest single sign that PnC really ran rather than quietly falling
    /// back to EIM.
    func testAcIso2PncSessionMatchesTheRecording() throws {

        let trace  = try SessionTrace.load("iso2-ac-pnc")
        let replay = TraceReplay(trace)
        let stream = V2GTPStream(replay)

        try SapHandshake.runEvccSide(stream, .iso15118_2, .ac)
        let evcc = Evcc2(stream, .ac)
        evcc.pnc = try PncMaterial.options
        try evcc.run()

        XCTAssertTrue(replay.complete, "replayed \(replay.replayed) of \(trace.exchanges.count) exchanges")
        XCTAssertEqual(evcc.authorizationMode, "pnc-signed",
            "the session completed but authorized via EIM — then nothing signed was compared")
        XCTAssertGreaterThan(evcc.meteringReceiptsSent, 0,
            "no metering receipt was sent, so the second signed message type never ran")
    }

    /// Plug & Charge, -20: the signed AuthorizationReq, whose signature covers the
    /// PnC_AReqAuthorizationMode fragment rather than the request body.
    func testDcIso20PncSessionMatchesTheRecording() throws {

        let trace  = try SessionTrace.load("iso20-dc-pnc")
        let replay = TraceReplay(trace)
        let stream = V2GTPStream(replay)

        try SapHandshake.runEvccSide(stream, .iso15118_20, .dc)
        let evcc = Evcc20Dc(stream, clock: recordedAt)
        evcc.pnc = try PncMaterial.options
        try evcc.run()

        XCTAssertTrue(replay.complete, "replayed \(replay.replayed) of \(trace.exchanges.count) exchanges")
        XCTAssertEqual(evcc.authorizationMode, "pnc-signed")
    }

    /// A Common Name that cannot be an eMAID is refused before the session opens.
    ///
    /// ISO 15118-2 constrains `eMAIDType` to 14–15 characters, and nothing enforced it: the generated
    /// codec does not apply string-length facets, so a 19-character CN travelled in this repository's
    /// own recorded PnC session, accepted by all three back ends, until this port got an X.509 reader
    /// that checked the length the schema states.
    ///
    /// The rule is **-2's**, not a certificate profile's — ISO 15118-20 never sends the eMAID from
    /// the certificate, so ``Evcc20Base`` deliberately does not check, and the credential itself does
    /// not refuse. An earlier draft here did refuse it, and was wrong in exactly that way.
    func testACommonNameThatCannotBeAnEmaidIsRefusedBeforeTheSessionOpens() throws {

        let replay = TraceReplay(try SessionTrace.load("iso2-ac-pnc"))
        let evcc = Evcc2(V2GTPStream(replay), .ac)

        // The credential itself is accepted — it is usable for -20 — and carries no eMAID.
        let credential = try PncEvccOptions(
            contractCertificate: PncMaterial.certificateWithUnusableEmaid,
            subCertificates: [PncMaterial.certificateWithUnusableEmaid],
            contractKey: PncMaterial.signer)
        XCTAssertNil(credential.emaid, "a 19-character Common Name is not an eMAID")

        evcc.pnc = credential

        // The -2 session refuses it, before any byte reaches the transport.
        XCTAssertThrowsError(try evcc.run()) { error in
            XCTAssertTrue(String(describing: error).contains("14 or 15"), String(describing: error))
        }
    }

    /// **The signature C# produced verifies under this port's own verification path.**
    ///
    /// This closes a blind spot the two tests above cannot, and it is worth spelling out because the
    /// gap is not obvious. A replayed signature is *substituted away* before the byte comparison, and
    /// the verification of a produced signature uses this port's own `standaloneOctets`. So a Swift
    /// standalone-xmldsig encoder that disagreed with C#'s would sign over X, verify over X, and pass
    /// both checks — while producing a signature no other implementation accepts. The mirrored bug,
    /// in the one place the corpus does not otherwise reach.
    ///
    /// Verifying the **recorded** frame breaks the symmetry: those bytes were signed by C# over C#'s
    /// octets, so they verify here only if the two encoders agree. That is the whole point of the
    /// separate `ExiXmlDsig` target, checked rather than assumed.
    func testTheRecordedSignatureVerifiesUnderThisPortsOwnEncoder() throws {

        for name in ["iso2-ac-pnc", "iso20-dc-pnc"] {

            let trace = try SessionTrace.load(name)
            let key   = try SignedFrame.publicKey(x: XCTUnwrap(trace.signingKey).x,
                                                  y: XCTUnwrap(trace.signingKey).y)
            let signed = trace.exchanges.filter { $0.request.isSigned }

            XCTAssertFalse(signed.isEmpty, "\(name) records no signed request")

            for exchange in signed {
                XCTAssertTrue(SignedFrame.verifies(exchange.request.bytes, with: key),
                    "\(name) exchange \(exchange.index) (\(exchange.request.message)): the signature " +
                    "C# recorded does not verify here. The two standalone-xmldsig encoders disagree, " +
                    "which no other check in this suite can see.")
            }
        }
    }

    /// A session that ends early is the other way a replay passes without meaning anything.
    func testAnEarlyEndingSessionIsNotComplete() throws {
        let trace  = try SessionTrace.load("iso2-ac-eim")
        let replay = TraceReplay(trace)
        try replay.write(trace.exchanges[0].request.bytes)
        XCTAssertEqual(replay.replayed, 1)
        XCTAssertFalse(replay.complete)
    }
}
