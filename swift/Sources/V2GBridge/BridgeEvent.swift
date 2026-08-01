import ExiRuntime

/// One event on the bridge between the session and whatever is watching it — the WebView inspector,
/// Chargy, a log file (`docs/CONCEPT.md` B1).
///
/// **Every message goes out twice: as JSON-LD and as the raw V2GTP frame.** That is B1's wording and
/// it is not redundancy. The JSON-LD is what a person or a tool can read; the frame is what actually
/// crossed the wire. Either alone is a claim — together they are a claim and its evidence, and anyone
/// holding the event can check one against the other without asking this application anything.
///
/// The events are a **record of what happened**, never a channel for making things happen. There is
/// no event that asks the far side to do something, which is what keeps the stream safe to hand to a
/// WebView.
///
/// A port of C#'s `BridgeEvent`, held to `Vectors/Bridge.events.json` character for character.
public enum BridgeEvent {

    /// The session began — what the whole stream is about, so a consumer joining at the top needs no
    /// configuration of its own.
    case sessionStarted(seq: Int, atMillis: Int, name: String, protocolName: String, mode: String)

    /// A message crossed the wire.
    ///
    /// - Parameters:
    ///   - direction: `out` for a message this EV sent, `in` for one it received.
    ///   - payloadType: the V2GTP payload type, e.g. `0x8001` — which message set the frame is in.
    ///   - exi: the complete V2GTP frame, header included, as lower-case hex.
    ///   - json: the same message as JSON-LD — the generated form, not a summary of it.
    case message(seq: Int, atMillis: Int, direction: String, payloadType: String,
                 messageName: String, exi: String, json: JsonObject)

    case sessionFinished(seq: Int, atMillis: Int, exchanges: Int, outcome: String)

    /// Something went wrong. It carries the frame that caused it when there was one: a decode failure
    /// whose bytes are not in the stream is a report nobody can act on.
    case error(seq: Int, atMillis: Int, detail: String, exi: String?)


    public var seq: Int {
        switch self {
        case let .sessionStarted(seq, _, _, _, _):   return seq
        case let .message(seq, _, _, _, _, _, _):    return seq
        case let .sessionFinished(seq, _, _, _):     return seq
        case let .error(seq, _, _, _):               return seq
        }
    }

    /// Milliseconds since the session started. The C# and Kotlin ports have always exposed this; it
    /// arrived here when the Capacitor adapter needed to time an event it had to invent.
    public var atMillis: Int {
        switch self {
        case let .sessionStarted(_, atMillis, _, _, _):   return atMillis
        case let .message(_, atMillis, _, _, _, _, _):    return atMillis
        case let .sessionFinished(_, atMillis, _, _):     return atMillis
        case let .error(_, atMillis, _, _):               return atMillis
        }
    }

    public var kind: String {
        switch self {
        case .sessionStarted:  return "sessionStarted"
        case .message:         return "message"
        case .sessionFinished: return "sessionFinished"
        case .error:           return "error"
        }
    }


    /// An event as the JSON a consumer receives.
    ///
    /// Hand-written rather than derived from `Codable`, because this shape is a wire format the
    /// moment a WebView reads it — and `Codable` would put member order in the hands of a synthesised
    /// encoder. The corpus compares text.
    public static func toJSON(_ event: BridgeEvent) -> JsonObject {

        let json = JsonObject()

        func head(_ seq: Int, _ atMillis: Int) {
            json["seq"] = JsonNumber(String(seq))
            json["atMillis"] = JsonNumber(String(atMillis))
            json["kind"] = JsonValue.of(event.kind)
        }

        switch event {

        case let .sessionStarted(seq, atMillis, name, protocolName, mode):
            head(seq, atMillis)
            json["name"] = JsonValue.of(name)
            json["protocol"] = JsonValue.of(protocolName)
            json["mode"] = JsonValue.of(mode)

        case let .message(seq, atMillis, direction, payloadType, messageName, exi, payload):
            head(seq, atMillis)
            json["direction"] = JsonValue.of(direction)
            json["payloadType"] = JsonValue.of(payloadType)
            json["messageName"] = JsonValue.of(messageName)
            json["exi"] = JsonValue.of(exi)
            json["json"] = payload

        case let .sessionFinished(seq, atMillis, exchanges, outcome):
            head(seq, atMillis)
            json["exchanges"] = JsonNumber(String(exchanges))
            json["outcome"] = JsonValue.of(outcome)

        case let .error(seq, atMillis, detail, exi):
            head(seq, atMillis)
            json["detail"] = JsonValue.of(detail)
            if let exi { json["exi"] = JsonValue.of(exi) }
        }

        return json
    }
}
