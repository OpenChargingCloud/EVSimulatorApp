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

    private func replay20(_ name: String, _ mode: PowerMode) throws -> (TraceReplay, Evcc20Base) {
        let replay = TraceReplay(try SessionTrace.load(name))
        let stream = V2GTPStream(replay)
        try SapHandshake.runEvccSide(stream, .iso15118_20, mode)
        let evcc: Evcc20Base = mode == .dc
            ? Evcc20Dc(stream, clock: recordedAt)
            : Evcc20Ac(stream, clock: recordedAt)
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

    /// A signed trace is refused, not mis-compared.
    ///
    /// The corpus now contains Plug & Charge sessions whose requests carry an ECDSA signature. Those
    /// are not byte-comparable — the nonce is random — and the C# harness handles them by
    /// substituting the recorded signature and verifying the produced one separately. This harness
    /// does neither yet, and the failure mode worth guarding is the quiet one: comparing such a frame
    /// as-is fails on 64 bytes of signature and reads like a state-machine bug.
    ///
    /// It also marks the boundary. The day somebody ports Plug & Charge to Swift without extending
    /// the harness, this is what tells them.
    func testASignedTraceIsRefusedRatherThanMiscompared() throws {

        let trace  = try SessionTrace.load("iso20-dc-pnc")
        let replay = TraceReplay(trace)

        let signedAt = try XCTUnwrap(trace.exchanges.firstIndex { $0.request.isSigned },
                                     "the PnC trace records no signed request — then this guards nothing")

        // Replay the unsigned prefix by hand, then hit the signed one.
        for i in 0 ..< signedAt { try replay.write(trace.exchanges[i].request.bytes) }

        XCTAssertThrowsError(try replay.write(trace.exchanges[signedAt].request.bytes)) { error in
            XCTAssertTrue(String(describing: error).contains("signed"), String(describing: error))
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
