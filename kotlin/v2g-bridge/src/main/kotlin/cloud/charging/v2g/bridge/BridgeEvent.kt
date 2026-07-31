package cloud.charging.v2g.bridge

import cloud.charging.v2g.exi.JsonObject
import cloud.charging.v2g.exi.JsonValue

/**
 * One event on the bridge between the session and whatever is watching it — the WebView inspector,
 * Chargy, a log file (`docs/CONCEPT.md` B1).
 *
 * **Every message goes out twice: as JSON-LD and as the raw V2GTP frame.** That is B1's wording and
 * it is not redundancy. The JSON-LD is what a person or a tool can read; the frame is what actually
 * crossed the wire. Either alone is a claim — together they are a claim and its evidence, and anyone
 * holding the event can check one against the other without asking this application anything.
 *
 * The events are a **record of what happened**, never a channel for making things happen. There is
 * no event that asks the far side to do something, which is what keeps the stream safe to hand to a
 * WebView.
 *
 * A port of C#'s `BridgeEvent`, held to `Vectors/Bridge.events.json` character for character.
 */
sealed class BridgeEvent {

    /** Position in the stream, from 0. A consumer that sees a gap has lost events. */
    abstract val seq: Int

    /**
     * Milliseconds since the session started, from a monotonic clock.
     *
     * Relative and monotonic rather than wall-clock, because these are *timings*: a wall clock can
     * step backwards over NTP or a timezone change and would then show a response arriving before
     * its request.
     */
    abstract val atMillis: Long

    abstract val kind: String

    /** The session began — what the whole stream is about, so a consumer joining at the top needs
     *  no configuration of its own. */
    data class SessionStarted(
        override val seq: Int,
        override val atMillis: Long,
        val name: String,
        val protocol: String,
        val mode: String,
    ) : BridgeEvent() {
        override val kind get() = "sessionStarted"
    }

    /** A message crossed the wire. */
    data class Message(
        override val seq: Int,
        override val atMillis: Long,
        /** `out` for a message this EV sent, `in` for one it received. */
        val direction: String,
        /** The V2GTP payload type, e.g. `0x8001` — which message set the frame is in. */
        val payloadType: String,
        val messageName: String,
        /** The complete V2GTP frame, header included, as lower-case hex. */
        val exi: String,
        /** The same message as JSON-LD — the generated form, not a summary of it. */
        val json: JsonObject,
    ) : BridgeEvent() {
        override val kind get() = "message"
    }

    data class SessionFinished(
        override val seq: Int,
        override val atMillis: Long,
        val exchanges: Int,
        /** `completed`, or `failed` when an error event preceded this one. */
        val outcome: String,
    ) : BridgeEvent() {
        override val kind get() = "sessionFinished"
    }

    /**
     * Something went wrong.
     *
     * It carries the frame that caused it when there was one: a decode failure whose bytes are not
     * in the stream is a report nobody can act on.
     */
    data class Error(
        override val seq: Int,
        override val atMillis: Long,
        val detail: String,
        val exi: String? = null,
    ) : BridgeEvent() {
        override val kind get() = "error"
    }


    companion object {

        /**
         * An event as the JSON a consumer receives.
         *
         * Hand-written rather than reflected, because this shape is a wire format the moment a
         * WebView reads it: a property renamed by a serialiser setting somewhere would be a breaking
         * change nobody wrote down.
         */
        fun toJson(event: BridgeEvent): JsonObject {

            val json = JsonObject()
            json["seq"] = JsonValue.of(event.seq)
            json["atMillis"] = JsonValue.of(event.atMillis)
            json["kind"] = JsonValue.of(event.kind)

            when (event) {

                is SessionStarted -> {
                    json["name"] = JsonValue.of(event.name)
                    json["protocol"] = JsonValue.of(event.protocol)
                    json["mode"] = JsonValue.of(event.mode)
                }

                is Message -> {
                    json["direction"] = JsonValue.of(event.direction)
                    json["payloadType"] = JsonValue.of(event.payloadType)
                    json["messageName"] = JsonValue.of(event.messageName)
                    json["exi"] = JsonValue.of(event.exi)
                    json["json"] = event.json
                }

                is SessionFinished -> {
                    json["exchanges"] = JsonValue.of(event.exchanges)
                    json["outcome"] = JsonValue.of(event.outcome)
                }

                is Error -> {
                    json["detail"] = JsonValue.of(event.detail)
                    event.exi?.let { json["exi"] = JsonValue.of(it) }
                }
            }

            return json
        }
    }
}
