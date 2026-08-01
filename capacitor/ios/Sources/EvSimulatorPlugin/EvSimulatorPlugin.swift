import Foundation
import Capacitor

import ExiRuntime
import V2GBridge

/// The iOS half of the Capacitor adapter (`docs/CONCEPT.md` B1).
///
/// **This file is transport and nothing else, on purpose.** It is one of the two files in the
/// project that no `swift test`, `gradle test`, `dotnet test` or `npm test` on a laptop can exercise
/// as it actually runs, so anything decided here is decided where nothing checks it. What a session
/// is, what an event is and what a configuration may say all live in ``V2GBridge``, where four back
/// ends agree on them character for character. The two translations that are genuinely this module's
/// own are in ``BridgeCodec``, which has no Capacitor types in its signatures and can therefore be
/// read — and reasoned about — without one.
///
/// The name `EvSimulator` is repeated in three places: ``jsName`` here, `@CapacitorPlugin(name =
/// "EvSimulator")` on Android, and `registerPlugin("EvSimulator")` in TypeScript. Nothing checks
/// that the three agree.
@objc(EvSimulatorPlugin)
public class EvSimulatorPlugin: CAPPlugin, CAPBridgedPlugin {

    public let identifier = "EvSimulatorPlugin"
    public let jsName     = "EvSimulator"

    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop",  returnType: CAPPluginReturnPromise),
    ]

    /// The event name the WebView subscribes to.
    public static let eventName = "v2gEvent"

    /// What actually runs a session.
    ///
    /// Installed by the host application rather than constructed here, because where the frames come
    /// from is not the bridge's business: a socket to a station, a recorded trace, a fixture. See
    /// ``SessionRunner`` and ``TraceSessionRunner``.
    public static var runner: SessionRunner?

    private let lock     = NSLock()
    private var sessions: [String: DispatchWorkItem] = [:]
    private var nextId   = 1


    /// Starts a session and begins the stream.
    ///
    /// Rejects — rather than starting a stream that immediately dies — when the configuration is
    /// refused or no runner is installed. The distinction is what the caller acts on: a rejection
    /// means no session exists, an error event means one exists and something in it went wrong.
    @objc public func start(_ call: CAPPluginCall) {

        guard let runner = EvSimulatorPlugin.runner else {
            call.reject("no session runner is installed on this build.")
            return
        }

        let config: SessionConfig
        do {
            config = try BridgeCodec.configFrom(call.getObject("config"))
        } catch let refusal as SessionConfigError {
            call.reject(refusal.description)
            return
        } catch {
            call.reject("\(error)")
            return
        }

        lock.lock()
        let id = "s-\(nextId)"
        nextId += 1
        lock.unlock()

        // A work item on a background queue: SessionRunner blocks by design, and the lifetime being
        // tracked is exactly one blocking call. `stop` cancels it; a runner that ignores
        // cancellation simply runs to its end, which for a replayed trace is immediate.
        let work = DispatchWorkItem { [weak self] in

            guard let self else { return }

            var lastSeq = -1
            var lastAt  = 0

            do {
                try runner.run(config) { event in
                    lastSeq = event.seq
                    lastAt  = event.atMillis
                    self.notifyListeners(EvSimulatorPlugin.eventName, data: BridgeCodec.payload(event))
                }
            } catch {
                // The stream has already started, so this cannot be a rejection any more. It becomes
                // the last event instead — silence would leave the WebView waiting forever.
                self.notifyListeners(EvSimulatorPlugin.eventName, data: BridgeCodec.payload(
                    .error(seq: lastSeq + 1, atMillis: lastAt,
                           detail: "the session ended abnormally: \(error)", exi: nil)))
            }

            self.lock.lock()
            self.sessions.removeValue(forKey: id)
            self.lock.unlock()
        }

        lock.lock()
        sessions[id] = work
        lock.unlock()

        DispatchQueue.global(qos: .userInitiated).async(execute: work)

        call.resolve(["sessionId": id])
    }


    /// Stops a running session. Idempotent: stopping a finished or unknown session is not an error.
    @objc public func stop(_ call: CAPPluginCall) {

        guard let id = call.getString("sessionId") else {
            call.reject("'sessionId' is missing.")
            return
        }

        lock.lock()
        let work = sessions.removeValue(forKey: id)
        lock.unlock()

        work?.cancel()

        call.resolve()
    }

}


/// The two translations that belong to this module rather than to ``V2GBridge``.
enum BridgeCodec {

    /// An event, as the payload `notifyListeners` carries.
    ///
    /// **The event travels as text, not as a dictionary, and that is the one real decision in this
    /// module.** Building the `[String: Any]` the bridge takes means going through
    /// `JSONSerialization`, and a round trip through it loses member order at every level — measured
    /// on all 196 events of `Vectors/Bridge.events.json`: 196 changed. Android's `org.json` does the
    /// same, in a different order.
    ///
    /// Neither library is wrong; member order is semantically insignificant in JSON. But the JSON-LD
    /// documents in this repository are pinned as **text** across four back ends, so a document that
    /// arrives in a third order on iOS and a fourth on Android is no longer the document the corpus
    /// agreed on: an inspector would render one message three ways and anything hashing it would get
    /// three hashes.
    ///
    /// So the string the back ends already agree on crosses unaltered, and `JSON.parse` — whose key
    /// order the language specification fixes — rebuilds it on the far side.
    /// `SessionConfigTests.testAnEventDoesNotSurviveJSONSerialization` keeps that measurement honest
    /// rather than leaving it as a claim in a comment.
    static func payload(_ event: BridgeEvent) -> [String: Any] {
        ["event": BridgeEvent.toJSON(event).jsonString]
    }


    /// The configuration out of a plugin call.
    ///
    /// Read from a key of its own rather than from the call's options, because Capacitor puts its
    /// own `callbackId` in there and ``SessionConfig`` refuses unknown properties — deliberately,
    /// since a silently ignored setting is one the user believes they approved.
    ///
    /// **This direction is a dictionary where the other is text, and the asymmetry is deliberate.**
    /// An event *contains* a JSON-LD document whose member order is pinned; a configuration is eight
    /// flat properties read by name, where order means nothing and there is nothing nested to lose.
    static func configFrom(_ config: [String: Any]?) throws -> SessionConfig {

        guard let config else {
            throw SessionConfigError("a session configuration is a JSON object.")
        }

        let data = try JSONSerialization.data(withJSONObject: config)

        return try SessionConfig.parse(JsonValue.parse(String(decoding: data, as: UTF8.self)))
    }
}
