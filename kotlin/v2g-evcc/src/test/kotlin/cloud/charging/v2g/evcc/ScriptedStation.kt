package cloud.charging.v2g.evcc

import cloud.charging.v2g.appprotocol.ResponseCode
import cloud.charging.v2g.appprotocol.SupportedAppProtocolCodec
import cloud.charging.v2g.appprotocol.SupportedAppProtocolRes
import cloud.charging.v2g.tp.MessageSet
import cloud.charging.v2g.tp.V2GTP
import cloud.charging.v2g.tp.V2GTPDispatcher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A station with a script instead of a state machine: its responses are laid out in full before the
 * session starts, and it never reads what the EV sent.
 *
 * That deafness is the point. The C# tests for these behaviours (`EvccReadsTheOfferTests`,
 * `Evcc2EnergyTransferModeTests`, `Evcc20DynamicModeTests`) doctor one field of a real SECC's
 * response and let the rest of the session run live; the ports have no SECC, and a scripted answer
 * sequence is the smallest thing that puts a **foreign offer** in front of the EVCC — the one thing
 * the trace corpus structurally cannot contain, because every trace is a session with our own C#
 * station, which offers exactly what this EVCC prefers. A constant and a list agree until a foreign
 * station disagrees; these tests are that station.
 *
 * The EVCC alternates write/read strictly, so pre-canned responses need no sequencing. A session
 * that refuses mid-script never reads the rest; a session that outruns the script hits the end of
 * the stream (`EOFException`), which the partial-script tests treat as the expected stop — the
 * request under test is already in [requests] by then.
 */
class ScriptedStation(vararg frames: ByteArray) {

    private val sent = ByteArrayOutputStream()

    val stream = V2GTPStream(
        ByteArrayInputStream(frames.fold(ByteArray(0)) { acc, f -> acc + f }), sent)

    /** The requests the EV actually wrote, one whole V2GTP frame each, header included. */
    fun requests(): List<ByteArray> {
        val bytes  = sent.toByteArray()
        val frames = mutableListOf<ByteArray>()
        var at = 0
        while (at + V2GTP.HEADER_SIZE <= bytes.size) {
            val header = V2GTP.tryReadHeader(bytes.copyOfRange(at, at + V2GTP.HEADER_SIZE))
                ?: error("request at offset $at is not a V2GTP frame")
            val total = V2GTP.HEADER_SIZE + header.payloadLength.toInt()
            frames.add(bytes.copyOfRange(at, at + total))
            at += total
        }
        return frames
    }

    /** The EXI payloads of every session request after the SAP handshake, ready for a codec. */
    fun sessionRequestPayloads(): List<ByteArray> =
        requests().drop(1).map { it.copyOfRange(V2GTP.HEADER_SIZE, it.size) }

    companion object {

        /** A framed `SupportedAppProtocolRes` — OK, accepting [schemaID]. */
        fun sapOk(schemaID: UByte? = 1u): ByteArray {
            val payload = SupportedAppProtocolCodec.encode(
                SupportedAppProtocolRes(ResponseCode.OK_SuccessfulNegotiation, schemaID))
            val frame = ByteArray(V2GTP.HEADER_SIZE + payload.size)
            V2GTP.writeHeader(frame, V2GTP.PAYLOAD_TYPE_APP_PROTOCOL, payload.size.toUInt())
            payload.copyInto(frame, V2GTP.HEADER_SIZE)
            return frame
        }

        /** An already-encoded session response, framed for [set]. */
        fun framed(set: MessageSet, exiPayload: ByteArray): ByteArray =
            V2GTPDispatcher.encode(set, exiPayload)
    }
}
