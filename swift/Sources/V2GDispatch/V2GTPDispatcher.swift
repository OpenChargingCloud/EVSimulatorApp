import V2GTP

import ExiIso2
import ExiIso20AC
import ExiIso20ACDP
import ExiIso20Common
import ExiIso20DC

/// The ISO 15118 message sets a V2GTP frame can carry. Mirrors the C# `MessageSet`.
public enum MessageSet: Sendable {
    case appProtocol
    case iso15118_2
    case iso20CommonMessages
    case iso20AC
    case iso20DC
    case iso20WPT
    case iso20ACDP
}

/// The outcome of ``V2GTPDispatcher/decode(_:)``.
///
/// Swift's stand-in for C#'s `bool` plus `out set`, `out message` and `out error`: a frame either
/// resolves to a set and a message, or it fails with a reason. Malformed EXI *inside* a recognised
/// set is not modelled here — that throws out of the codec, exactly as calling `decodeAny` directly
/// would. Keeping the two apart is the point: a framing problem is a value the caller can act on,
/// a broken payload is an error.
public enum V2GTPDecodeResult {
    /// The frame resolved to `set` and decoded to `message`, one of that set's generated types.
    case decoded(set: MessageSet, message: Any)
    /// Not a frame this dispatcher can decode; `error` says why, in the words the C# side uses.
    case failed(error: String)
}

/// Maps V2GTP payload types to the message set that owns them, so a transport layer can decode an
/// incoming frame without knowing in advance which codec applies, and can wrap an already-encoded
/// payload with the right header. An unknown payload type is reported as a clean error rather than
/// guessed at.
///
/// A faithful port of the C# `V2GTPDispatcher`, down to the error strings.
public enum V2GTPDispatcher {

    /// Reads the header, checks the declared length against what the frame carries, resolves the
    /// payload type, and decodes with that set's generated `decodeAny`.
    ///
    /// The payload is copied out of `frame`: the generated decoders take a `[UInt8]` beginning at
    /// the EXI header, where the C# side passes a `ReadOnlySpan` and copies nothing.
    public static func decode(_ frame: [UInt8]) throws -> V2GTPDecodeResult {

        guard let header = V2GTP.readHeader(frame) else {
            return .failed(error: "not a valid V2GTP frame (bad version bytes, or too short for the 8-byte header).")
        }

        let payloadLength = frame.count - V2GTP.headerSize
        guard header.payloadLength == UInt32(payloadLength) else {
            return .failed(error: "payload length mismatch: header declares \(header.payloadLength) byte(s), " +
                                  "frame carries \(payloadLength).")
        }

        let payload = Array(frame[V2GTP.headerSize...])

        // NB: the SupportedAppProtocol handshake shares payload id 0x8001 with the DIN/-2 messages
        // and is disambiguated by session phase, not payload type — so it is decoded explicitly by
        // the SAP handshake, never here. 0x8001 therefore resolves to the -2 message set, which is
        // also why this module does not depend on ExiAppProtocol.
        switch header.payloadType {

        case V2GTP.payloadTypeDinIso2Main:
            return .decoded(set: .iso15118_2, message: try Iso15118_2Codec.decodeAny(payload))

        case V2GTP.payloadTypeIso20Main:
            return .decoded(set: .iso20CommonMessages, message: try CommonMessagesCodec.decodeAny(payload))

        case V2GTP.payloadTypeIso20AC:
            return .decoded(set: .iso20AC, message: try ACCodec.decodeAny(payload))

        case V2GTP.payloadTypeIso20DC:
            return .decoded(set: .iso20DC, message: try DCCodec.decodeAny(payload))

        case V2GTP.payloadTypeIso20ACDP:
            return .decoded(set: .iso20ACDP, message: try ACDPCodec.decodeAny(payload))

        case V2GTP.payloadTypeIso20WPT:
            // The set is recognised but not generated: the Swift back end refuses WPT because
            // cbexigen's own encoder for WPT_LF_TransmitterDataType cannot represent even the
            // schema's required minimum, so there is nothing to check an implementation against.
            // Saying that is better than reporting an unknown payload type, which would suggest the
            // frame was malformed.
            return .failed(error: "ISO 15118-20 WPT is a known payload type, but no Swift codec is " +
                                  "generated for it — the set has no working reference encoder.")

        default:
            // Hand-formatted rather than String(format:), which lives in Foundation — nothing else
            // on this path needs it, and the Kotlin port avoids its JVM equivalent for the same
            // reason.
            let hex = String(header.payloadType, radix: 16, uppercase: true)
            return .failed(error: "unknown V2GTP payload type 0x" +
                                  String(repeating: "0", count: max(0, 4 - hex.count)) + hex + ".")
        }
    }

    /// Prepends the V2GTP header for `set` to an already-encoded EXI payload. Never inspects the
    /// payload bytes — encoding stays each set's own generated `encode`.
    public static func encode(_ set: MessageSet, _ exiPayload: [UInt8]) -> [UInt8] {
        V2GTP.header(payloadType: payloadType(of: set), payloadLength: UInt32(exiPayload.count)) + exiPayload
    }

    /// The wire payload type `set` is framed with.
    public static func payloadType(of set: MessageSet) -> UInt16 {
        switch set {
        case .appProtocol:         return V2GTP.payloadTypeAppProtocol
        case .iso15118_2:          return V2GTP.payloadTypeDinIso2Main
        case .iso20CommonMessages: return V2GTP.payloadTypeIso20Main
        case .iso20AC:             return V2GTP.payloadTypeIso20AC
        case .iso20DC:             return V2GTP.payloadTypeIso20DC
        case .iso20WPT:            return V2GTP.payloadTypeIso20WPT
        case .iso20ACDP:           return V2GTP.payloadTypeIso20ACDP
        }
    }
}
