package cloud.charging.v2g.tp

import cloud.charging.v2g.iso2.Iso15118_2Codec
import cloud.charging.v2g.iso20.ac.ACCodec
import cloud.charging.v2g.iso20.acdp.ACDPCodec
import cloud.charging.v2g.iso20.common.CommonMessagesCodec
import cloud.charging.v2g.iso20.dc.DCCodec
import cloud.charging.v2g.iso20.wpt.WPTCodec

/**
 * The outcome of [V2GTPDispatcher.decode].
 *
 * Kotlin's stand-in for C#'s `bool` + `out set` + `out message` + `out error`: a frame either
 * resolves to a set and a message, or it fails with a reason. Malformed EXI *inside* a recognised
 * set is not modelled here — that throws out of the codec, exactly as calling `decodeAny` directly
 * would.
 */
sealed interface V2GTPDecodeResult {

    /** The frame resolved to [set] and decoded to [message], one of that set's generated types. */
    data class Decoded(val set: MessageSet, val message: Any) : V2GTPDecodeResult

    /** Not a frame this dispatcher can decode: [error] says why, in the words the C# side uses. */
    data class Failed(val error: String) : V2GTPDecodeResult
}

/**
 * Maps V2GTP payload types (§ the 8-byte [V2GTP] header) to the message set that owns them, so a
 * transport layer can decode an incoming frame without knowing in advance which of the six
 * generated codecs applies, and wrap an already-EXI-encoded payload with the header for the set it
 * came from. An unknown payload type is reported as a clean error rather than guessed at.
 *
 * A faithful port of the C# `V2GTPDispatcher`, down to the error strings.
 */
object V2GTPDispatcher {

    /**
     * Reads the V2GTP header, validates the length field against what the frame actually carries,
     * resolves the payload type to a [MessageSet], and decodes the payload with that set's
     * generated `decodeAny`. A malformed header, a length mismatch, or an unrecognised payload type
     * is a [V2GTPDecodeResult.Failed]; malformed EXI within a recognised set still throws.
     *
     * The payload is copied out of [frame] — the generated decoders take a `ByteArray` beginning at
     * the EXI header, where the C# side passes a `ReadOnlySpan` and copies nothing.
     */
    fun decode(frame: ByteArray): V2GTPDecodeResult {

        val header = V2GTP.tryReadHeader(frame)
            ?: return V2GTPDecodeResult.Failed(
                "not a valid V2GTP frame (bad version bytes, or too short for the 8-byte header).")

        val payloadLength = frame.size - V2GTP.HEADER_SIZE
        if (header.payloadLength != payloadLength.toUInt())
            return V2GTPDecodeResult.Failed(
                "payload length mismatch: header declares ${header.payloadLength} byte(s), " +
                "frame carries $payloadLength.")

        val payload = frame.copyOfRange(V2GTP.HEADER_SIZE, frame.size)

        // NB: the SupportedAppProtocol handshake shares payload id 0x8001 with the DIN/-2 messages
        // (see V2GTP.PAYLOAD_TYPE_APP_PROTOCOL) and is disambiguated by session phase, not payload type —
        // so it is decoded explicitly by the SAP handshake, never through this payload-type dispatcher.
        // 0x8001 here therefore resolves to the -2 message set.
        return when (header.payloadType) {

            V2GTP.PAYLOAD_TYPE_DIN_ISO2_MAIN ->
                V2GTPDecodeResult.Decoded(MessageSet.Iso15118_2, Iso15118_2Codec.decodeAny(payload))

            V2GTP.PAYLOAD_TYPE_ISO20_MAIN ->
                V2GTPDecodeResult.Decoded(MessageSet.Iso20CommonMessages, CommonMessagesCodec.decodeAny(payload))

            V2GTP.PAYLOAD_TYPE_ISO20_AC ->
                V2GTPDecodeResult.Decoded(MessageSet.Iso20AC, ACCodec.decodeAny(payload))

            V2GTP.PAYLOAD_TYPE_ISO20_DC ->
                V2GTPDecodeResult.Decoded(MessageSet.Iso20DC, DCCodec.decodeAny(payload))

            V2GTP.PAYLOAD_TYPE_ISO20_WPT ->
                V2GTPDecodeResult.Decoded(MessageSet.Iso20WPT, WPTCodec.decodeAny(payload))

            V2GTP.PAYLOAD_TYPE_ISO20_ACDP ->
                V2GTPDecodeResult.Decoded(MessageSet.Iso20ACDP, ACDPCodec.decodeAny(payload))

            // Hand-formatted rather than String.format: that one is a JVM-only extension, and
            // nothing else on this path needs a JVM.
            else -> V2GTPDecodeResult.Failed(
                "unknown V2GTP payload type 0x" +
                header.payloadType.toInt().toString(16).uppercase().padStart(4, '0') + ".")
        }
    }

    /**
     * Prepends the V2GTP header for [set] to an already-EXI-encoded payload. Never inspects the
     * payload bytes themselves — encoding is still each set's own generated `encode`.
     */
    fun encode(set: MessageSet, exiPayload: ByteArray): ByteArray {
        val frame = ByteArray(V2GTP.HEADER_SIZE + exiPayload.size)
        V2GTP.writeHeader(frame, payloadTypeOf(set), exiPayload.size.toUInt())
        exiPayload.copyInto(frame, V2GTP.HEADER_SIZE)
        return frame
    }

    /** The wire payload type [set] is framed with. */
    fun payloadTypeOf(set: MessageSet): UShort = when (set) {
        MessageSet.AppProtocol         -> V2GTP.PAYLOAD_TYPE_APP_PROTOCOL
        MessageSet.Iso15118_2          -> V2GTP.PAYLOAD_TYPE_DIN_ISO2_MAIN
        MessageSet.Iso20CommonMessages -> V2GTP.PAYLOAD_TYPE_ISO20_MAIN
        MessageSet.Iso20AC             -> V2GTP.PAYLOAD_TYPE_ISO20_AC
        MessageSet.Iso20DC             -> V2GTP.PAYLOAD_TYPE_ISO20_DC
        MessageSet.Iso20WPT            -> V2GTP.PAYLOAD_TYPE_ISO20_WPT
        MessageSet.Iso20ACDP           -> V2GTP.PAYLOAD_TYPE_ISO20_ACDP
    }
}
