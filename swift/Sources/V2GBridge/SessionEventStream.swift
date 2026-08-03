import ExiRuntime
import ExiAppProtocol
import ExiIso2
import ExiIso20Common
import ExiIso20AC
import ExiIso20DC

/// Which message set a V2GTP frame belongs to, and how to read it as JSON-LD.
///
/// **The payload type is not enough, and that is a fact about ISO 15118 rather than about this
/// code.** `0x8001` carries both the SupportedAppProtocol handshake and every ISO 15118-2 message —
/// the handshake happens before a protocol has been agreed, so it cannot have a payload type of its
/// own. The dispatcher resolves it by position; here the message's own name does, because the events
/// are built from a record of the session rather than from a live socket.
///
/// A frame this cannot place becomes an error event, never a silently skipped one.
public enum MessageSetCodecs {

    /// The V2GTP header: version, payload type, and the payload's length.
    public static let v2gtpHeaderBytes = 8

    public static func toJSON(frame: [UInt8], payloadType: String,
                              messageName: String) throws -> JsonObject {
        try toJSON(frame: frame, payloadType: payloadType,
                   isSap: messageName.hasPrefix("SupportedAppProtocol"))
    }

    /// The same, told directly whether the frame is a SupportedAppProtocol one.
    ///
    /// A live session has no recorded message name to read that off, and it does not need one: the
    /// dispatcher's rule is that SAP is what comes *first*, and the runner driving the handshake is
    /// the one place that knows without guessing. Deciding it from the frame's own bytes would be a
    /// guess — both grammars will decode a 0x8001 payload, and the wrong one produces a message that
    /// looks plausible.
    public static func toJSON(frame: [UInt8], payloadType: String,
                              isSap: Bool) throws -> JsonObject {

        guard frame.count > v2gtpHeaderBytes else {
            throw JsonLdError("a V2GTP frame is longer than its \(v2gtpHeaderBytes)-byte header.")
        }

        let payload = Array(frame[v2gtpHeaderBytes...])

        switch (payloadType, isSap) {
        case ("0x8001", true):
            return try SupportedAppProtocolCodecJson.toJSON(SupportedAppProtocolCodec.decodeAny(payload))
        case ("0x8001", false):
            return try Iso15118_2CodecJson.toJSON(Iso15118_2Codec.decodeAny(payload))
        case ("0x8002", _):
            return try CommonMessagesCodecJson.toJSON(CommonMessagesCodec.decodeAny(payload))
        case ("0x8003", _):
            return try ACCodecJson.toJSON(ACCodec.decodeAny(payload))
        case ("0x8004", _):
            return try DCCodecJson.toJSON(DCCodec.decodeAny(payload))
        default:
            throw JsonLdError("payload type '\(payloadType)' is not a message set this build carries.")
        }
    }


    /// The message's name, read out of the document.
    ///
    /// The recorder derives this from the decoded object's *type*, which a live session cannot do
    /// without reflection in three languages that name types three different ways. It does not have
    /// to: the JSON-LD emitter writes the type name as `@type`, so the same answer is already in the
    /// document, and reading it there is language-neutral.
    ///
    /// The rule is structural rather than a table. ISO 15118-2 wraps everything in a `V2G_Message`,
    /// so the interesting name is the body element's; the -20 sets and the SupportedAppProtocol
    /// handshake decode straight to the message, so the document is the message. C#'s
    /// `TheNameInTheDocumentIsTheNameTheRecorderGave` holds that claim to all 196 recorded events.
    public static func messageName(_ json: JsonObject) -> String {

        guard let body = json["body"] as? JsonObject else {
            return (json["@type"] as? JsonString)?.value ?? "unnamed"
        }

        guard let element = body["bodyElement"] as? JsonObject else {
            return "V2G_Message(empty body)"
        }

        return (element["@type"] as? JsonString)?.value ?? "unnamed"
    }
}


/// The event stream of a session that is happening now.
///
/// ``SessionEventStream`` turns a *recording* into events, all of them at once, because a recording
/// is over. A live session hands over one frame at a time and does not know how many there will be,
/// so the stream is driven from outside: ``started(name:protocol:mode:)`` once, ``frame(_:payloadType:direction:isSap:)``
/// per frame, ``finished()`` at the end.
///
/// **The events are the same events.** That is the point of the class existing rather than the runner
/// assembling them: `LiveSessionRunnerTests` drives this over the recorded frames — through a real
/// socket — and requires exactly what `Vectors/Bridge.events.json` pins, event for event. What
/// differs between a replay and a live session is where the frames come from, and nothing else.
///
/// Not thread-safe, deliberately: one session, one thread, one caller. The sequence numbers are the
/// consumer's guarantee that nothing was lost, and a stream two threads could interleave would not
/// have them.
public final class LiveEventStream {

    private let monotonicMillis: () -> Int

    private var start  = 0
    private var seq    = 0
    private var didFail = false

    /// How many request/response exchanges this session ran, counted as messages sent.
    public private(set) var exchanges = 0

    public init(monotonicMillis: @escaping () -> Int) {
        self.monotonicMillis = monotonicMillis
    }

    /// The session began. Starts the clock, so nothing before this is timed.
    public func started(name: String, protocolName: String, mode: String) -> BridgeEvent {
        start = monotonicMillis()
        defer { seq += 1 }
        return .sessionStarted(seq: seq, atMillis: monotonicMillis() - start,
                               name: name, protocolName: protocolName, mode: mode)
    }

