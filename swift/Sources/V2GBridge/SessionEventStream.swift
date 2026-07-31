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

        guard frame.count > v2gtpHeaderBytes else {
            throw JsonLdError("a V2GTP frame is longer than its \(v2gtpHeaderBytes)-byte header.")
        }

        let payload = Array(frame[v2gtpHeaderBytes...])
        let isSap   = messageName.hasPrefix("SupportedAppProtocol")

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

        events.append(.sessionStarted(seq: seq, atMillis: monotonicMillis() - start,
                                      name: name.value, protocolName: proto.value, mode: mode.value))
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
