package cloud.charging.v2g.pairing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The TOTP port against the C#-generated corpus.
 *
 * This is the half where a silent divergence is worst. A pairing payload that drifts fails visibly —
 * a field is missing, a warning is absent, something on screen looks wrong. A TOTP that drifts fails
 * as **"pairing does not work"**, with no way to see why from either end: every code is rejected, and
 * both sides are certain they are right. A hash either agrees exactly or it agrees not at all.
 */
class PairingTotpTest {

    private val corpus: JsonObject by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")

        val file = File(dir, "pairing/EVSimulatorApp.Pairing.Tests/Vectors/Pairing.totp.vectors.json")
        require(file.isFile) { "TOTP corpus not found at $file" }
        JsonParser.parseString(file.readText()).asJsonObject
    }

    private val secret: String get() = corpus.get("secret").asString


    @Test
    fun `every slot vector matches this port`() {

        assertEquals(PairingTotpGenerator.DEFAULT_ALPHABET, corpus.get("alphabet").asString,
                     "the alphabet is part of the algorithm, not a preference")

        val slots = corpus.getAsJsonArray("slots")
        assertTrue(slots.size() >= 9, "the corpus looks truncated")

        for (vector in slots.map { it.asJsonObject }) {

            val name = vector.get("name").asString
            val produced = PairingTotpGenerator.slots(
                secret,
                Instant.ofEpochSecond(vector.get("at").asLong),
                vector.get("validitySeconds").asLong,
                vector.get("length").asInt)

            assertEquals(vector.get("previous").asString, produced.previous, "$name: previous slot")
            assertEquals(vector.get("current").asString,  produced.current,  "$name: current slot")
            assertEquals(vector.get("next").asString,     produced.next,     "$name: next slot")
            assertEquals(vector.get("remainingSeconds").asLong, produced.remainingSeconds,
                         "$name: remaining seconds")
        }
    }

    /**
     * The verifier script, replayed. Stateful — accepting a code changes what happens next — so it
     * runs in order and shares one verifier. Replay is only visible as a *sequence*, which is why this
     * is a script rather than a set of cases.
     */
    @Test
    fun `the verifier script still holds`() {

        var now = Instant.ofEpochSecond(1_700_000_025)
        val verifier = PairingTotpVerifier(secret, 30) { now }

        for (step in corpus.getAsJsonArray("verifier").map { it.asJsonObject }) {

            now = Instant.ofEpochSecond(step.get("atUnixSeconds").asLong)

            val what = step.get("what").asString
            val result = verifier.verify(step.get("presented").asString)

            assertEquals(step.get("expected").asString, result.corpusName, what)
            assertEquals(step.get("spentAfter").asInt, verifier.spentCount, "$what (spent count)")
        }
    }

    /**
     * The script is only worth running if it still contains a replay and the window edges. A corpus
     * that lost them would go on passing forever.
     */
    @Test
    fun `the script still covers replay and the window edges`() {

        val outcomes = corpus.getAsJsonArray("verifier").map { it.asJsonObject.get("expected").asString }

        assertTrue("Replayed" in outcomes,
                   "a script without a replay proves nothing about the one-shot rule")
        assertTrue("Unknown" in outcomes)
        assertTrue("Malformed" in outcomes)
        assertEquals(3, outcomes.count { it == "Accepted" },
                     "previous, current and next — the ±1 window, no wider")
    }

    /**
     * Generating a code must not need the network or a system clock, and must not depend on the
     * device's timezone. The last is the one worth asserting: a slot number derived from local time
     * would work perfectly on the developer's machine and fail for every user east or west of it.
     */
    @Test
    fun `the slot number is derived from UTC alone`() {

        val default = java.util.TimeZone.getDefault()
        try {
            val at = Instant.ofEpochSecond(1_700_000_025)

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"))
            val far = PairingTotpGenerator.current(secret, at)

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val utc = PairingTotpGenerator.current(secret, at)

            assertEquals(utc, far, "the code changed with the device's timezone")
        } finally {
            java.util.TimeZone.setDefault(default)
        }
    }
}
