import XCTest
@testable import V2GEvcc

/// The handshake reads *which* protocol was accepted, not only that one was — and since the
/// multi-protocol offer, that answer decides which state machine runs next.
///
/// The single-offer refusal mirrors
/// `EvccReadsTheOfferTests.Sap_AnAcceptedSchemaWeNeverOfferedIsRefused` (found in the C# sweep of
/// 2026-08-03). The `*-sapboth` traces hold the multi-offer end to end: the request's two entries,
/// the station's answered SchemaID, and the session that has to follow it.
final class SapHandshakeTests: XCTestCase {

    private let recordedAt: () -> UInt64 = { 1_767_225_600 }

    private func both(_ mode: PowerMode) -> [SapOffer] {
        [SapOffer(.iso15118_20, mode), SapOffer(.iso15118_2, mode)]
    }


    func testAnAcceptedSchemaWeNeverOfferedIsRefused() throws {

        let station = ScriptedStation([ScriptedStation.sapOk(schemaID: 7)])

        XCTAssertThrowsError(try SapHandshake.runEvccSide(station.stream, .iso15118_2, .ac)) { error in
            XCTAssertTrue(String(describing: error).contains("SchemaID 7"), String(describing: error))
        }
    }


    /// The answered SchemaID selects among the offers — 2 names the priority-2 entry, protocol and
    /// mode alike.
    func testTheAnsweredSchemaIdSelectsAmongTheOffers() throws {

        let station = ScriptedStation([ScriptedStation.sapOk(schemaID: 2)])

        let accepted = try SapHandshake.runEvccSide(station.stream, both(.ac))

        XCTAssertEqual(accepted, SapOffer(.iso15118_2, .ac))
    }


    func testAnAcceptedSchemaOutsideAMultiOfferIsRefused() throws {

        let station = ScriptedStation([ScriptedStation.sapOk(schemaID: 7)])

        XCTAssertThrowsError(try SapHandshake.runEvccSide(station.stream, both(.dc))) { error in
            let text = String(describing: error)
            XCTAssertTrue(text.contains("SchemaID 7"), text)
            XCTAssertTrue(text.contains("1, 2"), text)   // …and names what was on offer
        }
    }


    // ── the negotiated sessions, held to the corpus ───────────────────────
    //
    // The dispatch below — run whichever machine the accepted offer names — is the capability these
    // traces exist for: the state machine is chosen AFTER the handshake, and a port that chose it
    // before would replay one of the two traces wrongly.

    private func runNegotiated(_ name: String, _ mode: PowerMode) throws -> (TraceReplay, SapOffer) {

        let replay = TraceReplay(try SessionTrace.load(name))
        let stream = V2GTPStream(replay)

        let accepted = try SapHandshake.runEvccSide(stream, both(mode))

        switch accepted.variant {
        case .iso15118_2:
            try Evcc2(stream, accepted.mode).run()
        case .iso15118_20:
            let evcc: Evcc20Base = accepted.mode == .dc
                ? Evcc20Dc(stream, clock: recordedAt)
                : Evcc20Ac(stream, clock: recordedAt)
            try evcc.run()
        }

        return (replay, accepted)
    }


    /// The station speaks only -2 and answers SchemaID 2 — the priority-2 entry — so the -2 session
    /// must run. Also the one recording in which the answered SchemaID is not 1, which is what shows
    /// a station echoing the accepted entry rather than a literal.
    func testOfferingBothAtAnIso2OnlyStationRunsTheIso2Session() throws {

        let (replay, accepted) = try runNegotiated("iso2-ac-eim-sapboth", .ac)

        XCTAssertEqual(accepted.variant, .iso15118_2,
            "the station accepted the priority-2 entry; only its answered SchemaID says so")
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) recorded exchanges")
    }


    func testOfferingBothAtAnIso20StationRunsTheIso20Session() throws {

        let (replay, accepted) = try runNegotiated("iso20-dc-eim-sapboth", .dc)

        XCTAssertEqual(accepted.variant, .iso15118_20)
        XCTAssertTrue(replay.complete,
            "the session stopped after \(replay.replayed) recorded exchanges")
    }
}
