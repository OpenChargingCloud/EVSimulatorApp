package cloud.charging.v2g.bridge

import cloud.charging.v2g.exi.JsonObject

/**
 * Whatever turns an approved [SessionConfig] into a stream of [BridgeEvent]s.
 *
 * **This exists so the Capacitor adapter can be transport and nothing else.** The plugin's job is to
 * carry a command in and events out; what actually happens in between — a socket to a station, a
 * recorded trace, a fixture in a test — is not the bridge's business, and threading it through the
 * plugin would put session logic in the one file that cannot be unit-tested on a laptop.
 *
 * Blocking, and deliberately so: the caller picks the thread, because on Android that decision
 * belongs to whoever owns the lifecycle. [run] returns when the session is over.
 *
 * A failure that prevents the session from starting at all is an **exception**, not an error event.
 * The two are different things to a caller: an exception means the command was rejected and no
 * stream exists, while an error event means a stream is running and something in it went wrong.
 */
fun interface SessionRunner {

    /** Runs one session, handing each event to [emit] in order, and returns when it has ended. */
    fun run(config: SessionConfig, emit: (BridgeEvent) -> Unit)
}


/**
 * A [SessionRunner] that replays a recorded session instead of opening a socket.
 *
 * The traces under `Vectors/Session.*.trace.json` are whole EV↔station exchanges captured frame by
 * frame, so this produces exactly the stream `Vectors/Bridge.events.json` pins — which makes the
 * whole path, from the WebView's command to the events it renders, demonstrable on a real phone
 * without a station in the room.
 *
 * @param trace  finds the recording for a configuration, or returns null if there is none. Supplied
 *               by the host application because where a trace lives is a packaging question: an
 *               Android asset, an iOS bundle resource, a file a developer dropped in.
 */
class TraceSessionRunner(
    private val trace: (SessionConfig) -> JsonObject?,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : SessionRunner {

    override fun run(config: SessionConfig, emit: (BridgeEvent) -> Unit) {

        val recording = trace(config)
            ?: throw IllegalStateException(
                "no recorded session for ${config.protocol} ${config.mode}.")

        SessionEventStream(monotonicMillis).replay(recording).forEach(emit)
    }
}
