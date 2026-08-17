package cloud.charging.evsim

import cloud.charging.v2g.bridge.BridgeEvent
import cloud.charging.v2g.bridge.SessionConfigException
import cloud.charging.v2g.bridge.SessionEventStream
import cloud.charging.v2g.bridge.SteppingClock
import cloud.charging.v2g.exi.JsonArray
import cloud.charging.v2g.exi.JsonObject
import cloud.charging.v2g.exi.JsonString
import cloud.charging.v2g.exi.JsonValue
import com.getcapacitor.JSObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Android adapter's two translations, against the real `JSObject` rather than a stand-in.
 *
 * Everything else in this module is `Plugin` machinery that needs an `Activity` and a `WebView`.
 * These two are the module's own decisions, and both are about what a platform JSON library does to
 * a value on its way across the bridge.
 *
 * **A note on what this does and does not prove.** `com.getcapacitor.JSObject` extends
 * `org.json.JSONObject`, and on a device that class comes from Android's own fork. Here it is the
 * reference implementation from Maven, because the mockable `android.jar` supplies a stub that
 * throws. Both are hash-map backed and neither preserves member order, but the honest statement is
 * that this is the reference implementation's behaviour — and the conclusion drawn from it holds a
 * fortiori for any implementation that is not *more* order-preserving.
 */
class EvSimulatorPluginTest {

    private val repositoryRoot: File by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")
        dir
    }

    private fun corpus(name: String): JsonObject =
        JsonValue.parse(File(repositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors/$name")
                            .readText()) as JsonObject

    /**
     * Every recorded session's events — as ``BridgeEvent``s, not as JSON.
     *
     * Replayed rather than read out of `Bridge.events.json`, because the thing under test takes a
     * `BridgeEvent`. A test that assembled the payload out of corpus text would be checking its own
     * arithmetic: an earlier draft of this file did exactly that, and a mutation replacing the
     * payload with a marshalled `JSObject` — the design this module rejects — left it green.
     */
    private fun events(): List<BridgeEvent> =
        File(repositoryRoot, "vectors")
            .listFiles { f -> f.name.startsWith("Session.") && f.name.endsWith(".trace.json") }!!
            .sortedBy { it.name }
            .flatMap {
                SessionEventStream(SteppingClock()::read).replay(JsonValue.parse(it.readText()) as JsonObject)
            }

    /** The same events as the corpus's text, keyed the same way — the expected side. */
    private fun expectedText(): List<String> {
        val sessions = corpus("Bridge.events.json")["sessions"] as JsonObject
        return sessions.keys.flatMap { name ->
            (sessions[name] as JsonArray).asList().map { it.toJsonString() }
        }
    }


    /**
     * The payload carries the event's text across the bridge unaltered.
     *
     * "Across the bridge" is the whole round trip Capacitor performs: the payload is serialised,
     * handed to the WebView and parsed there. What arrives has to be the string every back end
     * agrees on, character for character.
     */
    @Test
    fun `the payload carries the event text through the bridge unaltered`() {

        val expected = expectedText()
        val produced = events()

        assertEquals(expected.size, produced.size, "event count")
        assertTrue(produced.size >= 190, "only ${produced.size} events were measured")

        for ((i, event) in produced.withIndex()) {

            val payload = BridgeCodec.payload(event)

            // What Capacitor does with the payload: serialise it, hand it over, parse it.
            val arrived = JSObject(payload.toString()).getString("event")

            assertEquals(expected[i], arrived, "event $i")
        }
    }


    /**
     * The measurement the payload's shape rests on: an event does not survive being a `JSObject`.
     *
     * If this ever stops failing — if some future `JSONObject` preserved insertion order — the text
     * payload would become ceremony rather than protection, and this test is where that would be
     * noticed. Until then it is the reason [BridgeCodec.payload] sends a string.
     */
    @Test
    fun `an event does not survive being marshalled as a JSObject`() {

        val all     = expectedText()
        var changed = 0

        for (text in all)
            if (JSObject(text).toString() != text) changed++

        assertTrue(all.size >= 190, "only ${all.size} events were measured")
        assertEquals(all.size, changed,
            "org.json preserved ${all.size - changed} of ${all.size} events — re-read BridgeCodec.payload")
    }


    /**
     * A configuration read out of a real `JSObject` is the one C# accepts, or the refusal C# gives.
     *
     * The corpus is checked by `v2g-bridge` against JSON parsed by this project's own reader. This
     * checks the same cases after a round trip through org.json — the actual path on a device — and
     * it is not the same question: a JSON library that widened `15118.5` to an integer, or quoted a
     * number, would turn a refusal into an accepted session and no existing test would see it.
     */
    @Test
    fun `a configuration survives the real JSObject on its way in`() {

        val accepted = (corpus("Bridge.config.json")["accepted"] as JsonArray).asList().map { it as JsonObject }
        val refused  = (corpus("Bridge.config.json")["refused"]  as JsonArray).asList().map { it as JsonObject }

        for (entry in accepted) {
            val input  = entry["input"] as JsonObject
            val config = BridgeCodec.configFrom(JSObject(input.toJsonString()))

            assertEquals((entry["canonical"] as JsonObject).toJsonString(), config.toJson().toJsonString(),
                         (entry["name"] as JsonString).value)
        }

        var checked = 0

        for (entry in refused) {

            // Only the object-shaped cases: JSObject cannot hold an array or a bare null, and a call
            // that carried one would be refused by Capacitor before it reached this module.
            val input = entry["input"] as? JsonObject ?: continue
            val name  = (entry["name"] as JsonString).value

            try {
                BridgeCodec.configFrom(JSObject(input.toJsonString()))
                fail("$name was accepted after a round trip through org.json")
            } catch (e: SessionConfigException) {
                assertEquals((entry["message"] as JsonString).value, e.message, name)
                checked++
            }
        }

        assertTrue(checked >= 18, "only $checked refusals survived the round trip")
    }


    /** A missing `config` key is a refusal in the same words as a config that is not an object. */
    @Test
    fun `a call with no configuration is refused rather than defaulted`() {

        val thrown = try {
            BridgeCodec.configFrom(null)
            fail("a missing configuration was accepted")
        } catch (e: SessionConfigException) {
            e
        }

        assertEquals("a session configuration is a JSON object.", thrown.message)
    }

}
