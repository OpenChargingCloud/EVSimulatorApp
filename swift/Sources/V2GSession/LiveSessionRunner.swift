import Foundation

import V2GBridge
import V2GEvcc

/// A ``SessionRunner`` that talks to a real station.
///
/// ## What is new here, and what is not
///
/// Nothing about the *protocol* is new. `SapHandshake`, `Evcc2` and `Evcc20Ac`/`Evcc20Dc` are the
/// same state machines the trace tests hold to the recorded sessions byte for byte, and this struct
/// does not touch them. What it adds is where the bytes come from and where the events go:
///
///  - the frames arrive on a socket instead of a byte array, and
///  - every frame that crosses becomes a ``BridgeEvent``, through `V2GTPStream`'s frame tap.
///
/// **So the interesting property is that this changes nothing.** `LiveSessionRunnerTests` drives it
/// over the *recorded* frames, delivered by a real loopback listener, and requires exactly the events
/// `Vectors/Bridge.events.json` pins — the same events a replay produces, from the same frames,
/// because the difference really is only where they came from. The listener answers in three-byte
/// pieces, which is the one thing a byte-array replay can never test: measured on the Kotlin twin, a
/// `readExactly` that assumed one read per frame passes every EVCC trace test and fails four of these.
///
/// ## The transport is injected
///
/// ``connect`` rather than a socket, so everything above the socket is testable and the socket itself
/// is one small file (``NetworkV2GTransport``) with no session logic in it. It is also where TLS
/// lives, and TLS is a trust decision rather than a transport detail — see that file.
///
/// ## Blocking
///
/// ``run(_:emit:)`` returns when the session is over, on the caller's thread. That is
/// ``SessionRunner``'s contract and it is what makes the Capacitor adapter's job a work item and
/// nothing more.
public struct LiveSessionRunner: SessionRunner {

    private let connect:          (SessionConfig) throws -> V2GConnection
    private let pnc:              PncEvccOptions?
    private let monotonicMillis:  () -> Int
    private let wallClockSeconds: () -> UInt64
    private let pollDelay:        (UInt64) -> Void
    private let sessionName:      (SessionConfig) -> String

    /// - Parameters:
    ///   - connect: opens the connection an approved configuration describes.
    ///   - pnc: contract credentials, for `authorization = "pnc"`. Supplied rather than loaded here,
    ///     because a private key's home is the platform keystore (§3.4) and this type has no business
    ///     knowing which one. A `pnc` configuration with none is refused before the socket opens.
    ///   - wallClockSeconds: seconds since the epoch, for the header ISO 15118-20 stamps on every
    ///     message. Separate from `monotonicMillis` because they answer different questions: this one
    ///     has to match what the station believes the time is, and that one must never step backwards.
    ///   - sessionName: the label the stream opens with. A packaging decision, like where a trace lives.
    public init(connect: @escaping (SessionConfig) throws -> V2GConnection,
                pnc: PncEvccOptions? = nil,
                monotonicMillis: @escaping () -> Int = {
                    Int(DispatchTime.now().uptimeNanoseconds / 1_000_000)
                },
                wallClockSeconds: @escaping () -> UInt64 = { UInt64(Date().timeIntervalSince1970) },
                pollDelay: @escaping (UInt64) -> Void = { Thread.sleep(forTimeInterval: Double($0) / 1000) },
                sessionName: @escaping (SessionConfig) -> String = {
                    "\($0.protocol)-\($0.mode)-\($0.authorization)"
                }) {
        self.connect          = connect
        self.pnc              = pnc
        self.monotonicMillis  = monotonicMillis
        self.wallClockSeconds = wallClockSeconds
        self.pollDelay        = pollDelay
        self.sessionName      = sessionName
    }


    public func run(_ config: SessionConfig, emit: (BridgeEvent) -> Void) throws {

        // Before the socket, not after: a session that cannot be authorized the way it was approved
        // should not reach a station at all.
        if config.authorization == "pnc" && pnc == nil {
            throw SessionRunnerError(
                "this configuration asks for Plug & Charge, and no contract credentials are installed.")
        }

        let variant: ProtocolVariant
        switch config.protocol {
        case "iso15118-2":  variant = .iso15118_2
        case "iso15118-20": variant = .iso15118_20
        default: throw SessionRunnerError("'\(config.protocol)' is not a protocol this build runs.")
        }

        let mode: PowerMode = config.mode == "dc" ? .dc : .ac

        let events = LiveEventStream(monotonicMillis: monotonicMillis)
        emit(events.started(name: sessionName(config), protocolName: config.protocol, mode: config.mode))

        // The SupportedAppProtocol handshake shares payload id 0x8001 with every ISO 15118-2 message,
        // and the dispatcher's rule is that SAP is what comes FIRST. This is the one place that knows
        // it without guessing — deciding from the bytes would be a guess, because both grammars will
        // decode a 0x8001 payload and the wrong one yields a message that looks fine.
        //
        // A box rather than a plain `var`: the tap is an escaping closure, and what it has to read is
        // the value at the moment a frame crosses, not the value when it was created.
        let handshaking = Flag(true)

        do {
            let connection = try connect(config)
            defer { connection.close() }

            // `emit` is not escaping and must not become so — a consumer's listener outliving the
            // session would be a stream that keeps arriving after `run` returned. The frame tap does
            // need an escaping closure, and it is destroyed with the stream inside this scope, which
            // is precisely what `withoutActuallyEscaping` is for.
            try withoutActuallyEscaping(emit) { emit in

                let stream = V2GTPStream(connection.stream) { frame, payloadType, direction in
                    emit(events.frame(frame,
                                      payloadType: String(format: "0x%04x", payloadType),
                                      direction: direction,
                                      isSap: handshaking.value))
                }

                try SapHandshake.runEvccSide(stream, variant, mode)
                handshaking.value = false

                switch variant {

                case .iso15118_2:
                    let evcc = Evcc2(stream, mode, pollDelay: pollDelay)
                    evcc.pnc = pnc
                    try evcc.run()

                case .iso15118_20:
                    let evcc = mode == .dc
                        ? Evcc20Dc(stream, clock: wallClockSeconds, pollDelay: pollDelay)
                        : Evcc20Ac(stream, clock: wallClockSeconds, pollDelay: pollDelay)
                    try evcc.run()
                }
            }

        } catch {
            // The stream has already started, so this cannot become a refusal any more: a consumer
            // that has been receiving events needs to be told which one is the last.
            emit(events.failure("the session ended: \(error)"))
        }

        emit(events.finished())
    }
}


/// An open connection to a station.
///
/// Deliberately not a socket. What `V2GTPStream` needs is a `V2GByteStream`, TLS replaces one socket
/// with something else that offers the same two operations, and a test needs a pair that is not a
/// socket at all — so the seam is drawn where all three agree.
public protocol V2GConnection {
    var stream: V2GByteStream { get }
    func close()
}


/// A mutable value an escaping closure can read.
///
/// Swift closures capture `var`s by reference, so a plain local would work — but only until someone
/// makes the tap `@Sendable` or moves the session onto a queue, at which point the capture becomes
/// an error and the obvious fix is to snapshot the value, which is silently wrong. Naming the box
/// makes the intent survive that.
private final class Flag {
    var value: Bool
    init(_ value: Bool) { self.value = value }
}
