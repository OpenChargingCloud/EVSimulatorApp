package cloud.charging.v2g.bridge

import cloud.charging.v2g.exi.JsonArray
import cloud.charging.v2g.exi.JsonObject
import cloud.charging.v2g.exi.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This back end's event stream, against the one C# produces.
 *
 * The events are what a WebView receives, so their shape is a wire format the moment anything reads
 * it — and the four back ends have to agree on it for the same reason they agree on the JSON-LD
 * documents. `Bridge.events.json` is that agreement, compared as text.
 *
 * The second test is the property nothing else checks: **within one event, the JSON-LD and the raw
 * EXI are the same message**. Each half is verified on its own elsewhere, and an event whose JSON-LD
 * came from a different frame than its `exi` would pass everything — while being exactly what an
 * inspector cannot see, since it shows you the JSON and tells you the bytes are right there.
 */
class BridgeEventStreamTest {

    private val repositoryRoot: File by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")
        dir
    }

    private val traceDirectory: File
        get() = File(repositoryRoot, "../../ISO15118ConformanceTests.Simulation/Vectors")

    private val corpus: JsonObject by lazy {
        val file = File(repositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json")
        require(file.isFile) { "the event corpus is missing at $file" }
        (JsonValue.parse(file.readText()) as JsonObject)["sessions"] as JsonObject
    }

    private fun traces(): List<JsonObject> =
        traceDirectory.listFiles { f -> f.name.startsWith("Session.") && f.name.endsWith(".trace.json") }!!
            .sortedBy { it.name }
            .map { JsonValue.parse(it.readText()) as JsonObject }


    @Test
    fun `this back end emits the events C# emits`() {

        var checked = 0

        for (trace in traces()) {

            val name = (trace["name"] as cloud.charging.v2g.exi.JsonString).value
            val expected = corpus[name] as? JsonArray ?: error("the corpus has no events for $name")

            val produced = SessionEventStream(SteppingClock()::read).replay(trace)

            assertEquals(expected.size, produced.size, "$name: event count")

            for ((i, event) in produced.withIndex()) {
                assertEquals(expected[i].toJsonString(), BridgeEvent.toJson(event).toJsonString(),
                             "$name/$i")
                checked++
            }
        }

        assertTrue(checked >= 150, "only $checked events were compared")
    }


    /**
     * Within one event, the JSON-LD and the raw EXI are the same message.
     *
     * Read from the **checked-in** corpus rather than from a freshly produced stream, so it is a
     * statement about what a consumer receives rather than about what this code happens to do in one
     * run.
     */
    @Test
    fun `the two halves of an event are the same message`() {

        var checked = 0

        for ((name, events) in corpus.keys.map { it to corpus[it] as JsonArray }) {
            for (node in events.asList()) {

                val event = node as JsonObject
                if ((event["kind"] as cloud.charging.v2g.exi.JsonString).value != "message") continue

                fun str(key: String) = (event[key] as cloud.charging.v2g.exi.JsonString).value
                val hex = str("exi")

                val fromTheFrame = MessageSetCodecs.toJson(
                    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
                    str("payloadType"), str("messageName"))

                assertEquals(event["json"]!!.toJsonString(), fromTheFrame.toJsonString(),
                             "$name/${event["seq"]}: the event's JSON-LD is not what its own frame decodes to")
                checked++
            }
        }

        assertTrue(checked >= 100, "only $checked messages were self-checked")
    }


    /**
     * The stream is a record, never a channel: no event asks the far side to do anything.
     *
     * Asserted on the kinds rather than trusted, because the cost of adding a command-shaped event
     * later is that nobody notices it was a different kind of thing.
     */
    @Test
    fun `every event kind is an observation rather than a command`() {

        val kinds = corpus.keys
            .flatMap { (corpus[it] as JsonArray).asList() }
            .map { ((it as JsonObject)["kind"] as cloud.charging.v2g.exi.JsonString).value }
            .toSortedSet()

        assertEquals(setOf("message", "sessionFinished", "sessionStarted"), kinds.toSet(),
                     "a new event kind appeared")
    }


    /** A frame that cannot be read becomes an error event carrying the frame. */
    @Test
    fun `an unreadable frame becomes an error that carries it`() {

        val trace = JsonValue.parse("""
            {
              "name": "broken", "protocol": "iso15118-2", "mode": "ac",
              "exchanges": [
                { "index": 0,
                  "request":  { "payloadType": "0x8001", "message": "SessionSetupReq", "frame": "01fe800100000002ffff" },
                  "response": null }
              ]
            }
        """.trimIndent()) as JsonObject

        val events = SessionEventStream(SteppingClock()::read).replay(trace)
        val error = events.filterIsInstance<BridgeEvent.Error>().single()

        assertEquals("01fe800100000002ffff", error.exi, "the frame has to travel with the error")
        assertTrue(error.detail.contains("SessionSetupReq"), error.detail)
        assertEquals("failed", (events.last() as BridgeEvent.SessionFinished).outcome)
    }
}
