import XCTest
import ExiRuntime
@testable import V2GBridge

/// This back end's event stream, against the one C# produces.
///
/// The events are what a WebView receives, so their shape is a wire format the moment anything reads
/// it — and the four back ends have to agree on it for the same reason they agree on the JSON-LD
/// documents. `Bridge.events.json` is that agreement, compared as text.
///
/// The second test is the property nothing else checks: **within one event, the JSON-LD and the raw
/// EXI are the same message**. Each half is verified on its own elsewhere, and an event whose JSON-LD
/// came from a different frame than its `exi` would pass everything — while being exactly what an
/// inspector cannot see, since it shows you the JSON and tells you the bytes are right there.
final class BridgeEventStreamTests: XCTestCase {

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
        let file = repositoryRoot.appendingPathComponent(
            "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json")
        let text = try! String(contentsOf: file, encoding: .utf8)
        return (try! JsonValue.parse(text) as! JsonObject)["sessions"] as! JsonObject
    }()

    private func traces() throws -> [JsonObject] {

        let directory = repositoryRoot.appendingPathComponent(
            "vectors")

        return try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .filter { $0.hasPrefix("Session.") && $0.hasSuffix(".trace.json") }
            .sorted()
            .map { try JsonValue.parse(
                       String(contentsOf: directory.appendingPathComponent($0), encoding: .utf8)) as! JsonObject }
    }


    func testThisBackEndEmitsTheEventsCSharpEmits() throws {

        var checked = 0

        for trace in try traces() {

            let name = (trace["name"] as! JsonString).value
            let expected = corpus[name] as! JsonArray

            let produced = try SessionEventStream(monotonicMillis: SteppingClock().read).replay(trace)

            XCTAssertEqual(produced.count, expected.count, "\(name): event count")

            for (i, event) in produced.enumerated() {
                XCTAssertEqual(BridgeEvent.toJSON(event).jsonString, expected.asList[i].jsonString,
                               "\(name)/\(i)")
                checked += 1
            }
        }

        XCTAssertGreaterThanOrEqual(checked, 150, "only \(checked) events were compared")
    }


    /// Within one event, the JSON-LD and the raw EXI are the same message.
    ///
    /// Read from the **checked-in** corpus rather than from a freshly produced stream, so it is a
    /// statement about what a consumer receives rather than about what this code happens to do in
    /// one run.
    func testTheTwoHalvesOfAnEventAreTheSameMessage() throws {

        var checked = 0

        for name in corpus.keys {
            for node in (corpus[name] as! JsonArray).asList {

                let event = node as! JsonObject
                guard (event["kind"] as! JsonString).value == "message" else { continue }

                func string(_ key: String) -> String { (event[key] as! JsonString).value }
                let hex = Array(string("exi"))

                let bytes = stride(from: 0, to: hex.count - 1, by: 2).compactMap {
                    UInt8(String(hex[$0 ... $0 + 1]), radix: 16)
                }

                let fromTheFrame = try MessageSetCodecs.toJSON(
                    frame: bytes, payloadType: string("payloadType"), messageName: string("messageName"))

                XCTAssertEqual(fromTheFrame.jsonString, event["json"]!.jsonString,
                               "\(name)/\(event["seq"]!.jsonString): the event's JSON-LD is not what "
                             + "its own frame decodes to")
                checked += 1
            }
        }

        XCTAssertGreaterThanOrEqual(checked, 100, "only \(checked) messages were self-checked")
    }


    /// The stream is a record, never a channel: no event asks the far side to do anything.
    func testEveryEventKindIsAnObservationRatherThanACommand() {

        var kinds = Set<String>()
        for name in corpus.keys {
            for node in (corpus[name] as! JsonArray).asList {
                kinds.insert(((node as! JsonObject)["kind"] as! JsonString).value)
            }
        }

        XCTAssertEqual(kinds, ["message", "sessionFinished", "sessionStarted"],
                       "a new event kind appeared")
    }


    /// A frame that cannot be read becomes an error event carrying the frame.
    func testAnUnreadableFrameBecomesAnErrorThatCarriesIt() throws {

        let trace = try JsonValue.parse("""
            {
              "name": "broken", "protocol": "iso15118-2", "mode": "ac",
              "exchanges": [
                { "index": 0,
                  "request":  { "payloadType": "0x8001", "message": "SessionSetupReq", "frame": "01fe800100000002ffff" },
                  "response": null }
              ]
            }
            """) as! JsonObject

        let events = try SessionEventStream(monotonicMillis: SteppingClock().read).replay(trace)

        guard case let .error(_, _, detail, exi) = events[1] else {
            return XCTFail("the unreadable frame did not become an error event")
        }
        XCTAssertEqual(exi, "01fe800100000002ffff", "the frame has to travel with the error")
        XCTAssertTrue(detail.contains("SessionSetupReq"), detail)

        guard case let .sessionFinished(_, _, _, outcome) = events[2] else {
            return XCTFail("the session did not finish")
        }
        XCTAssertEqual(outcome, "failed")
    }
}
