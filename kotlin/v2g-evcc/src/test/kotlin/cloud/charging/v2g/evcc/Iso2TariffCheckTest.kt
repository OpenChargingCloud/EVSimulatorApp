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
import kotlin.test.assertTrue

import cloud.charging.v2g.iso2.ChargeParameterDiscoveryResType
import cloud.charging.v2g.iso2.Iso15118_2Codec
import cloud.charging.v2g.iso2.SAScheduleListType
import cloud.charging.v2g.iso2.V2G_Message

/**
 * §7.9.2.5, against the corpus C# generates and is itself held to.
 *
 * The corpus exists because the verdict never reaches the wire: the EV checks a signed offer and tells
 * the station nothing about the result, so no recorded session trace can pin it. Every case here is a
 * whole `ChargeParameterDiscoveryRes` frame, decoded exactly as a session would decode it — the only
 * thing this test does that a session does not is *look* at the answer.
 *
 * Three of the six cases cannot come from a recording at all. A station does not offer a tampered
 * digest, does not sign with a key the EV does not hold, and an EV in the field usually holds no tariff
 * key at all. Those are precisely the cases where a verifier that always answers "fine" still looks
 * perfectly healthy.
 */
class Iso2TariffCheckTest {

    private data class Case(
        val name: String,
        val frame: ByteArray,
        val verifyKey: PublicKey?,
        val signaturePresent: Boolean,
        val digestOk: Boolean,
        val signatureOk: Boolean,
        val signatureGrammar: String,
    )

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    private fun publicKey(key: JsonObject): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(BigInteger(key.get("x").asString, 16),
                        BigInteger(key.get("y").asString, 16)),
                p256))

    private fun corpus(): List<Case> {

        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")

        val file = File(dir, "vectors/Tariff.signature.vectors.json")
        require(file.isFile) { "tariff corpus not found at $file" }

        return JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonArray("cases").map { element ->

                val c = element.asJsonObject
                val expected = c.getAsJsonObject("expected")
                val key = c.get("verifyKey")

                Case(
                    name             = c.get("name").asString,
                    frame            = hex(c.get("frame").asString),
                    verifyKey        = if (key == null || key.isJsonNull) null
                                       else publicKey(key.asJsonObject),
                    signaturePresent = expected.get("signaturePresent").asBoolean,
                    digestOk         = expected.get("digestOk").asBoolean,
                    signatureOk      = expected.get("signatureOk").asBoolean,
                    signatureGrammar = expected.get("signatureGrammar").asString,
                )
            }
    }

    @Test
    fun `every corpus case reaches the verdict the C sharp side reached`() {

        for (c in corpus()) {

            val message = Iso15118_2Codec.decodeAny(c.frame) as V2G_Message
            val body = message.body.bodyElement as ChargeParameterDiscoveryResType

            val verdict = Iso2TariffCheck.evaluate(
                body.sASchedules as? SAScheduleListType, message.header.signature, c.verifyKey)

            assertEquals(c.signaturePresent, verdict.signaturePresent, "${c.name}: signaturePresent")
            assertEquals(c.digestOk,         verdict.digestOk,         "${c.name}: digestOk")
            assertEquals(c.signatureOk,      verdict.signatureOk,      "${c.name}: signatureOk")
            assertEquals(c.signatureGrammar, verdict.signatureGrammar, "${c.name}: signatureGrammar")
        }
    }

    /**
     * The corpus still carries the cases it was built for. A regeneration that quietly dropped the
     * negatives would leave this suite green over a verifier that only ever answers "fine".
     */
    @Test
    fun `the corpus still carries its negatives`() {
        val names = corpus().map { it.name }.toSet()
        for (required in listOf("signed-msgdef", "signed-standalone", "unsigned",
                                "digest-tampered", "wrong-key", "no-verify-key"))
            assertTrue(required in names, "the $required case is gone")
    }
}
