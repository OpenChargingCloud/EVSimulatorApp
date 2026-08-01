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

    fun toJson(frame: ByteArray, payloadType: String, messageName: String): JsonObject =
        toJson(frame, payloadType, messageName.startsWith("SupportedAppProtocol"))

    /**
     * The same, told directly whether the frame is a SupportedAppProtocol one.
     *
     * A live session has no recorded message name to read that off, and it does not need one: the
     * dispatcher's rule is that SAP is what comes *first*, and the runner driving the handshake is
     * the one place that knows without guessing. Deciding it from the frame's own bytes would be a
     * guess — both grammars will decode a 0x8001 payload, and the wrong one produces a message that
     * looks plausible.
     */
    fun toJson(frame: ByteArray, payloadType: String, isSap: Boolean): JsonObject {

        require(frame.size > V2GTP_HEADER_BYTES) {
            "a V2GTP frame is longer than its $V2GTP_HEADER_BYTES-byte header."
        }

        val payload = frame.copyOfRange(V2GTP_HEADER_BYTES, frame.size)

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


    /**
     * The message's name, read out of the document.
     *
     * The recorder derives this from the decoded object's *type*, which a live session cannot do
     * without reflection in three languages that name types three different ways. It does not have
     * to: the JSON-LD emitter writes the type name as `@type`, so the same answer is already in the
     * document, and reading it there is language-neutral.
     *
     * The rule is structural rather than a table. ISO 15118-2 wraps everything in a `V2G_Message`, so
     * the interesting name is the body element's; the -20 sets and the SupportedAppProtocol handshake
     * decode straight to the message, so the document is the message. C#'s
     * `TheNameInTheDocumentIsTheNameTheRecorderGave` holds that claim to all 196 recorded events, and
     * so does this back end's twin.
     */
    fun messageName(json: JsonObject): String {

        val body = json["body"] as? JsonObject
            ?: return (json["@type"] as JsonString).value

        val element = body["bodyElement"] as? JsonObject
            ?: return "V2G_Message(empty body)"

        return (element["@type"] as JsonString).value
    }
}


/**
 * The event stream of a session that is happening now.
 *
 * `SessionEventStream` turns a *recording* into events, all of them at once, because a recording is
 * over. A live session hands over one frame at a time and does not know how many there will be, so
 * the stream is driven from outside: [started] once, [frame] per frame, [finished] at the end.
 *
 * **The events are the same events.** That is the point of the class existing rather than the runner
 * assembling them: `LiveSessionRunnerTest` drives this over the recorded frames — through a real
 * socket — and requires exactly what `Vectors/Bridge.events.json` pins, event for event. What differs
 * between a replay and a live session is where the frames come from, and nothing else, which is what
 * `SessionEventStream`'s own documentation has claimed since before there was a live runner.
 *
 * Not thread-safe, deliberately: one session, one thread, one caller. The sequence numbers are the
 * consumer's guarantee that nothing was lost, and a stream two threads could interleave would not
 * have them.
 */
class LiveEventStream(private val monotonicMillis: () -> Long) {

    private var start  = 0L
    private var seq    = 0
    private var failed = false

    /** How many request/response exchanges this session ran, counted as messages sent. */
    var exchanges = 0
        private set

    /** The session began. Starts the clock, so nothing before this is timed. */
    fun started(name: String, protocol: String, mode: String): BridgeEvent {
        start = monotonicMillis()
        return BridgeEvent.SessionStarted(
            seq      = seq++,
            atMillis = monotonicMillis() - start,
            name     = name,
            protocol = protocol,
            mode     = mode)
    }

    /**
     * One frame that crossed the wire, as the event a consumer receives.
     *
     * @param isSap whether this is a SupportedAppProtocol frame — see [MessageSetCodecs.toJson].
     */
    fun frame(frame: ByteArray, payloadType: String, direction: String, isSap: Boolean): BridgeEvent {

        val at  = monotonicMillis() - start
        val hex = frame.joinToString("") { "%02x".format(it) }

        if (direction == "out") exchanges++

        return try {
            val json = MessageSetCodecs.toJson(frame, payloadType, isSap)
            BridgeEvent.Message(
                seq         = seq++,
                atMillis    = at,
                direction   = direction,
                payloadType = payloadType,
                messageName = MessageSetCodecs.messageName(json),
                exi         = hex,
                json        = json)
        } catch (e: Exception) {
            // The frame goes out with the error. A decode failure whose bytes are not in the stream
            // is a report nobody can act on.
            failed = true
            BridgeEvent.Error(
                seq      = seq++,
                atMillis = at,
                detail   = "a $payloadType frame could not be read: ${e.message}",
                exi      = hex)
        }
    }

    /**
     * Something went wrong that was not a frame — a socket that closed, a state machine that gave up.
     *
     * An event rather than an exception because the stream has already started: a consumer that has
     * been receiving events needs to be told this one is the last, and silence leaves it waiting.
     */
    fun failure(detail: String): BridgeEvent {
        failed = true
        return BridgeEvent.Error(seq = seq++, atMillis = monotonicMillis() - start, detail = detail)
    }

    /** The session ended. `failed` when any error event preceded this one. */
    fun finished(): BridgeEvent =
        BridgeEvent.SessionFinished(
            seq       = seq++,
            atMillis  = monotonicMillis() - start,
            exchanges = exchanges,
            outcome   = if (failed) "failed" else "completed")
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