    /// One frame that crossed the wire, as the event a consumer receives.
    ///
    /// - Parameter isSap: whether this is a SupportedAppProtocol frame — see ``MessageSetCodecs``.
    public func frame(_ frame: [UInt8], payloadType: String, direction: String,
                      isSap: Bool) -> BridgeEvent {

        let at  = monotonicMillis() - start
        let hex = frame.map { String(format: "%02x", $0) }.joined()

        if direction == "out" { exchanges += 1 }

        defer { seq += 1 }

        do {
            let json = try MessageSetCodecs.toJSON(frame: frame, payloadType: payloadType, isSap: isSap)
            return .message(seq: seq, atMillis: at, direction: direction, payloadType: payloadType,
                            messageName: MessageSetCodecs.messageName(json), exi: hex, json: json)
        } catch {
            // The frame goes out with the error. A decode failure whose bytes are not in the stream
            // is a report nobody can act on.
            didFail = true
            return .error(seq: seq, atMillis: at,
                          detail: "a \(payloadType) frame could not be read: \(error)", exi: hex)
        }
    }

    /// Something went wrong that was not a frame — a socket that closed, a state machine that gave up.
    ///
    /// An event rather than a thrown error because the stream has already started: a consumer that
    /// has been receiving events needs to be told this one is the last, and silence leaves it waiting.
    public func failure(_ detail: String) -> BridgeEvent {
        didFail = true
        defer { seq += 1 }
        return .error(seq: seq, atMillis: monotonicMillis() - start, detail: detail, exi: nil)
    }

    /// The session ended. `failed` when any error event preceded this one.
    public func finished() -> BridgeEvent {
        defer { seq += 1 }
        return .sessionFinished(seq: seq, atMillis: monotonicMillis() - start,
                                exchanges: exchanges, outcome: didFail ? "failed" : "completed")
    }
}


/// Turns a recorded session into the event stream the bridge emits (`docs/CONCEPT.md` B1).
///
/// **A recorded session, not a live one, and that is what makes the stream checkable at all.** The
/// traces under `Vectors/Session.*.trace.json` are whole EV↔station sessions captured frame by frame,
/// so the event stream they produce is deterministic and can be pinned by a corpus — which a stream
/// built from a socket never could.
///
/// The clock is injected for the same reason, and **the ports have to read it in the same places**:
/// once before the first event and once per event. The corpus is generated with a clock that steps by
/// one millisecond per reading, so a port that read it a different number of times would produce the
/// same events with different timings.
public final class SessionEventStream {

    private let monotonicMillis: () -> Int

    public init(monotonicMillis: @escaping () -> Int) {
        self.monotonicMillis = monotonicMillis
    }

    /// Every event of one recorded session, in order.
    public func replay(_ trace: JsonObject) throws -> [BridgeEvent] {

        guard let exchanges = trace["exchanges"] as? JsonArray,
              let name      = trace["name"] as? JsonString,
              let proto     = trace["protocol"] as? JsonString,
              let mode      = trace["mode"] as? JsonString
        else { throw JsonLdError("a session trace needs a name, a protocol, a mode and exchanges.") }

        let start = monotonicMillis()
        var events: [BridgeEvent] = []
        var seq = 0
        var failed = false

        // Once, at the head of the stream, rather than beside every reading: it is a property of the
        // station, and repeating it per message would invite checking each reading against the key
        // that arrived with it — which is no check at all.
        var meterKey: (x: String, y: String)?
        if let key = trace["meterKey"] as? JsonObject,
           let x = key["x"] as? JsonString, let y = key["y"] as? JsonString {
            meterKey = (x.value, y.value)
        }

        events.append(.sessionStarted(seq: seq, atMillis: monotonicMillis() - start,
                                      name: name.value, protocolName: proto.value, mode: mode.value,
                                      meterKey: meterKey))
        seq += 1

        for exchange in exchanges.asList {
            for (side, direction) in [("request", "out"), ("response", "in")] {

                guard let object = exchange as? JsonObject,
                      let frame = object[side] as? JsonObject else { continue }

                let event = describe(frame, direction: direction, seq: seq,
                                     at: monotonicMillis() - start)
                seq += 1

                if case .error = event { failed = true }
                events.append(event)
            }
        }

        events.append(.sessionFinished(seq: seq, atMillis: monotonicMillis() - start,
                                       exchanges: exchanges.count,
                                       outcome: failed ? "failed" : "completed"))
        return events
    }


    /// One recorded frame as an event — the message twice over, or an error naming the frame.
    private func describe(_ frame: JsonObject, direction: String, seq: Int, at: Int) -> BridgeEvent {

        let payloadType = (frame["payloadType"] as? JsonString)?.value ?? ""
        let messageName = (frame["message"] as? JsonString)?.value ?? ""
        let hex         = (frame["frame"] as? JsonString)?.value ?? ""

        do {
            let json = try MessageSetCodecs.toJSON(frame: SessionEventStream.bytes(hex),
                                                   payloadType: payloadType, messageName: messageName)

            return .message(seq: seq, atMillis: at, direction: direction, payloadType: payloadType,
                            messageName: messageName, exi: hex.lowercased(), json: json)
        } catch {
            // The frame goes out with the error. A decode failure whose bytes are not in the stream
            // is a report nobody can act on.
            return .error(seq: seq, atMillis: at,
                          detail: "\(messageName) (\(payloadType)) could not be read: \(error)",
                          exi: hex.lowercased())
        }
    }

    private static func bytes(_ hex: String) -> [UInt8] {
        let characters = Array(hex)
        return stride(from: 0, to: characters.count - 1, by: 2).compactMap {
            UInt8(String(characters[$0 ... $0 + 1]), radix: 16)
        }
    }
}


/// A clock that advances a fixed amount per reading. The corpus would not be a corpus otherwise, and
/// a live session gets the real one.
public final class SteppingClock {

    private var now = 0
    private let step: Int

    public init(stepMillis: Int = 1) { self.step = stepMillis }

    public func read() -> Int {
        let value = now
        now += step
        return value
    }
}
