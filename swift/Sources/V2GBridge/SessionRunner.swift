import Foundation
import ExiRuntime

/// Whatever turns an approved ``SessionConfig`` into a stream of ``BridgeEvent``s.
///
/// **This exists so the Capacitor adapter can be transport and nothing else.** The plugin's job is to
/// carry a command in and events out; what actually happens in between — a socket to a station, a
/// recorded trace, a fixture in a test — is not the bridge's business, and threading it through the
/// plugin would put session logic in the one file that cannot be unit-tested on a laptop.
///
/// Blocking, and deliberately so: the caller picks the queue, because on iOS that decision belongs to
/// whoever owns the lifecycle. `run` returns when the session is over.
///
/// A failure that prevents the session from starting at all **throws**; it is not an error event. The
/// two are different things to a caller: a throw means the command was rejected and no stream exists,
/// while an error event means a stream is running and something in it went wrong.
public protocol SessionRunner {

    /// Runs one session, handing each event to `emit` in order, and returns when it has ended.
    func run(_ config: SessionConfig, emit: (BridgeEvent) -> Void) throws
}


/// A ``SessionRunner`` that replays a recorded session instead of opening a socket.
///
/// The traces under `Vectors/Session.*.trace.json` are whole EV↔station exchanges captured frame by
/// frame, so this produces exactly the stream `Vectors/Bridge.events.json` pins — which makes the
/// whole path, from the WebView's command to the events it renders, demonstrable on a real phone
/// without a station in the room.
public struct TraceSessionRunner: SessionRunner {

    /// Finds the recording for a configuration, or returns nil if there is none.
    ///
    /// Supplied by the host application because where a trace lives is a packaging question: an iOS
    /// bundle resource, an Android asset, a file a developer dropped in.
    private let trace: (SessionConfig) -> JsonObject?

    private let monotonicMillis: () -> Int

    public init(trace: @escaping (SessionConfig) -> JsonObject?,
                monotonicMillis: @escaping () -> Int = {
                    Int(DispatchTime.now().uptimeNanoseconds / 1_000_000)
                }) {
        self.trace           = trace
        self.monotonicMillis = monotonicMillis
    }

    public func run(_ config: SessionConfig, emit: (BridgeEvent) -> Void) throws {

        guard let recording = trace(config) else {
            throw SessionRunnerError("no recorded session for \(config.protocol) \(config.mode).")
        }

        for event in try SessionEventStream(monotonicMillis: monotonicMillis).replay(recording) {
            emit(event)
        }
    }
}


/// A session that could not be started at all.
public struct SessionRunnerError: Error, CustomStringConvertible, Equatable {

    public let description: String

    public init(_ description: String) { self.description = description }
}
