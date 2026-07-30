/// The header of a V2GTP frame: payload type and payload length.
///
/// Returned by ``V2GTP/readHeader(_:offset:)`` instead of C#'s `bool` plus two `out` parameters —
/// the same information, with `nil` carrying the "not a V2GTP frame" case.
public struct V2GTPHeader: Equatable, Sendable {
    public let payloadType: UInt16
    public let payloadLength: UInt32

    public init(payloadType: UInt16, payloadLength: UInt32) {
        self.payloadType = payloadType
        self.payloadLength = payloadLength
    }
}

/// V2G Transfer Protocol frame — an 8-byte header wrapping an EXI payload.
///
/// A faithful port of the C# `V2GTP`; the two must agree byte for byte. It has no dependency on any
/// codec, deliberately: reading a frame's type and length pulls in nothing, while resolving the type
/// to a decoder needs every message set (see `V2GDispatch`).
public enum V2GTP {

    public static let protocolVersion: UInt8        = 0x01
    public static let inverseProtocolVersion: UInt8 = 0xFE

    /// The SupportedAppProtocol handshake uses payload id 0x8001 — the **same** id the DIN/-2
    /// messages use (ISO 15118-20 §A, libcbv2g's `V2GTP20_SAP_PAYLOAD_ID`, Josev's
    /// `ISOV20PayloadTypes.SAP`). SAP and -2 frames are told apart by session phase, since SAP is
    /// always first, and never by payload type — so this is not a distinct wire value. It is kept
    /// as a named alias for the SAP framing path, which decodes SAP explicitly rather than through
    /// the payload-type dispatcher. A live interop run against Josev caught an earlier 0x8000 here
    /// as a wire-conformance bug.
    public static let payloadTypeAppProtocol: UInt16  = 0x8001
    public static let payloadTypeDinIso2Main: UInt16  = 0x8001
    public static let payloadTypeIso20Main: UInt16    = 0x8002
    public static let payloadTypeIso20AC: UInt16      = 0x8003
    public static let payloadTypeIso20DC: UInt16      = 0x8004
    public static let payloadTypeIso20ACDP: UInt16    = 0x8005
    public static let payloadTypeIso20WPT: UInt16     = 0x8006

    public static let headerSize = 8

    /// The 8-byte header for a payload of `payloadLength` bytes. Both multi-byte fields are
    /// big-endian, as on the wire.
    ///
    /// C# and Kotlin write into a caller-supplied array at an offset; here the header is returned,
    /// because a Swift array is a value type and the caller would not observe the mutation. Same
    /// bytes either way.
    public static func header(payloadType: UInt16, payloadLength: UInt32) -> [UInt8] {
        [
            protocolVersion,
            inverseProtocolVersion,
            UInt8(truncatingIfNeeded: payloadType >> 8),
            UInt8(truncatingIfNeeded: payloadType),
            UInt8(truncatingIfNeeded: payloadLength >> 24),
            UInt8(truncatingIfNeeded: payloadLength >> 16),
            UInt8(truncatingIfNeeded: payloadLength >> 8),
            UInt8(truncatingIfNeeded: payloadLength),
        ]
    }

    /// Reads the header at `offset`, or returns nil when `src` is too short for one or does not
    /// carry the version byte pair.
    ///
    /// Says nothing about whether the payload that follows is complete — that is the caller's
    /// comparison against ``V2GTPHeader/payloadLength``.
    public static func readHeader(_ src: [UInt8], offset: Int = 0) -> V2GTPHeader? {
        guard offset >= 0, src.count - offset >= headerSize else { return nil }
        guard src[offset] == protocolVersion, src[offset + 1] == inverseProtocolVersion else { return nil }

        let payloadType = UInt16(src[offset + 2]) << 8 | UInt16(src[offset + 3])

        let payloadLength = UInt32(src[offset + 4]) << 24
                          | UInt32(src[offset + 5]) << 16
                          | UInt32(src[offset + 6]) << 8
                          | UInt32(src[offset + 7])

        return V2GTPHeader(payloadType: payloadType, payloadLength: payloadLength)
    }
}
