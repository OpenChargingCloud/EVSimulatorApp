import CryptoKit
import Foundation
import XCTest
import ExiIso2
import ExiIso20DC
import V2GDispatch
import V2GMetering
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

    private func replay2(_ name: String, _ mode: PowerMode,
                         configure: (Evcc2) -> Void = { _ in }) throws -> (TraceReplay, Evcc2) {
        let replay = TraceReplay(try SessionTrace.load(name))
        let stream = V2GTPStream(replay)
        try SapHandshake.runEvccSide(stream, .iso15118_2, mode)
        let evcc = Evcc2(stream, mode)
        configure(evcc)
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

    /// Recorded 2026-08-16; this target replayed neither new recording until 2026-08-17, so both of
    /// the divergences below had gone unmeasured here while Kotlin was already held to them.
    ///
    /// Every other recording charges for a fixed count of cycles; this one charges until the battery
    /// reaches its target state of charge, which is what the C# car has done since 2026-08-08. The
    /// pack settings have to match the recording exactly, because they are what the bytes are:
    /// 60 kWh from 20 % to a 22 % target is two cycles at 800 Wh each, and every `EVRESSSOC` on the
    /// way is this arithmetic rounded to a percent.
    func testDcIso2SessionWithABatteryMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay2("iso2-dc-eim-battery", .dc) { evcc in
            let pack = EvBattery(capacityKWh: 60.0, startSoCPercent: 20.0)
            pack.targetSoC     = 22.0
            pack.maxIterations = 100
            evcc.battery = pack
        }
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) recorded exchanges — the recorded car " +
            "charges to a target state of charge, this one to a cycle count")
    }

    /// Recorded 2026-08-16. Renegotiation ([V2G2-841]) was ported, but on DC it returns through
    /// CableCheck and PreCharge rather than straight back to the charge loop, and nothing had ever
    /// held this target to that return path.
    func testDcIso2RenegotiationMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay2("iso2-dc-eim-renegotiate", .dc) { $0.renegotiate = true }
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) recorded exchanges — the DC return path " +
            "through CableCheck and PreCharge is where to look")
    }

    /// The battery's own arithmetic, read back from the pack rather than from the wire.
    ///
    /// The bytes above already pin it, but only implicitly: a reader looking at the trace sees
    /// `EVRESSSOC` go 20, 21, 23 and has to reconstruct why. This states the why, and it is the
    /// assertion that would survive if the recording were ever re-taken.
    func testTheBatteryStopsBecauseItReachedItsTarget() {
        let pack = EvBattery(capacityKWh: 60.0, startSoCPercent: 20.0)
        pack.targetSoC     = 22.0
        pack.maxIterations = 100

        XCTAssertEqual(pack.energyNeededWh, 1200.0, "22 % of 60 kWh less the 20 % it starts with")
        pack.add(800.0)                                 // one minute at 48 kW, the DC loop's rate
        XCTAssertEqual(pack.stop, .running, "21.3 % has not reached 22 % yet")
        pack.add(800.0)
        XCTAssertEqual(pack.stop, .targetSoC, "22.7 % has")
        XCTAssertEqual(pack.iterations, 2)
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

    // ── the signed tariff offer ───────────────────────────────────────────

    /// The Mobility Operator's public key, read out of `Tariff.signature.vectors.json`.
    ///
    /// The recorded session and that corpus are signed by the *same* key on purpose, so this is one
    /// operator identity rather than two. It is not carried in the trace itself: the trace schema has
    /// places for the PnC and meter keys because those sessions need them to be readable at all, and
    /// adding a third would give one key two homes and a way to disagree with itself.
    private static func tariffVerifyKey() throws -> P256.Signing.PublicKey {

        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("EVSimulatorApp.slnx").path) { break }
        }

        let url = dir.appendingPathComponent("vectors/Tariff.signature.vectors.json")
        guard let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
              let cases = json["cases"] as? [[String: Any]],
              let signed = cases.first(where: { $0["name"] as? String == "signed-msgdef" }),
              let key = signed["verifyKey"] as? [String: Any],
              let x = key["x"] as? String, let y = key["y"] as? String
        else { throw XCTSkip("tariff corpus not found at \(url.path)") }

        let hex = { (s: String) in stride(from: 0, to: s.count, by: 2).map { i -> UInt8 in
            let start = s.index(s.startIndex, offsetBy: i)
            return UInt8(s[start...s.index(start, offsetBy: 1)], radix: 16)!
        } }
        return try P256.Signing.PublicKey(x963Representation: Data([0x04] + hex(x) + hex(y)))
    }

    func testSignedTariffSessionMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay2("iso2-ac-eim-tariff", .ac)
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    /// What the wire cannot show. The bytes above prove this port *read* a signed two-tuple offer the
    /// way the C# EVCC did; they say nothing about the verdict, because the EV never tells the station
    /// what it concluded. Only the fields below do, and only with the operator's key in hand.
    func testTheSignedTariffOfferVerifiesAndTheCheaperTupleWins() throws {

        let key = try Self.tariffVerifyKey()
        let (_, evcc) = try replay2("iso2-ac-eim-tariff", .ac) { $0.tariffVerifyKey = key }
        let tariff = try XCTUnwrap(evcc.tariff)

        XCTAssertEqual(tariff.tuplesOffered, 2, "the station offered a choice, not a formality")
        XCTAssertTrue(tariff.signaturePresent)
        XCTAssertTrue(tariff.digestOk, "each SalesTariff must digest to its own Reference")
        XCTAssertTrue(tariff.signatureOk)
        XCTAssertEqual(tariff.signatureGrammar, "iso2-msgdef",
                       "our own station signs under ISO's grammar; xmldsig-standalone here would mean " +
                       "the recording was made against a Josev-shaped signer")

        // Tuple 2 averages EPriceLevel 1.5 against tuple 1's 2.5, and carries two PMax steps.
        XCTAssertEqual(tariff.chosenTupleId, 2, "a price-aware EV takes the cheaper tuple")
        XCTAssertEqual(tariff.profileEntries, 2)
    }

    /// Without the key the digest half is still answered — and must be, because it is the half that
    /// catches a tariff edited after signing. Reporting it as unknown would throw away the only check
    /// an EV without an operator key can still make.
    func testWithoutTheOperatorKeyTheDigestIsStillChecked() throws {

        let (_, evcc) = try replay2("iso2-ac-eim-tariff", .ac)
        let tariff = try XCTUnwrap(evcc.tariff)

        XCTAssertTrue(tariff.signaturePresent)
        XCTAssertTrue(tariff.digestOk)
        XCTAssertFalse(tariff.signatureOk, "no key was offered, so nothing was established")
        XCTAssertEqual(tariff.signatureGrammar, "none")
    }

    // ── the station's meter ───────────────────────────────────────────────

    func testMeteredAcIso2SessionMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay2("iso2-ac-eim-meter", .ac)
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    func testMeteredDcIso20SessionMatchesTheRecordingByteForByte() throws {
        let (replay, _) = try replay20("iso20-dc-eim-meter", .dc)
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    /// **The vehicle's own counter lands where the station's signed reading does**, in both protocols.
    ///
    /// Two counters, two state machines, and neither looks at the other's total. In -2 AC both sides
    /// fall back to the ChargingProfile the EV committed to and the station accepted — 11,040 W
    /// (3 x 230 V x 16 A, what an ordinary European charge point delivers) for three minutes,
    /// 552 Wh. In -20 DC the vehicle's figure is half its own and half the station's: it measures
    /// the inlet voltage it reported, the station reports the current — 400 V x 120 A, 2400 Wh.
    ///
    /// The expected figure is read back out of the recording rather than restated, so this is the
    /// port's arithmetic held against C#'s rather than against itself.
    ///
    /// **What this stopped checking on 2026-08-07.** While the station offered a round 11 kW the
    /// sample was 183.33 Wh, which rounds to 183 three times and not to 550 — so a port integrating
    /// precisely and rounding once was one watt-hour out, silently, in a number a driver is billed
    /// on, and it showed up right here. The offer is now a physical number that divides exactly, so
    /// this trace no longer distinguishes the two rules. C# checks the rule directly
    /// (`EvMeterTests`, `Secc20SignedMeterTests`); this port has no metering test of its own, and
    /// until it has one, nothing here holds it to per-sample rounding.
    func testTheVehiclesOwnCounterAgreesWithTheStationsSignedReading() throws {

        let (_, evcc2) = try replay2("iso2-ac-eim-meter", .ac)
        XCTAssertEqual(evcc2.meter.samples, 3, "three charge-loop iterations, three samples")
        XCTAssertEqual(evcc2.meter.energyWh, 552)

        let iso2 = try SessionTrace.load("iso2-ac-eim-meter")
        let lastIso2 = try XCTUnwrap(iso2.exchanges.last { $0.response.carriesMeterSignature })
        guard case let .decoded(_, message) = try V2GTPDispatcher.decode(lastIso2.response.bytes),
              let v2g = message as? V2G_Message,
              let status = v2g.body.bodyElement as? ChargingStatusResType
        else { return XCTFail("the last metered -2 exchange is not a ChargingStatusRes") }

        XCTAssertEqual(status.meterInfo?.meterReading, evcc2.meter.energyWh,
            "the vehicle's counter and the station's last signed reading disagree, and they are " +
            "counting the same three iterations of the same charge loop")

        let (_, evcc20) = try replay20("iso20-dc-eim-meter", .dc)
        XCTAssertEqual(evcc20.meter.energyWh, 2_400)

        let iso20 = try SessionTrace.load("iso20-dc-eim-meter")
        let lastIso20 = try XCTUnwrap(iso20.exchanges.last { $0.response.carriesMeterSignature })
        guard case let .decoded(_, loopMessage) = try V2GTPDispatcher.decode(lastIso20.response.bytes),
              let loop = loopMessage as? DC_ChargeLoopRes
        else { return XCTFail("the last metered -20 exchange is not a DC_ChargeLoopRes") }

        XCTAssertEqual(loop.meterInfo?.chargedEnergyReadingWh, evcc20.meter.energyWh)
    }

    /// **The reading C# recorded verifies under this port's own payload layout**, in both protocols.
    ///
    /// The same argument as the recorded-signature test, one signer along and with a sharper edge:
    /// ISO 15118 defines the `SigMeterReading` / `MeterSignature` *field* and says nothing about what
    /// the signature covers, so the payload is this project's own convention. Three ports of one
    /// convention, each tested against itself, agree perfectly and can be wrong together.
    ///
    /// `MeterSignatureTests` already holds this port to a C#-generated vector corpus, which fixes the
    /// layout. What it cannot fix is everything between the wire and that call — above all whether
    /// the **session id** comes from the message header rather than from somewhere convenient.
    ///
    /// And the last assertion is the one nothing on the wire can make: the payload's protocol byte,
    /// 2 against 20, is never transmitted. A port that hard-coded one would keep the -2 corpus green
    /// while verifying every -20 reading over the wrong octets.
    func testTheRecordedMeterReadingsVerifyAndDoNotCrossProtocols() throws {

        // ISO 15118-2: SigMeterReading, on the AC charge-loop response.
        let iso2 = try SessionTrace.load("iso2-ac-eim-meter")
        let key2 = try SignedFrame.publicKey(x: XCTUnwrap(iso2.meterKey).x,
                                             y: XCTUnwrap(iso2.meterKey).y)
        var checked2 = 0
        var previousReading: UInt64 = 0

        for exchange in iso2.exchanges where exchange.response.carriesMeterSignature {

            guard case let .decoded(_, message) = try V2GTPDispatcher.decode(exchange.response.bytes),
                  let v2g = message as? V2G_Message,
                  let status = v2g.body.bodyElement as? ChargingStatusResType,
                  let info = status.meterInfo
            else { return XCTFail("exchange \(exchange.index) is not a -2 ChargingStatusRes") }

            XCTAssertEqual(info.meterID, "VAN*M*4711")
            // The reading climbs through the session — a register counting what the loop delivered,
            // not a constant. A meter that never advanced would still sign correctly, and that is
            // exactly the failure this line exists to notice.
            XCTAssertGreaterThan(try XCTUnwrap(info.meterReading), previousReading)
            previousReading = try XCTUnwrap(info.meterReading)

            XCTAssertTrue(try MeterSignature.verify(XCTUnwrap(info.sigMeterReading), protocol: 2,
                                                    sessionId: v2g.header.sessionID,
                                                    meterId: info.meterID,
                                                    reading: XCTUnwrap(info.meterReading),
                                                    timestamp: info.tMeter, publicKey: key2),
                "exchange \(exchange.index): the reading C# recorded does not verify here — the two " +
                "meter payload layouts disagree, or this port reads the session id from the wrong place")

            // The session binding, checked rather than assumed: without it the field would prove a
            // reading genuine but not that it is *yours*.
            var elsewhere = v2g.header.sessionID
            elsewhere[0] = elsewhere[0] &+ 1
            XCTAssertFalse(try MeterSignature.verify(XCTUnwrap(info.sigMeterReading), protocol: 2,
                                                     sessionId: elsewhere, meterId: info.meterID,
                                                     reading: XCTUnwrap(info.meterReading),
                                                     timestamp: info.tMeter, publicKey: key2),
                "a reading verified under a session id it was not signed for")
            checked2 += 1
        }

        XCTAssertGreaterThanOrEqual(checked2, 3, "the metered -2 corpus records \(checked2) readings")

        // ISO 15118-20: the same layout, the same key, one different byte that never travels.
        let iso20 = try SessionTrace.load("iso20-dc-eim-meter")
        let key20 = try SignedFrame.publicKey(x: XCTUnwrap(iso20.meterKey).x,
                                              y: XCTUnwrap(iso20.meterKey).y)
        var checked20 = 0

        for exchange in iso20.exchanges where exchange.response.carriesMeterSignature {

            guard case let .decoded(_, message) = try V2GTPDispatcher.decode(exchange.response.bytes),
                  let loop = message as? DC_ChargeLoopRes,
                  let info = loop.meterInfo
            else { return XCTFail("exchange \(exchange.index) is not a -20 DC_ChargeLoopRes") }

            let timestamp = info.meterTimestamp.map { Int64($0) }

            XCTAssertTrue(try MeterSignature.verify(XCTUnwrap(info.meterSignature), protocol: 20,
                                                    sessionId: loop.header.sessionID,
                                                    meterId: info.meterID,
                                                    reading: info.chargedEnergyReadingWh,
                                                    timestamp: timestamp, publicKey: key20),
                "exchange \(exchange.index): the -20 reading C# recorded does not verify here")

            XCTAssertFalse(try MeterSignature.verify(XCTUnwrap(info.meterSignature), protocol: 2,
                                                     sessionId: loop.header.sessionID,
                                                     meterId: info.meterID,
                                                     reading: info.chargedEnergyReadingWh,
                                                     timestamp: timestamp, publicKey: key20),
                "a -20 reading verified as a -2 one — the protocol byte is not in the signed payload")
            checked20 += 1
        }

        XCTAssertGreaterThan(checked20, 0, "the metered -20 corpus records no reading")
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
