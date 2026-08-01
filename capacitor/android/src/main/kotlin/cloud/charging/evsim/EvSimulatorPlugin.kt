package cloud.charging.evsim

import cloud.charging.v2g.bridge.BridgeEvent
import cloud.charging.v2g.bridge.SessionConfig
import cloud.charging.v2g.bridge.SessionConfigException
import cloud.charging.v2g.bridge.SessionRunner
import cloud.charging.v2g.exi.JsonValue
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The Android half of the Capacitor adapter (`docs/CONCEPT.md` B1).
 *
 * **This file is transport and nothing else, on purpose.** It is one of the two files in the project
 * that no `gradle test`, `swift test`, `dotnet test` or `npm test` on a laptop can exercise as it
 * actually runs, so anything decided here is decided where nothing checks it. What a session is,
 * what an event is and what a configuration may say all live in `v2g-bridge`, where four back ends
 * agree on them character for character. The two translations that are genuinely this module's own
 * are in [BridgeCodec], where a unit test can reach them.
 *
 * The name `"EvSimulator"` is repeated in three places — here, in the iOS plugin's `identifier`, and
 * in `registerPlugin("EvSimulator")` on the TypeScript side. Nothing checks that the three agree.
 */
@CapacitorPlugin(name = "EvSimulator")
class EvSimulatorPlugin : Plugin() {

    companion object {

        /** The event name the WebView subscribes to. */
        const val EVENT = "v2gEvent"

        /**
         * What actually runs a session.
         *
         * Installed by the host application rather than constructed here, because where the frames
         * come from is not the bridge's business: a socket to a station, a recorded trace, a fixture.
         * See `SessionRunner` and `TraceSessionRunner`.
         */
        @JvmStatic
        var runner: SessionRunner? = null
    }


    private val sessions  = ConcurrentHashMap<String, Thread>()
    private val nextId    = AtomicLong(1)


    /**
     * Starts a session and begins the stream.
     *
     * Rejects — rather than starting a stream that immediately dies — when the configuration is
     * refused or no runner is installed. The distinction is what the caller acts on: a rejection
     * means no session exists, an error event means one exists and something in it went wrong.
     */
    @PluginMethod
    fun start(call: PluginCall) {

        val runner = Companion.runner
            ?: return call.reject("no session runner is installed on this build.")

        val config = try {
            BridgeCodec.configFrom(call.getObject("config"))
        } catch (e: SessionConfigException) {
            return call.reject(e.message)
        }

        val id = "s-" + nextId.getAndIncrement()

        // A thread rather than a coroutine: SessionRunner blocks by design, this module has no other
        // reason to depend on kotlinx-coroutines, and the lifetime being tracked is exactly one
        // blocking call. Daemon, so a session in flight cannot keep the process alive.
        val thread = Thread({

            var lastSeq = -1
            var lastAt  = 0L

            try {
                runner.run(config) { event ->
                    lastSeq = event.seq
                    lastAt  = event.atMillis
                    notifyListeners(EVENT, BridgeCodec.payload(event))
                }
            } catch (t: Throwable) {
                // The stream has already started, so this cannot be a rejection any more. It becomes
                // the last event instead — silence would leave the WebView waiting forever.
                notifyListeners(EVENT, BridgeCodec.payload(BridgeEvent.Error(
                    seq      = lastSeq + 1,
                    atMillis = lastAt,
                    detail   = "the session ended abnormally: ${t.message ?: t::class.java.name}")))
            } finally {
                sessions.remove(id)
            }

        }, "v2g-session-$id")

        sessions[id] = thread
        thread.isDaemon = true
        thread.start()

        call.resolve(JSObject().put("sessionId", id))
    }


    /** Stops a running session. Idempotent: stopping a finished or unknown session is not an error. */
    @PluginMethod
    fun stop(call: PluginCall) {

        val id = call.getString("sessionId")
            ?: return call.reject("'sessionId' is missing.")

        sessions.remove(id)?.interrupt()

        call.resolve()
    }

}


/**
 * The two translations that belong to this module rather than to `v2g-bridge`.
 *
 * Separate from the plugin so a JVM unit test can reach them: `Plugin` needs a `Bridge`, an
 * `Activity` and a `WebView`, and none of those exist on a laptop.
 */
internal object BridgeCodec {

    /**
     * An event, as the payload `notifyListeners` carries.
     *
     * **The event travels as text, not as a `JSObject`, and that is the one real decision in this
     * module.** `JSObject` is an `org.json.JSONObject`, and a round trip through one loses member
     * order at every level — measured on all 196 events of `Vectors/Bridge.events.json`: 196
     * changed. iOS's `JSONSerialization` does the same, in a different order.
     *
     * Neither library is wrong; member order is semantically insignificant in JSON. But the JSON-LD
     * documents in this repository are pinned as **text** across four back ends, so a document that
     * arrives in a third order on Android and a fourth on iOS is no longer the document the corpus
     * agreed on: an inspector would render one message three ways and anything hashing it would get
     * three hashes.
     *
     * So the string the back ends already agree on crosses unaltered, and `JSON.parse` — whose key
     * order the language specification fixes — rebuilds it on the far side. `EvSimulatorPluginTest`
     * keeps that measurement honest rather than leaving it as a claim in a comment.
     */
    fun payload(event: BridgeEvent): JSObject =
        JSObject().put("event", BridgeEvent.toJson(event).toJsonString())


    /**
     * The configuration out of a plugin call.
     *
     * Read from a key of its own rather than from the call's data, because Capacitor puts its own
     * `callbackId` in there and [SessionConfig] refuses unknown properties — deliberately, since a
     * silently ignored setting is one the user believes they approved.
     *
     * Handed on as text for the same reason [payload] is: the JSON this build reads is the one four
     * back ends agree on, not org.json's rendering of it.
     */
    fun configFrom(config: JSObject?): SessionConfig {

        if (config == null)
            throw SessionConfigException("a session configuration is a JSON object.")

        return SessionConfig.parse(JsonValue.parse(config.toString()))
    }
}
