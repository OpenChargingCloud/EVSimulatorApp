package cloud.charging.v2g.evcc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

import cloud.charging.v2g.iso20.common.CommonMessagesCodec
import cloud.charging.v2g.iso20.common.ScheduleExchangeRes

/**
 * The -20 price-schedule check, against the corpus C# generates and is itself held to.
 *
 * Same reason as its -2 sibling: the verdict never reaches the wire, so no recorded session can pin
 * it. Every case here is a whole `ScheduleExchangeRes` frame, decoded exactly as a session would
 * decode it — the only thing this test does that a session does not is *look* at the answer.
 *
 * Two of the seven exist because a corpus can hold what a recording cannot express. `signed-dynamic`
 * is the same schedule as `signed-scheduled` in the other control mode, and a verifier that searches
 * only the schedule tuples fails it while looking perfectly healthy on every other case.
 * `no-price-schedule` expects *no verdict at all* rather than a failing one — most stations never
 * sign, and a screen that cannot tell "nothing to check" from "checked and unhappy" accuses them.
 */
class Iso20PriceScheduleCheckTest {

    private data class Case(
        val name: String,
        val frame: ByteArray,
        val verifyKey: PublicKey?,
        val expected: Iso20TariffResult?,
    )

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private val p521: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp521r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    private fun publicKey(key: JsonObject): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(BigInteger(key.get("x").asString, 16),
                        BigInteger(key.get("y").asString, 16)),
                p521))

    private fun corpus(): List<Case> {

        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")

        val file = File(dir, "vectors/PriceSchedule.signature.vectors.json")
        require(file.isFile) { "price-schedule corpus not found at $file" }

        return JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonArray("cases").map { element ->

                val c = element.asJsonObject
                val key = c.get("verifyKey")
                val expected = c.get("expected")

                Case(
                    name      = c.get("name").asString,
                    frame     = hex(c.get("frame").asString),
                    verifyKey = if (key == null || key.isJsonNull) null else publicKey(key.asJsonObject),
                    expected  = if (expected == null || expected.isJsonNull) null
                                else expected.asJsonObject.let {
                                    Iso20TariffResult(
                                        signaturePresent = it.get("signaturePresent").asBoolean,
                                        digestOk         = it.get("digestOk").asBoolean,
                                        signatureOk      = it.get("signatureOk").asBoolean)
                                },
                )
            }
    }

    @Test
    fun `every corpus case reaches the verdict the C sharp side reached`() {

        for (c in corpus()) {
            val res = CommonMessagesCodec.decodeAny(c.frame) as ScheduleExchangeRes
            val verdict = Iso20PriceScheduleCheck.evaluate(res, res.header.signature, c.verifyKey)
            assertEquals(c.expected, verdict, c.name)
        }
    }

    /** The offer with nothing to verify produces no verdict — not a failing one. */
    @Test
    fun `an offer without a price schedule produces no verdict`() {
        val c = corpus().first { it.name == "no-price-schedule" }
        val res = CommonMessagesCodec.decodeAny(c.frame) as ScheduleExchangeRes
        assertNull(Iso20PriceScheduleCheck.scheduleIn(res))
        assertNull(Iso20PriceScheduleCheck.evaluate(res, res.header.signature, null))
    }

    /**
     * Dynamic mode hides the schedule somewhere else entirely, and a verifier that only knows the
     * Scheduled tuples reports this signed offer as unsigned.
     */
    @Test
    fun `the dynamic control mode schedule is found too`() {

        val c = corpus().first { it.name == "signed-dynamic" }
        val res = CommonMessagesCodec.decodeAny(c.frame) as ScheduleExchangeRes

        assertNull(res.scheduled_SEResControlMode, "this case must have no schedule tuples at all")
        assertNotNull(Iso20PriceScheduleCheck.scheduleIn(res))

        val verdict = Iso20PriceScheduleCheck.evaluate(res, res.header.signature, c.verifyKey)!!
        assertTrue(verdict.digestOk)
        assertTrue(verdict.signatureOk)
    }

    @Test
    fun `the corpus still carries its negatives`() {
        val names = corpus().map { it.name }.toSet()
        for (required in listOf("signed-scheduled", "signed-dynamic", "unsigned", "digest-tampered",
                                "wrong-key", "no-verify-key", "no-price-schedule"))
            assertTrue(required in names, "the $required case is gone")
    }
}
