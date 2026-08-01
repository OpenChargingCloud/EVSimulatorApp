package cloud.charging.v2g.session

import cloud.charging.v2g.bridge.BridgeEvent
import cloud.charging.v2g.bridge.LiveEventStream
import cloud.charging.v2g.bridge.SessionConfig
import cloud.charging.v2g.bridge.SessionRunner
import cloud.charging.v2g.evcc.Evcc2
import cloud.charging.v2g.evcc.Evcc20Ac
import cloud.charging.v2g.evcc.Evcc20Dc
import cloud.charging.v2g.evcc.PncEvccOptions
import cloud.charging.v2g.evcc.PowerMode
import cloud.charging.v2g.evcc.ProtocolVariant
import cloud.charging.v2g.evcc.SapHandshake
import cloud.charging.v2g.evcc.V2GTPStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * A [SessionRunner] that talks to a real station.
 *
 * ## What is new here, and what is not
 *
 * Nothing about the *protocol* is new. `SapHandshake`, `Evcc2` and `Evcc20Ac`/`Evcc20Dc` are the same
 * state machines the trace tests hold to the recorded sessions byte for byte, and this class does not
 * touch them. What it adds is where the bytes come from and where the events go:
 *
 *  - the frames arrive on a socket instead of a `ByteArrayInputStream`, and
 *  - every frame that crosses becomes a [BridgeEvent], through `V2GTPStream`'s frame tap.
 *
 * **So the interesting property is that this changes nothing.** `LiveSessionRunnerTest` drives it
 * over the *recorded* frames, delivered by a real loopback listener, and requires exactly the events
 * `Vectors/Bridge.events.json` pins — the same events a replay produces, from the same frames,
 * because the difference really is only where they came from. The listener answers in three-byte
 * pieces, which is the one thing a `ByteArrayInputStream` can never test: measured, a `readExactly`
 * that assumed one read per frame passes every EVCC trace test and fails four of these.
 *
 * ## The transport is injected
 *
 * [connect] rather than a socket, so everything above the socket is testable and the socket itself is
 * one small file ([TcpV2GTransport]) with no session logic in it. It is also where TLS lives, and TLS
 * is a trust decision rather than a transport detail — see that file.
 *
 * ## Blocking
 *
 * [run] returns when the session is over, on the caller's thread. That is `SessionRunner`'s contract
 * and it is what makes the Capacitor adapter's job a `Thread` and nothing more.
 */
class LiveSessionRunner(

    /** Opens the connection an approved configuration describes. */
    private val connect: (SessionConfig) -> V2GConnection,

    /**
     * Contract credentials, for `authorization = "pnc"`.
     *
     * Supplied rather than loaded here, because a private key's home is the platform keystore
     * (§3.4) and this class has no business knowing which one. A `pnc` configuration with none is
     * refused before the socket opens — four exchanges in is not where to discover it.
     */
    private val pnc: PncEvccOptions? = null,

    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },

    /**
     * Wall-clock seconds since the epoch, for the message headers ISO 15118-20 stamps on every
     * message. Separate from [monotonicMillis] because they answer different questions: this one has
     * to match what the station believes the time is, and that one must never step backwards.
     */
    private val wallClockSeconds: () -> ULong = { System.currentTimeMillis().toULong() / 1000u },

    private val pollDelay: (Long) -> Unit = { Thread.sleep(it) },

    /** The label the stream opens with. A packaging decision, like where a trace lives. */
    private val sessionName: (SessionConfig) -> String =
        { "${it.protocol}-${it.mode}-${it.authorization}" },

) : SessionRunner {


    override fun run(config: SessionConfig, emit: (BridgeEvent) -> Unit) {

        // Before the socket, not after: a session that cannot be authorized the way it was approved
        // should not reach a station at all.
        if (config.authorization == "pnc" && pnc == null)
            throw IllegalStateException(
                "this configuration asks for Plug & Charge, and no contract credentials are installed.")

        val variant = when (config.protocol) {
            "iso15118-2"  -> ProtocolVariant.Iso15118_2
            "iso15118-20" -> ProtocolVariant.Iso15118_20
            else -> throw IllegalStateException("'${config.protocol}' is not a protocol this build runs.")
        }

        val mode = if (config.mode == "dc") PowerMode.Dc else PowerMode.Ac

        val events = LiveEventStream(monotonicMillis)
        emit(events.started(sessionName(config), config.protocol, config.mode))

        // The SupportedAppProtocol handshake shares payload id 0x8001 with every ISO 15118-2 message,
        // and the dispatcher's rule is that SAP is what comes FIRST. This is the one place that knows
        // it without guessing — deciding from the bytes would be a guess, because both grammars will
        // decode a 0x8001 payload and the wrong one yields a message that looks fine.
        var handshaking = true

        try {

            connect(config).use { connection ->

                val stream = V2GTPStream(connection.input, connection.output) { frame, payloadType, direction ->
                    emit(events.frame(frame, "0x%04x".format(payloadType.toInt()), direction, handshaking))
                }

                SapHandshake.runEvccSide(stream, variant, mode)
                handshaking = false

                when (variant) {

                    ProtocolVariant.Iso15118_2 ->
                        Evcc2(stream, mode, pollDelay).also { it.pnc = pnc }.run()

                    ProtocolVariant.Iso15118_20 ->
                        (if (mode == PowerMode.Dc) Evcc20Dc(stream, wallClockSeconds, pollDelay)
                         else                      Evcc20Ac(stream, wallClockSeconds, pollDelay)).run()
                }
            }

        } catch (e: Exception) {
            // The stream has already started, so this cannot become a refusal any more: a consumer
            // that has been receiving events needs to be told which one is the last.
            emit(events.failure("the session ended: ${e.message ?: e::class.java.name}"))
        }

        emit(events.finished())
    }
}


/**
 * An open connection to a station: a pair of blocking streams, and a way to close them.
 *
 * Deliberately not a `Socket`. What `V2GTPStream` needs is two streams, TLS turns one socket into a
 * different pair, and a test needs a pair that is not a socket at all — so the seam is drawn where
 * all three agree.
 */
interface V2GConnection : Closeable {
    val input: InputStream
    val output: OutputStream
}
