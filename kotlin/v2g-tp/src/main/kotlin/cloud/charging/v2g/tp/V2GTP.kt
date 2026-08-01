package cloud.charging.v2g.tp

/**
 * The header of a V2GTP frame: protocol version, payload type and payload length.
 *
 * Returned by [V2GTP.tryReadHeader] instead of C#'s `bool` + two `out` parameters — same
 * information, and `null` carries the "not a V2GTP frame" case.
 */
data class V2GTPHeader(val payloadType: UShort, val payloadLength: UInt)

/**
 * V2G Transfer Protocol frame (8-byte header wrapping an EXI payload).
 *
 * A faithful port of the C# `V2GTP`; the two must agree byte for byte.
 */
object V2GTP {

    const val PROTOCOL_VERSION:         Byte = 0x01
    const val INVERSE_PROTOCOL_VERSION: Byte = 0xFE.toByte()

    // The SupportedAppProtocol handshake uses the SAP/EXI-encoded payload id 0x8001 — the SAME id the
    // DIN/-2 messages use (ISO 15118-20 §A / libcbv2g V2GTP20_SAP_PAYLOAD_ID / Josev's ISOV20PayloadTypes.SAP).
    // SAP and -2 frames are told apart by session phase (SAP is always first), NOT by payload type, so this
    // is not a distinct wire value. It is kept as a named alias for the SAP framing path (which decodes SAP
    // explicitly rather than through the payload-type dispatcher). A live interop run against Josev caught
    // the earlier 0x8000 here as a wire-conformance bug — see docs/interop-runs/2026-07-21-iso20-dc-pnc-tcp/.
    //
    // These are `val` rather than `const val`: Kotlin has no UShort literal, and the wire width is
    // what makes an out-of-range payload type unrepresentable rather than merely unlikely.
    val PAYLOAD_TYPE_APP_PROTOCOL:   UShort = 0x8001u.toUShort()
    val PAYLOAD_TYPE_DIN_ISO2_MAIN:  UShort = 0x8001u.toUShort()
    val PAYLOAD_TYPE_ISO20_MAIN:     UShort = 0x8002u.toUShort()
    val PAYLOAD_TYPE_ISO20_AC:       UShort = 0x8003u.toUShort()
    val PAYLOAD_TYPE_ISO20_DC:       UShort = 0x8004u.toUShort()
    val PAYLOAD_TYPE_ISO20_ACDP:     UShort = 0x8005u.toUShort()
    val PAYLOAD_TYPE_ISO20_WPT:      UShort = 0x8006u.toUShort()

    const val HEADER_SIZE = 8

    /**
     * The largest payload a reader will allocate for. A **receive** limit, not a wire change.
     *
     * The header's length is a 32-bit count supplied by the peer, so believing it means letting
     * whoever answered the socket choose an allocation of up to 4 GiB — out of one 8-byte frame that
     * carries no payload at all. On a phone that is an out-of-memory kill, and the frame that asked
     * for it cost the sender nothing.
     *
     * Nothing legitimate comes close. The largest frame in any recorded session is a -20
     * `AuthorizationReq` carrying a contract chain, at **921 bytes**; the largest EXI vector in the
     * corpus is 266. A megabyte is three orders of magnitude of headroom, so this refuses only what
     * no encoder here can produce. Kept in step with C#'s `V2GTP.MaximumPayloadBytes` and Swift's
     * `V2GTP.maximumPayloadBytes`.
     */
    const val MAXIMUM_PAYLOAD_BYTES = 1 shl 20

    /**
     * Writes the 8-byte header into [dest] at [offset] and returns [HEADER_SIZE].
     * Both multi-byte fields are big-endian, as on the wire.
     */
    fun writeHeader(dest: ByteArray, payloadType: UShort, payloadLength: UInt, offset: Int = 0): Int {
        require(offset >= 0 && dest.size - offset >= HEADER_SIZE) {
            "Need at least 8 bytes for V2GTP header."
        }

        val pt = payloadType.toInt()
        val pl = payloadLength.toLong()

        dest[offset]     = PROTOCOL_VERSION
        dest[offset + 1] = INVERSE_PROTOCOL_VERSION
        dest[offset + 2] = (pt shr 8).toByte()
        dest[offset + 3] = pt.toByte()
        dest[offset + 4] = (pl shr 24).toByte()
        dest[offset + 5] = (pl shr 16).toByte()
        dest[offset + 6] = (pl shr 8).toByte()
        dest[offset + 7] = pl.toByte()
        return HEADER_SIZE
    }

    /**
     * Reads the header at [offset], or returns `null` when [src] is too short for one or does not
     * carry the version byte pair. Says nothing about whether the payload that follows is complete —
     * that is the caller's comparison against [V2GTPHeader.payloadLength].
     */
    fun tryReadHeader(src: ByteArray, offset: Int = 0): V2GTPHeader? {
        if (offset < 0 || src.size - offset < HEADER_SIZE) return null
        if (src[offset] != PROTOCOL_VERSION || src[offset + 1] != INVERSE_PROTOCOL_VERSION) return null

        val payloadType = ((src[offset + 2].toInt() and 0xFF) shl 8) or
                           (src[offset + 3].toInt() and 0xFF)

        val payloadLength = ((src[offset + 4].toLong() and 0xFF) shl 24) or
                            ((src[offset + 5].toLong() and 0xFF) shl 16) or
                            ((src[offset + 6].toLong() and 0xFF) shl 8) or
                             (src[offset + 7].toLong() and 0xFF)

        return V2GTPHeader(payloadType.toUShort(), payloadLength.toUInt())
    }
}
