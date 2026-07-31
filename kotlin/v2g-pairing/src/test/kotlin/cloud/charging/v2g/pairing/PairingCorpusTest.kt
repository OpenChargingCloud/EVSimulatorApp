package cloud.charging.v2g.pairing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * This port against the corpus the C# side generates.
 *
 * The corpus is the *only* thing tying the two together: the Pi renders the code and the phone reads
 * it, in different languages, in different processes, on different machines, and neither can observe
 * the other. Two readings of a written format drift silently — and a pairing format that drifts does
 * not break loudly, it accepts something it should have refused.
 *
 * The refusal cases are the substance here. That a well-formed code parses is table stakes; that a
 * code with its parameters in the *query* is rejected outright, in every back end, is the property
 * worth pinning.
 */
class PairingCorpusTest {

    private val cases: List<JsonObject> by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory)
            dir = dir.parentFile ?: error("repository root not found")

        val file = File(dir, "pairing/EVSimulatorApp.Pairing.Tests/Vectors/Pairing.payload.vectors.json")
        require(file.isFile) { "pairing corpus not found at $file" }

        JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonArray("cases").map { it.asJsonObject }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString


    @Test
    fun `every case matches this port`() {

        assertTrue(cases.size >= 20, "the corpus looks truncated: ${cases.size} cases")

        for (case in cases) {
            val name  = case.str("name")!!
            val input = case.str("input")!!

            val payload = try {
                val parsed = PairingUri.parse(input)
                assertEquals(case.str("outcome"), if (parsed == null) "notAPairingCode" else "parsed",
                             "$name: outcome")
                parsed ?: continue
            } catch (e: PairingFormatException) {
                assertEquals("malformed", case.str("outcome"), "$name: outcome — ${e.message}")
                // The message too, not just the fact of refusal: it is what the user is shown, and a
                // refusal nobody can act on is only marginally better than none.
                assertEquals(case.str("error"), e.message, "$name: error message")
                continue
            }

            val expected = case.getAsJsonObject("payload")
            assertEquals(expected.get("version").asInt,   payload.version,   "$name: version")
            assertEquals(expected.get("host").asString,   payload.host,      "$name: host")
            assertEquals(expected.get("port").asInt,      payload.port,      "$name: port")
            assertEquals(expected.str("transport"),       payload.transport.name.lowercase(), "$name: transport")
            assertEquals(expected.getAsJsonArray("protocols").map { it.asString }, payload.protocols, "$name: protocols")
            assertEquals(expected.str("crypto"),          payload.crypto,    "$name: crypto")
            assertEquals(expected.get("nonConformant").asBoolean, payload.nonConformant, "$name: nonConformant")
            assertEquals(expected.str("nonConformanceReason"), payload.nonConformanceReason, "$name: ncwhy")
            assertEquals(expected.str("rootFingerprint"), payload.rootFingerprint, "$name: root")
            assertEquals(expected.str("meter"),           payload.meter,     "$name: meter")
            assertEquals(expected.str("totp"),            payload.totp,      "$name: totp")
            assertEquals(expected.str("evseId"),          payload.evseId,    "$name: evseId")
            assertEquals(expected.str("tariffId"),        payload.tariffId,  "$name: tariffId")
            assertEquals(expected.str("currency"),        payload.currency,  "$name: currency")
            assertEquals(expected.str("uiLanguage"),      payload.uiLanguage,"$name: uiLanguage")
            assertEquals(expected.str("wifiSsid"),        payload.wifiSsid,  "$name: wifi ssid")
            assertEquals(expected.str("wifiPsk"),         payload.wifiPsk,   "$name: wifi psk")
            assertEquals(expected.getAsJsonObject("extra").entrySet().associate { it.key to it.value.asString },
                         payload.extra, "$name: extra")

            assertEquals(case.getAsJsonArray("warnings").map { it.asJsonObject.str("kind") },
                         payload.warnings.map { it.kind.corpusName },
                         "$name: warnings (order included — it is the order a sheet lists them in)")

            assertEquals(case.getAsJsonArray("warnings").map { it.asJsonObject.get("blocking").asBoolean },
                         payload.warnings.map { it.isBlocking },
                         "$name: which warnings block")
        }
    }

    /**
     * The corpus is only worth running if it still contains the cases it was built for. A corpus that
     * quietly lost its refusals would go on passing forever — which is the failure mode of every
     * generated-fixture scheme, and cheap to rule out.
     */
    /**
     * The watchdog is itself a check that would pass if it were not installed, so it is made to fire
     * once on purpose. Without this, a `META-INF/services` file that stopped being packaged would
     * quietly turn the test above into an assertion about nothing.
     */
    @Test
    fun `the no-resolution watchdog is actually installed`() {

        NoResolutionResolverProvider.attempts.clear()
        runCatching { java.net.InetAddress.getByName("watchdog-selftest.invalid") }

        assertEquals(listOf("watchdog-selftest.invalid"),
                     NoResolutionResolverProvider.attempts.toList(),
                     "the resolver SPI is not in effect — every no-resolution assertion here is vacuous")
    }


    @Test
    fun `the corpus still covers the refusals`() {

        val byName = cases.associateBy { it.str("name") }

        for (required in listOf("query-instead-of-fragment", "repeated-parameter",
                                "hostname-is-not-resolved", "public-target")) {
            assertNotNull(byName[required], "the corpus no longer covers '$required'")
        }

        assertEquals("malformed", byName["query-instead-of-fragment"]!!.str("outcome"))
        assertEquals("malformed", byName["repeated-parameter"]!!.str("outcome"))
    }

    /**
     * Judging a host must not send a packet — asserted, not assumed.
     *
     * [NoResolutionResolverProvider] is installed for this whole test JVM and records every lookup,
     * so this test exercises the hosts most likely to tempt an implementation into resolving and then
     * asks what it did. Names are the ones that matter — an address literal never reaches a resolver
     * on any implementation, so a port that called `getByName` would pass a test written with
     * literals alone and still phone home for `localhost`.
     */
    @Test
    fun `judging a host resolves nothing`() {

        NoResolutionResolverProvider.attempts.clear()

        for (host in listOf("localhost", "charger.example.com", "pi.local", "not a host at all",
                            "192.168.4.1", "fe80::1%wlan0", "::1")) {
            runCatching { PairingWarnings.isPrivateTarget(host) }
        }

        // Asked afterwards rather than relying on the watchdog's throw: an implementation that
        // resolves inside its own try/catch swallows the throw and looks innocent. The record does not
        // care whether anyone caught anything.
        assertEquals(emptyList(), NoResolutionResolverProvider.attempts.toList(),
                     "the pairing parser resolved these hosts")

        // Only `.local` among the names, and never on the strength of what it might resolve to.
        assertTrue(PairingWarnings.isPrivateTarget("pi.local"))
        assertFalse(PairingWarnings.isPrivateTarget("localhost"),
                    "'localhost' is a name, and a name is judged as text: whether it happens to map " +
                    "to 127.0.0.1 on this device is not knowable without asking.")

        // The literals, both directions.
        for (private in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.4.1", "169.254.1.1",
                               "::1", "fe80::1%wlan0", "fd00::1")) {
            assertTrue(PairingWarnings.isPrivateTarget(private), private)
        }
        for (public in listOf("8.8.8.8", "172.32.0.1", "1.1.1.1", "2001:db8::1")) {
            assertFalse(PairingWarnings.isPrivateTarget(public), public)
        }
    }
}
