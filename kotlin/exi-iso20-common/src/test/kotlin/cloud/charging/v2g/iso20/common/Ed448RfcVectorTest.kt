package cloud.charging.v2g.iso20.common

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bouncycastle.crypto.params.Ed448PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed448PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed448Signer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The published Ed448 vectors from RFC 8032 §7.4, against the signer ISO 15118-20's second
 * signature suite runs on.
 *
 * [V2GSignatureTest] says of itself that "a signature test can only show that this implementation
 * agrees with itself". These are the answer to that: deterministic signatures with published
 * expected values, so signing is an equality check rather than another round trip.
 *
 * Worth doing here as well as on the C# side even though both call BouncyCastle, because they do
 * not call the *same* BouncyCastle — this is `bcprov-jdk18on`, the Java original; C# uses the .NET
 * port. Checking each against the standard is worth more than checking them against each other.
 *
 * The corpus is read from the C# test project rather than copied, exactly as the codec vectors are,
 * so the two back ends cannot drift onto different numbers.
 */
class Ed448RfcVectorTest {

    private val vectors: List<JsonObject> by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory) {
            dir = dir.parentFile ?: error("repository root not found from ${File(".").absolutePath}")
        }
        val f = File(dir, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Tests/Vectors/Ed448.rfc8032.vectors.json")
        assertTrue(f.isFile, "RFC 8032 vector corpus not found at $f")
        JsonParser.parseString(f.readText()).asJsonObject
            .getAsJsonArray("vectors").map { it.asJsonObject }
    }

    private fun JsonObject.hex(name: String): ByteArray {
        val s = get(name).asString
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `the corpus is the whole of section 7 4`() {
        assertEquals(9, vectors.size, "RFC 8032 §7.4 has nine Ed448 vectors")
        assertEquals(1, vectors.count { it.get("context").asString.isNotEmpty() },
                     "exactly one §7.4 vector carries a context")
        assertEquals(1023, vectors.maxOf { it.hex("message").size },
                     "the 1023-octet vector is missing — the one that crosses SHAKE256 block " +
                     "boundaries and would catch a broken streaming update")
    }

    @Test
    fun `every public key derives from its secret key`() {
        for (v in vectors) {
            assertContentEquals(
                v.hex("publicKey"),
                Ed448PrivateKeyParameters(v.hex("secretKey")).generatePublicKey().encoded,
                v.get("label").asString)
        }
    }

    /**
     * Ed448 is deterministic — RFC 8032 has no per-signature randomness — so this compares against
     * the RFC's own bytes instead of verifying what we just produced.
     */
    @Test
    fun `signing reproduces every RFC signature`() {
        for (v in vectors) {
            val message = v.hex("message")
            val signature = Ed448Signer(v.hex("context")).apply {
                init(true, Ed448PrivateKeyParameters(v.hex("secretKey")))
                update(message, 0, message.size)
            }.generateSignature()

            assertEquals(v.get("signature").asString, toHex(signature), v.get("label").asString)
        }
    }

    @Test
    fun `verifying accepts every RFC signature`() {
        for (v in vectors) {
            val message = v.hex("message")
            val ok = Ed448Signer(v.hex("context")).apply {
                init(false, Ed448PublicKeyParameters(v.hex("publicKey")))
                update(message, 0, message.size)
            }.verifySignature(v.hex("signature"))

            assertTrue(ok, v.get("label").asString)
        }
    }

    /**
     * The context is load-bearing, and the RFC proves it for us: §7.4's "1 octet" and
     * "1 octet (with context)" share a key and a message and differ only in the context — `""`
     * against `"foo"`. So an empty context is a *choice* [V2GSignature.signEd448] makes, not a
     * property of Ed448, and an API that hides the parameter makes it silently.
     */
    @Test
    fun `the context changes the signature entirely`() {
        val without = vectors.single { it.get("label").asString == "1 octet" }
        val withFoo = vectors.single { it.get("label").asString == "1 octet (with context)" }

        assertEquals(without.get("secretKey").asString, withFoo.get("secretKey").asString, "same key")
        assertEquals(without.get("message").asString, withFoo.get("message").asString, "same message")
        assertTrue(without.get("context").asString.isEmpty())
        assertContentEquals("foo".toByteArray(), withFoo.hex("context"))
        assertNotEquals(without.get("signature").asString, withFoo.get("signature").asString)

        val message = withFoo.hex("message")
        val accepted = Ed448Signer(ByteArray(0)).apply {
            init(false, Ed448PublicKeyParameters(withFoo.hex("publicKey")))
            update(message, 0, message.size)
        }.verifySignature(withFoo.hex("signature"))

        assertFalse(accepted, "a signature made under a context verified without one")
    }

    /**
     * Connects the vector-checked primitive to our own two lines on top of it: pure Ed448, empty
     * context, over the SignedInfo fragment octets with no external pre-hash. If [signEd448] ever
     * grows a pre-hash, a context, or a different notion of which octets are signed, this fails
     * while every self-referential test in [V2GSignatureTest] keeps passing.
     */
    @Test
    fun `signEd448 is pure Ed448 with an empty context over the fragment`() {
        val key = Ed448PrivateKeyParameters(vectors.first().hex("secretKey"))
        val signedInfo = V2GSignature.buildSignedInfo(
            "ID1", ByteArray(64) { 0xAB.toByte() }, V2GSignature.EDDSA_ED448)

        val ours = V2GSignature.signEd448(signedInfo, key)

        val fragment = CommonMessagesCodec.encodeFragment_SignedInfo(signedInfo)
        val expected = Ed448Signer(ByteArray(0)).apply {
            init(true, key)
            update(fragment, 0, fragment.size)
        }.generateSignature()

        assertContentEquals(expected, ours)
        assertEquals(114, ours.size)
    }

    /**
     * RFC 9231 §2.3.12 lists `#eddsa-ed448ph` as a separate identifier from `#eddsa-ed448`, and only
     * the latter is implemented. The URI travels inside the message, so a peer states its variant
     * rather than leaving us to infer it from a signature length.
     */
    @Test
    fun `the Ed448 signature method URI is the pure variant`() {
        assertEquals("http://www.w3.org/2021/04/xmldsig-more#eddsa-ed448", V2GSignature.EDDSA_ED448)
        assertFalse(V2GSignature.EDDSA_ED448.contains("ed448ph"),
                    "the prehashed variant is a different algorithm and is not implemented")
    }
}
