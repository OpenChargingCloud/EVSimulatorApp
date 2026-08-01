import XCTest
import Foundation

import ExiRuntime
import V2GBridge
@testable import V2GSession

/// The live runner against the recorded sessions — over a real socket.
///
/// ## The claim being checked
///
/// ``SessionEventStream``'s documentation has said, since before there was a live runner, that *"the
/// live runner emits the same events from the same frames; what differs is where the frames come
/// from."* That was a design intention with nothing holding it. This is what holds it: the same
/// recorded frames, delivered over TCP by ``RecordedStation``, have to produce **exactly** the events
/// `Vectors/Bridge.events.json` pins — property for property, timing included.
///
/// The timings line up because the clock is injected and stepped, and because a live session reads it
/// in the same places a replay does: once before the first event, once per event. That coupling is
/// load-bearing, and a stray extra clock read fails at the second event rather than somewhere
/// unrelated.
///
/// ## And the socket is doing real work
///
/// ``RecordedStation`` answers in three-byte pieces, so every frame arrives across several reads. The
/// in-memory replays the EVCC tests use cannot produce that, and a reader that assumed one `read` per
/// frame passes all of them. The station also compares each request against the recording before
/// answering, so this is still the byte oracle — reached through a different door.
final class LiveSessionRunnerTests: XCTestCase {

    private func corpusEvents(_ session: String) throws -> JsonArray {

        let file = RecordedStation.repositoryRoot.appendingPathComponent(
            "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json")

        let root = try JsonValue.parse(String(contentsOf: file, encoding: .utf8)) as! JsonObject

        return (root["sessions"] as! JsonObject)[session] as! JsonArray
    }


    private func runOverASocket(_ session: String, _ protocolName: String,
                                _ mode: String) throws -> ([BridgeEvent], RecordedStation) {

        let station = try RecordedStation(trace: try RecordedStation.load(session)).start()
        defer { station.stop() }

        let config = SessionConfig(host: "127.0.0.1", port: Int(station.port), transport: "tcp",
                                   protocol: protocolName, mode: mode, authorization: "eim")

        let clock = SteppingClock()

        let runner = LiveSessionRunner(
            connect:          { try NetworkV2GTransport.connect($0, readTimeout: 5) },
            monotonicMillis:  clock.read,
            // -20 stamps a timestamp into every message header, so without the recording's own
            // instant not one frame would match. Same value the EVCC trace tests use.
            wallClockSeconds: { 1_767_225_600 },
            pollDelay:        { _ in },
            sessionName:      { _ in session })

        var events: [BridgeEvent] = []
        try runner.run(config) { events.append($0) }

        return (events, station)
    }


    private func check(_ session: String, _ protocolName: String, _ mode: String) throws {

        let (events, station) = try runOverASocket(session, protocolName, mode)

        XCTAssertNil(station.complaint, "the station refused what the EV sent")
        XCTAssertEqual(station.served, station.exchanges,
            "\(session): the EV walked through \(station.served) of \(station.exchanges) recorded "
          + "exchanges — it ended early, which sends no wrong bytes and would otherwise pass")

        let expected = try corpusEvents(session)

        XCTAssertEqual(events.count, expected.count, "\(session): event count")

        for (i, event) in events.enumerated() where i < expected.count {
            XCTAssertEqual(BridgeEvent.toJSON(event).jsonString, expected.asList[i].jsonString,
                           "\(session)/\(i)")
        }
    }


    func testTheIso2AcSessionProducesTheCorpusEvents()  throws { try check("iso2-ac-eim",  "iso15118-2",  "ac") }
    func testTheIso2DcSessionProducesTheCorpusEvents()  throws { try check("iso2-dc-eim",  "iso15118-2",  "dc") }
    func testTheIso20AcSessionProducesTheCorpusEvents() throws { try check("iso20-ac-eim", "iso15118-20", "ac") }
    func testTheIso20DcSessionProducesTheCorpusEvents() throws { try check("iso20-dc-eim", "iso15118-20", "dc") }


