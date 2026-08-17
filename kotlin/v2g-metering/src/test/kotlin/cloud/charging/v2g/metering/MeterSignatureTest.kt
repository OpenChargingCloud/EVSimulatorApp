package cloud.charging.v2g.metering

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.AlgorithmParameters
import java.security.spec.ECParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Kotlin verifier against the **C# side's** corpus.
 *
 * Held to bytes another implementation produced, not to its own output. Three ports of one layout,
 * each checked against itself, would agree perfectly and could be wrong together — the mirrored bug
 * this project has been bitten by before. The corpus is read out of the C# test project rather than
 * copied, exactly as the codec vectors are, so the two cannot drift.
 *
 * It is *not* conformance evidence: ISO 15118 defines the field and not its content, so no reference
 * encoder exists anywhere. What this proves is that the app and the station agree.
 */
class MeterSignatureTest {

    private data class Vector(
        val meterId: String,
        val protocol: Int,
        val sessionId: ByteArray,
        val reading: ULong,
        val timestamp: Long?,
        val payload: ByteArray,
        val signature: ByteArray,
    )

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private val corpus: JsonObject by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile) {
            dir = dir.parentFile ?: error("repository root not found")
        }
        val f = File(dir, "vectors/Meter.signing.vectors.json")
        assertTrue(f.isFile, "meter corpus not found at $f")
        JsonParser.parseString(f.readText()).asJsonObject
    }

    private val vectors: List<Vector> by lazy {
        corpus.getAsJsonArray("vectors").map { it.asJsonObject }.map {
            Vector(
                meterId   = it.get("meterId").asString,
                protocol  = it.get("protocol").asInt,
                sessionId = hex(it.get("sessionId").asString),
                reading   = it.get("reading").asString.toULong(),
                timestamp = if (it.get("timestamp").isJsonNull) null else it.get("timestamp").asString.toLong(),
                payload   = hex(it.get("payload").asString),
                signature = hex(it.get("signature").asString),
            )
        }
    }

    private val publicKey: PublicKey by lazy {
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)

        KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(BigInteger(corpus.get("publicKeyX").asString, 16),
                        BigInteger(corpus.get("publicKeyY").asString, 16)),
                params))
    }

    @Test
    fun `the corpus loaded`() {
        assertTrue(vectors.size >= 8)
        assertTrue(vectors.any { it.protocol == 20 }, "no -20 vector")
        assertTrue(vectors.any { it.timestamp == null }, "no absent-timestamp vector")
    }

    /**
     * The payload comparison, which is where a divergence is diagnosable. A failing signature says
     * only "no"; this says *which byte*, and it is what would catch a UTF-8 or endianness
     * disagreement between the three languages.
     */
    @Test
    fun `every payload is rebuilt byte for byte`() {
        for (v in vectors) {
            assertContentEquals(
                v.payload,
                MeterSignature.payload(v.protocol, v.sessionId, v.meterId, v.reading, v.timestamp),
                "meterId ${v.meterId}, protocol ${v.protocol}")
        }
    }

    @Test
    fun `every signature verifies`() {
        for (v in vectors) {
            assertTrue(
                MeterSignature.verify(v.signature, v.protocol, v.sessionId, v.meterId,
                                      v.reading, v.timestamp, publicKey),
                "meterId ${v.meterId}, protocol ${v.protocol}")
        }
    }

    /** A shaved reading must not verify — the whole reason the field is signed. */
    @Test
    fun `a tampered reading does not verify`() {
        val v = vectors.first { it.reading > 100uL }

        assertFalse(MeterSignature.verify(v.signature, v.protocol, v.sessionId, v.meterId,
                                          v.reading - 100uL, v.timestamp, publicKey))
    }

    /** The session binding, using the corpus's own pair: one byte of session id apart. */
    @Test
    fun `a reading from another session does not verify`() {
        val a = vectors.first { it.sessionId.contentEquals(hex("0102030405060708")) && it.protocol == 2 }
        val b = vectors.first { it.sessionId.contentEquals(hex("0102030405060709")) }

        assertFalse(MeterSignature.verify(a.signature, a.protocol, b.sessionId, a.meterId,
                                          a.reading, a.timestamp, publicKey))
    }

    /** A -2 reading is not a -20 reading; the corpus carries the same values under both. */
    @Test
    fun `a reading does not cross between protocols`() {
        val two = vectors.first { it.protocol == 2 && it.meterId == "VAN*M1" }

        assertFalse(MeterSignature.verify(two.signature, 20, two.sessionId, two.meterId,
                                          two.reading, two.timestamp, publicKey))
    }

    /** The length-prefix collision pair, taken from the corpus rather than constructed here. */
    @Test
    fun `two readings that would collide without length prefixes do not`() {
        assertNotEquals(
            vectors.first { it.meterId == "A1" }.payload.toList(),
            vectors.first { it.meterId == "A" }.payload.toList())
    }

    @Test
    fun `an unsupported protocol is refused`() {
        val e = kotlin.runCatching {
            MeterSignature.payload(3, ByteArray(8), "M", 0uL, null)
        }.exceptionOrNull()

        assertTrue(e is IllegalArgumentException, "expected a refusal, got $e")
    }

    /** A DER signature is refused on length rather than reaching the crypto. */
    @Test
    fun `a DER shaped signature is refused`() {
        val v = vectors.first()

        assertFalse(MeterSignature.verify(ByteArray(70), v.protocol, v.sessionId, v.meterId,
                                          v.reading, v.timestamp, publicKey))
    }
}
