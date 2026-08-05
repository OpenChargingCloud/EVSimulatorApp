package cloud.charging.v2g.session

import cloud.charging.v2g.bridge.BridgeEvent
import cloud.charging.v2g.bridge.SessionConfig
import cloud.charging.v2g.bridge.SteppingClock
import cloud.charging.v2g.exi.JsonArray
import cloud.charging.v2g.exi.JsonObject
import cloud.charging.v2g.exi.JsonValue
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live runner against the recorded sessions — over a real socket.
 *
 * ## The claim being checked
 *
 * `SessionEventStream`'s documentation has said, since before there was a live runner, that *"the
 * live runner emits the same events from the same frames; what differs is where the frames come
 * from."* That was a design intention with nothing holding it. This is what holds it: the same
 * recorded frames, delivered over TCP by [RecordedStation], have to produce **exactly** the events
 * `Vectors/Bridge.events.json` pins — property for property, timing included.
 *
 * The timings line up because the clock is injected and stepped, and because a live session reads it
 * in the same places a replay does: once before the first event, once per event. That coupling is
 * load-bearing and is the reason a mutation adding a single stray clock read fails at the second
 * event rather than somewhere unrelated.
 *
 * ## And the socket is doing real work
 *
 * [RecordedStation] answers in three-byte pieces, so every frame arrives across several reads. The
 * byte-array replays the EVCC tests use cannot produce that, and a reader that assumed one `read`
 * per frame passes all of them.
 *
 * The station also compares each request against the recording before answering, so this is still
 * the byte oracle the EVCC trace tests are — reached through a different door.
 */
class LiveSessionRunnerTest {

    private val repositoryRoot: File by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")
        dir
    }

    private fun corpusEvents(session: String): JsonArray {
        val file = File(repositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json")
        val sessions = (JsonValue.parse(file.readText()) as JsonObject)["sessions"] as JsonObject
        return sessions[session] as? JsonArray ?: error("the corpus has no events for $session")
    }


    /** Runs one recorded session end to end and returns the events the WebView would have received. */
    private fun replayOverASocket(session: String,
                                  protocol: String,
                                  mode: String): Pair<List<BridgeEvent>, RecordedStation> {

        val trace   = RecordedStation.load(session)
        val station = RecordedStation(trace).start()

        val config = SessionConfig(
            host          = "127.0.0.1",
            port          = station.port,
            transport     = "tcp",
            protocol      = protocol,
            mode          = mode,
            authorization = "eim")

        val runner = LiveSessionRunner(
            connect          = { TcpV2GTransport.connect(it, readTimeoutMillis = 5_000) },
            monotonicMillis  = SteppingClock()::read,
            // -20 stamps a timestamp into every message header, so without the recording's own
            // instant not one frame would match. Same value the EVCC trace tests use.
            wallClockSeconds = { 1_767_225_600uL },
            pollDelay        = { },
            sessionName      = { session })

        val events = mutableListOf<BridgeEvent>()
        runner.run(config, events::add)

        station.close()
        return events to station
    }


    private fun check(session: String, protocol: String, mode: String) {

        val (events, station) = replayOverASocket(session, protocol, mode)

        assertNull(station.complaint, "the station refused what the EV sent")
        assertEquals(station.exchanges, station.served,
            "$session: the EV walked through ${station.served} of ${station.exchanges} recorded " +
            "exchanges — it ended early, which sends no wrong bytes and would otherwise pass")

        val expected = corpusEvents(session)

        assertEquals(expected.size, events.size, "$session: event count")

        for ((i, event) in events.withIndex())
            assertEquals(expected[i].toJsonString(), BridgeEvent.toJson(event).toJsonString(),
                         "$session/$i")
    }


    @Test fun `the iso2 AC session produces the corpus events`()  = check("iso2-ac-eim",  "iso15118-2",  "ac")
    @Test fun `the iso2 DC session produces the corpus events`()  = check("iso2-dc-eim",  "iso15118-2",  "dc")
    @Test fun `the iso20 AC session produces the corpus events`() = check("iso20-ac-eim", "iso15118-20", "ac")
    @Test fun `the iso20 DC session produces the corpus events`() = check("iso20-dc-eim", "iso15118-20", "dc")


    /**
     * A station that is not there is a failed session with events, not a thrown command.
     *
     * The opposite of `TraceSessionRunner`, and deliberately: that one refuses before anything starts,
     * because it can tell. This one has already told the consumer a session began — the connection is
     * the first thing that happens *after* that — so the failure has to arrive as the last event
     * rather than as silence.
     */
    @Test
    fun `a station that refuses the connection ends the stream instead of hanging it`() {

        val runner = LiveSessionRunner(
            connect         = { throw java.net.ConnectException("connection refused") },
            monotonicMillis = SteppingClock()::read)

        val events = mutableListOf<BridgeEvent>()

        runner.run(SessionConfig("127.0.0.1", 1, "tcp", "iso15118-2", "ac", "eim"), events::add)

        assertEquals(3, events.size, "started, the failure, finished")
        assertTrue(events[0] is BridgeEvent.SessionStarted)
        assertTrue((events[1] as BridgeEvent.Error).detail.contains("connection refused"))

        val finished = events[2] as BridgeEvent.SessionFinished
        assertEquals("failed", finished.outcome)
        assertEquals(0, finished.exchanges)
    }


    /**
     * Plug & Charge without credentials is refused before the socket opens.
     *
     * A thrown command rather than an event stream, because nothing has started yet — and four
     * exchanges into a session is not where to discover that the EV cannot sign anything.
     */
    @Test
    fun `a pnc session with no contract credentials never reaches the station`() {

        var connected = false

        val runner = LiveSessionRunner(connect = { connected = true; error("should not get here") })

        val thrown = kotlin.runCatching {
            runner.run(SessionConfig("127.0.0.1", 15118, "tls", "iso15118-2", "ac", "pnc")) { }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException, "expected a refusal, got $thrown")
        assertTrue(thrown.message!!.contains("Plug & Charge"))
        assertTrue(!connected, "the transport was opened for a session that cannot be authorized")
    }


    /**
     * The frame tap fires once per frame, in the order the frames crossed.
     *
     * Compared against the recording's own frame list rather than against a uniqueness assumption: a
     * charging session polls, so `ChargingStatusReq` really does go out three times with identical
     * bytes. An earlier draft asserted that the frames were distinct and failed on exactly that —
     * 24 events, 19 distinct — which is the recording being right and the test being wrong.
     */
    @Test
    fun `every frame is tapped exactly once and in order`() {

        val (events, _) = replayOverASocket("iso2-ac-eim", "iso15118-2", "ac")

        val recorded = (RecordedStation.load("iso2-ac-eim")["exchanges"] as JsonArray).asList()
            .flatMap { exchange ->
                listOf("request" to "out", "response" to "in").mapNotNull { (side, direction) ->
                    ((exchange as JsonObject)[side] as? JsonObject)?.let {
                        direction to (it["frame"] as cloud.charging.v2g.exi.JsonString).value.lowercase()
                    }
                }
            }

        val tapped = events.filterIsInstance<BridgeEvent.Message>().map { it.direction to it.exi }

        assertEquals(recorded, tapped,
            "the tapped frames are not the recorded frames, once each, in order")
    }
}