    /// A station that is not there is a failed session with events, not a thrown command.
    ///
    /// The opposite of `TraceSessionRunner`, and deliberately: that one refuses before anything
    /// starts, because it can tell. This one has already told the consumer a session began — the
    /// connection is the first thing that happens *after* that — so the failure has to arrive as the
    /// last event rather than as silence.
    func testAStationThatRefusesTheConnectionEndsTheStreamInsteadOfHangingIt() throws {

        let runner = LiveSessionRunner(
            connect:         { _ in throw StationError("connection refused") },
            monotonicMillis: SteppingClock().read)

        var events: [BridgeEvent] = []

        try runner.run(SessionConfig(host: "127.0.0.1", port: 1, transport: "tcp",
                                     protocol: "iso15118-2", mode: "ac", authorization: "eim")) {
            events.append($0)
        }

        XCTAssertEqual(events.count, 3, "started, the failure, finished")

        guard case .error(_, _, let detail, _) = events[1] else {
            return XCTFail("the second event is \(events[1].kind), not an error")
        }
        XCTAssertTrue(detail.contains("connection refused"), detail)

        guard case .sessionFinished(_, _, let exchanges, let outcome) = events[2] else {
            return XCTFail("the last event is \(events[2].kind), not sessionFinished")
        }
        XCTAssertEqual(outcome, "failed")
        XCTAssertEqual(exchanges, 0)
    }


    /// Plug & Charge without credentials is refused before the socket opens.
    ///
    /// A thrown command rather than an event stream, because nothing has started yet — and four
    /// exchanges into a session is not where to discover that the EV cannot sign anything.
    func testAPncSessionWithNoContractCredentialsNeverReachesTheStation() {

        let connected = Attempt()

        let runner = LiveSessionRunner(connect: { _ in
            connected.happened = true
            throw StationError("should not get here")
        })

        let config = SessionConfig(host: "127.0.0.1", port: 15118, transport: "tls",
                                   protocol: "iso15118-2", mode: "ac", authorization: "pnc")

        XCTAssertThrowsError(try runner.run(config) { _ in }) { error in
            XCTAssertTrue("\(error)".contains("Plug & Charge"), "\(error)")
        }
        XCTAssertFalse(connected.happened,
                       "the transport was opened for a session that cannot be authorized")
    }


    /// The frame tap fires once per frame, in the order the frames crossed.
    ///
    /// Compared against the recording's own frame list rather than against a uniqueness assumption: a
    /// charging session polls, so `ChargingStatusReq` really does go out three times with identical
    /// bytes. The Kotlin twin of this test was written the other way first and failed on exactly that
    /// — 24 events, 19 distinct — which is the recording being right and the test being wrong.
    func testEveryFrameIsTappedExactlyOnceAndInOrder() throws {

        let (events, _) = try runOverASocket("iso2-ac-eim", "iso15118-2", "ac")

        let trace     = try RecordedStation.load("iso2-ac-eim")
        var recorded: [(String, String)] = []

        for exchange in (trace["exchanges"] as! JsonArray).asList {
            for (side, direction) in [("request", "out"), ("response", "in")] {
                if let frame = ((exchange as! JsonObject)[side] as? JsonObject)?["frame"] as? JsonString {
                    recorded.append((direction, frame.value.lowercased()))
                }
            }
        }

        let tapped: [(String, String)] = events.compactMap {
            if case .message(_, _, let direction, _, _, let exi, _) = $0 { return (direction, exi) }
            return nil
        }

        XCTAssertEqual(tapped.map(\.0), recorded.map(\.0), "directions")
        XCTAssertEqual(tapped.map(\.1), recorded.map(\.1),
                       "the tapped frames are not the recorded frames, once each, in order")
    }
}


/// Whether the transport was reached. A class because the closure that sets it escapes.
private final class Attempt: @unchecked Sendable {
    var happened = false
}
