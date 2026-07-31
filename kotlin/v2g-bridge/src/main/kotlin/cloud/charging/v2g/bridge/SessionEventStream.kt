package cloud.charging.v2g.bridge

import cloud.charging.v2g.exi.JsonArray
import cloud.charging.v2g.exi.JsonObject
import cloud.charging.v2g.exi.JsonString

/**
 * Which message set a V2GTP frame belongs to, and how to read it as JSON-LD.
 *
 * **The payload type is not enough, and that is a fact about ISO 15118 rather than about this code.**
 * `0x8001` carries both the SupportedAppProtocol handshake and every ISO 15118-2 message — the
 * handshake happens before a protocol has been agreed, so it cannot have a payload type of its own.
 * The dispatcher resolves it by position; here the message's own name does, because the events are
 * built from a record of the session rather than from a live socket.
 *
 * A frame this cannot place becomes an error event, never a silently skipped one.
 */
object MessageSetCodecs {

    /** The V2GTP header: version, payload type, and the payload's length. */
    const val V2GTP_HEADER_BYTES = 8

    fun toJson(frame: ByteArray, payloadType: String, messageName: String): JsonObject {

        require(frame.size > V2GTP_HEADER_BYTES) {
            "a V2GTP frame is longer than its $V2GTP_HEADER_BYTES-byte header."
        }

        val payload = frame.copyOfRange(V2GTP_HEADER_BYTES, frame.size)
        val isSap = messageName.startsWith("SupportedAppProtocol")

        return when {
            payloadType == "0x8001" && isSap ->
                cloud.charging.v2g.appprotocol.SupportedAppProtocolCodecJson.toJson(
                    cloud.charging.v2g.appprotocol.SupportedAppProtocolCodec.decodeAny(payload))

            payloadType == "0x8001" ->
                cloud.charging.v2g.iso2.Iso15118_2CodecJson.toJson(
                    cloud.charging.v2g.iso2.Iso15118_2Codec.decodeAny(payload))

            payloadType == "0x8002" ->
                cloud.charging.v2g.iso20.common.CommonMessagesCodecJson.toJson(
                    cloud.charging.v2g.iso20.common.CommonMessagesCodec.decodeAny(payload))

            payloadType == "0x8003" ->
                cloud.charging.v2g.iso20.ac.ACCodecJson.toJson(
                    cloud.charging.v2g.iso20.ac.ACCodec.decodeAny(payload))

            payloadType == "0x8004" ->
                cloud.charging.v2g.iso20.dc.DCCodecJson.toJson(
                    cloud.charging.v2g.iso20.dc.DCCodec.decodeAny(payload))

            else -> throw IllegalArgumentException(
                "payload type '$payloadType' is not a message set this build carries.")
        }
    }
}


/**
 * Turns a recorded session into the event stream the bridge emits (`docs/CONCEPT.md` B1).
 *
 * **A recorded session, not a live one, and that is what makes the stream checkable at all.** The
 * traces under `Vectors/Session.*.trace.json` are whole EV↔station sessions captured frame by frame,
 * so the event stream they produce is deterministic and can be pinned by a corpus — which a stream
 * built from a socket never could. The live runner emits the same events from the same frames; what
 * differs is where the frames come from.
 *
 * The clock is injected for the same reason, and **the ports have to read it in the same places**:
 * once before the first event and once per event. The corpus is generated with a clock that steps by
 * one millisecond per reading, so a port that read it a different number of times would produce the
 * same events with different timings — which is exactly the kind of divergence the corpus exists to
 * catch.
 */
class SessionEventStream(private val monotonicMillis: () -> Long) {

    /** Every event of one recorded session, in order. */
    fun replay(trace: JsonObject): List<BridgeEvent> {

        val exchanges = trace["exchanges"] as JsonArray
        val start = monotonicMillis()
        val events = ArrayList<BridgeEvent>()
        var seq = 0
        var failed = false

        events.add(BridgeEvent.SessionStarted(
            seq = seq++,
            atMillis = monotonicMillis() - start,
            name = (trace["name"] as JsonString).value,
            protocol = (trace["protocol"] as JsonString).value,
            mode = (trace["mode"] as JsonString).value))

        for (exchange in exchanges.asList()) {
            for ((side, direction) in listOf("request" to "out", "response" to "in")) {

                val frame = (exchange as JsonObject)[side] as? JsonObject ?: continue
                val event = describe(frame, direction, seq++, monotonicMillis() - start)

                if (event is BridgeEvent.Error) failed = true
                events.add(event)
            }
        }

        events.add(BridgeEvent.SessionFinished(
            seq = seq,
            atMillis = monotonicMillis() - start,
            exchanges = exchanges.size,
            outcome = if (failed) "failed" else "completed"))

        return events
    }


    /** One recorded frame as an event — the message twice over, or an error naming the frame. */
    private fun describe(frame: JsonObject, direction: String, seq: Int, at: Long): BridgeEvent {

        val payloadType = (frame["payloadType"] as JsonString).value
        val messageName = (frame["message"] as JsonString).value
        val hex = (frame["frame"] as JsonString).value

        return try {
            BridgeEvent.Message(
                seq = seq,
                atMillis = at,
                direction = direction,
                payloadType = payloadType,
                messageName = messageName,
                exi = hex.lowercase(),
                json = MessageSetCodecs.toJson(hexToBytes(hex), payloadType, messageName))
        } catch (e: Exception) {
            // The frame goes out with the error. A decode failure whose bytes are not in the stream
            // is a report nobody can act on.
            BridgeEvent.Error(
                seq = seq,
                atMillis = at,
                detail = "$messageName ($payloadType) could not be read: ${e.message}",
                exi = hex.lowercase())
        }
    }

    private fun hexToBytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}


/**
 * A clock that advances a fixed amount per reading. The corpus would not be a corpus otherwise, and
 * a live session gets the real one.
 */
class SteppingClock(private val stepMillis: Long = 1) {

    private var now = 0L

    fun read(): Long {
        val value = now
        now += stepMillis
        return value
    }
}
