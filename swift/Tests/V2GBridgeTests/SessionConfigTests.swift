import XCTest
import Foundation
import ExiRuntime
@testable import V2GBridge

/// This back end's reading of a session configuration, against C#'s.
///
/// The event stream is a record: a page can only watch it. This is the opposite direction — a value a
/// WebView hands to native code, which then opens a socket because of it. So the interesting half is
/// the refusals, and they are pinned **by message**: a port that says no for a different reason is a
/// different product, and the difference only ever shows up in front of a user who cannot act on it.
final class SessionConfigTests: XCTestCase {

    private lazy var repositoryRoot: URL = {
        var directory = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        while !FileManager.default.fileExists(
                atPath: directory.appendingPathComponent("EVSimulatorApp.slnx").path) {
            let parent = directory.deletingLastPathComponent()
            precondition(parent != directory, "repository root not found")
            directory = parent
        }
        return directory
    }()

    private lazy var corpus: JsonObject = {
        let file = repositoryRoot
            .appendingPathComponent("bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.config.json")
        let text = try! String(contentsOf: file, encoding: .utf8)
        return try! JsonValue.parse(text) as! JsonObject
    }()

    private func section(_ name: String) -> [JsonObject] {
        (corpus[name] as! JsonArray).asList.map { $0 as! JsonObject }
    }

    private func caseName(_ entry: JsonObject) -> String {
        (entry["name"] as! JsonString).value
    }


    func testEveryConfigurationCSharpAcceptsIsAcceptedHereCanonically() throws {

        let cases = section("accepted")
        XCTAssertGreaterThanOrEqual(cases.count, 8, "only \(cases.count) accepted cases")

        for entry in cases {
            let config = try SessionConfig.parse(entry["input"])

            XCTAssertEqual(config.toJSON().jsonString,
                           (entry["canonical"] as! JsonObject).jsonString,
                           caseName(entry))
        }
    }


    func testEveryConfigurationCSharpRefusesIsRefusedHereInTheSameWords() throws {

        let cases = section("refused")
        XCTAssertGreaterThanOrEqual(cases.count, 20, "only \(cases.count) refused cases")

        for entry in cases {

            let expected = (entry["message"] as! JsonString).value

            do {
                _ = try SessionConfig.parse(entry["input"])
                XCTFail("\(caseName(entry)) was accepted; C# refuses it with: \(expected)")
            } catch let error as SessionConfigError {
                XCTAssertEqual(error.description, expected, caseName(entry))
            }
        }
    }


    /// Parse, write, parse again — the same value. What makes the canonical form usable as a record.
    func testEveryAcceptedConfigurationSurvivesItsOwnJson() throws {

        for entry in section("accepted") {
            let once  = try SessionConfig.parse(entry["input"])
            let twice = try SessionConfig.parse(once.toJSON())

            XCTAssertEqual(once, twice, caseName(entry))
        }
    }


    /// A runner that cannot start refuses the command; it does not open a stream that immediately dies.
    ///
    /// The distinction is what a caller acts on: a throw means no session exists and the WebView's
    /// `start()` rejects, while an error event means a session is running and something in it failed.
    func testATraceRunnerWithNoRecordingRefusesRatherThanEmittingABrokenStream() throws {

        let runner = TraceSessionRunner(trace: { _ in nil }, monotonicMillis: SteppingClock().read)
        let config = try SessionConfig.parse(section("accepted").first!["input"])

        var emitted: [BridgeEvent] = []

        XCTAssertThrowsError(try runner.run(config) { emitted.append($0) }) { error in
            XCTAssertTrue((error as! SessionRunnerError).description.contains(config.protocol),
                          "the refusal names what was asked for")
        }
        XCTAssertTrue(emitted.isEmpty, "a refused command emitted \(emitted.count) event(s)")
    }


    /// The measurement the Capacitor payload's shape rests on: an event does not survive
    /// `JSONSerialization`.
    ///
    /// Capacitor's `notifyListeners` takes a `[String: Any]`, and `JSONSerialization` is the only way
    /// to build one from a document. It is backed by a `Dictionary`, which has no member order at
    /// all — so every JSON-LD document that went through it would arrive in an order decided by a
    /// hash function, differing from Android's and from the corpus every back end agrees on. That is
    /// why an event crosses the bridge as **text**.
    ///
    /// Kept here rather than in the plugin because the plugin can only be built for iOS, and a
    /// measurement nothing runs is a comment. If this ever stops failing — if some future
    /// `JSONSerialization` preserved order — the text payload would become ceremony rather than
    /// protection, and this test is where that would be noticed.
    func testAnEventDoesNotSurviveJSONSerialization() throws {

        let events = try JsonValue.parse(String(contentsOf: repositoryRoot.appendingPathComponent(
            "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json"), encoding: .utf8))

        let sessions = (events as! JsonObject)["sessions"] as! JsonObject

        var total = 0, changed = 0

        for name in sessions.keys {
            for event in (sessions[name] as! JsonArray).asList {

                let text   = event.jsonString
                let object = try JSONSerialization.jsonObject(with: Data(text.utf8))
                let data   = try JSONSerialization.data(withJSONObject: object,
                                                        options: [.withoutEscapingSlashes])
                total += 1
                if String(decoding: data, as: UTF8.self) != text { changed += 1 }
            }
        }

        XCTAssertGreaterThanOrEqual(total, 190, "only \(total) events were measured")
        XCTAssertEqual(changed, total,
            "JSONSerialization preserved \(total - changed) of \(total) events — re-read BridgeCodec")
    }


    /// And one that has a recording produces exactly the stream the event corpus pins.
    func testATraceRunnerReplaysTheRecordedSession() throws {

        let traceDirectory = repositoryRoot
            .appendingPathComponent("../ISO15118ConformanceTests.Simulation/Vectors")

        let traceFile = try FileManager.default
            .contentsOfDirectory(atPath: traceDirectory.path)
            .filter { $0.hasPrefix("Session.") && $0.hasSuffix(".trace.json") }
            .sorted()
            .first!

        let trace = try JsonValue.parse(
            String(contentsOf: traceDirectory.appendingPathComponent(traceFile), encoding: .utf8))
            as! JsonObject

        let name   = (trace["name"] as! JsonString).value
        let runner = TraceSessionRunner(trace: { _ in trace }, monotonicMillis: SteppingClock().read)
        let config = try SessionConfig.parse(section("accepted").first!["input"])

        var emitted: [BridgeEvent] = []
        try runner.run(config) { emitted.append($0) }

        let events = try JsonValue.parse(String(contentsOf: repositoryRoot.appendingPathComponent(
            "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json"), encoding: .utf8))
        let expected = ((events as! JsonObject)["sessions"] as! JsonObject)[name] as! JsonArray

        XCTAssertEqual(emitted.count, expected.count, "\(name): event count")

        for (i, event) in emitted.enumerated() {
            XCTAssertEqual(BridgeEvent.toJSON(event).jsonString, expected.asList[i].jsonString,
                           "\(name)/\(i)")
        }
    }

}
