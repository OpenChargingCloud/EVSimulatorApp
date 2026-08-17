package cloud.charging.v2g.bridge

import cloud.charging.v2g.exi.JsonArray
import cloud.charging.v2g.exi.JsonObject
import cloud.charging.v2g.exi.JsonString
import cloud.charging.v2g.exi.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * This back end's reading of a session configuration, against C#'s.
 *
 * The event stream is a record: a page can only watch it. This is the opposite direction — a value a
 * WebView hands to native code, which then opens a socket because of it. So the interesting half is
 * the refusals, and they are pinned **by message**: a port that says no for a different reason is a
 * different product, and the difference only ever shows up in front of a user who cannot act on it.
 */
class SessionConfigTest {

    private val repositoryRoot: File by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")
        dir
    }

    private val corpus: JsonObject by lazy {
        val file = File(repositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.config.json")
        require(file.isFile) { "the configuration corpus is missing at $file" }
        JsonValue.parse(file.readText()) as JsonObject
    }

    private fun section(name: String) =
        (corpus[name] as JsonArray).asList().map { it as JsonObject }

    private fun caseName(entry: JsonObject) = (entry["name"] as JsonString).value


    @Test
    fun `every configuration C# accepts is accepted here, canonically`() {

        val cases = section("accepted")
        assertTrue(cases.size >= 8, "only ${cases.size} accepted cases")

        for (entry in cases) {
            val config = SessionConfig.parse(entry["input"])

            assertEquals((entry["canonical"] as JsonObject).toJsonString(),
                         config.toJson().toJsonString(),
                         caseName(entry))
        }
    }


    @Test
    fun `every configuration C# refuses is refused here, in the same words`() {

        val cases = section("refused")
        assertTrue(cases.size >= 20, "only ${cases.size} refused cases")

        for (entry in cases) {

            val expected = (entry["message"] as JsonString).value

            val thrown = try {
                SessionConfig.parse(entry["input"])
                fail("${caseName(entry)} was accepted; C# refuses it with: $expected")
            } catch (e: SessionConfigException) {
                e
            }

            assertEquals(expected, thrown.message, caseName(entry))
        }
    }


    /** Parse, write, parse again — the same value. What makes the canonical form usable as a record. */
    @Test
    fun `every accepted configuration survives its own JSON`() {

        for (entry in section("accepted")) {
            val once  = SessionConfig.parse(entry["input"])
            val twice = SessionConfig.parse(once.toJson())

            assertEquals(once, twice, caseName(entry))
        }
    }


    /**
     * A runner that cannot start refuses the command; it does not open a stream that immediately dies.
     *
     * The distinction is what a caller acts on: an exception means no session exists and the WebView's
     * `start()` rejects, while an error event means a session is running and something in it failed.
     */
    @Test
    fun `a trace runner with no recording refuses rather than emitting a broken stream`() {

        val runner = TraceSessionRunner({ null }, SteppingClock()::read)
        val config = SessionConfig.parse(section("accepted").first()["input"])

        val emitted = mutableListOf<BridgeEvent>()
        val thrown  = assertFailsWith<IllegalStateException> { runner.run(config) { emitted.add(it) } }

        assertTrue(thrown.message!!.contains(config.protocol), "the refusal names what was asked for")
        assertTrue(emitted.isEmpty(), "a refused command emitted ${emitted.size} event(s)")
    }


    /** And one that has a recording produces exactly the stream the event corpus pins. */
    @Test
    fun `a trace runner replays the recorded session`() {

        val traceFile = File(repositoryRoot,
            "vectors")
            .listFiles { f -> f.name.startsWith("Session.") && f.name.endsWith(".trace.json") }!!
            .sortedBy { it.name }
            .first()

        val trace  = JsonValue.parse(traceFile.readText()) as JsonObject
        val name   = (trace["name"] as JsonString).value
        val runner = TraceSessionRunner({ trace }, SteppingClock()::read)
        val config = SessionConfig.parse(section("accepted").first()["input"])

        val emitted = mutableListOf<BridgeEvent>()
        runner.run(config) { emitted.add(it) }

        val expected = File(repositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json")
            .let { (JsonValue.parse(it.readText()) as JsonObject)["sessions"] as JsonObject }
            .let { it[name] as JsonArray }

        assertEquals(expected.size, emitted.size, "$name: event count")

        for ((i, event) in emitted.withIndex())
            assertEquals(expected[i].toJsonString(), BridgeEvent.toJson(event).toJsonString(), "$name/$i")
    }

}
