package cloud.charging.v2g.evcc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECPrivateKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

import cloud.charging.v2g.iso2.CertificateInstallationResType
import cloud.charging.v2g.iso2.Iso15118_2Codec
import cloud.charging.v2g.iso2.V2G_Message
import cloud.charging.v2g.iso20.common.CertificateInstallationRes
import cloud.charging.v2g.iso20.common.CommonMessagesCodec

/**
 * Contract provisioning on both protocols, against the corpus C# generates and is itself held to.
 *
 * ## What this test is really checking
 *
 * Two things, and the second is the one no other test in this module can reach.
 *
 * The first is the familiar verdict: does this port judge a provisioning response the way C# does — four
 * references on -2, one on -20, digests, signature, grammar. The verdict never travels, so no recorded
 * session can pin it.
 *
 * The second is the **unwrapped scalar**. What the car ends up holding after provisioning is a private
 * key it never saw transmitted: the station ran an ECDH, a KDF and a cipher, and the car has to arrive at
 * the same 32 (or 66) bytes independently. Nothing is echoed, acknowledged, or checked by the peer.
 * `recoveredKeyD` in the corpus is the only place in this repository where that property can be stated at
 * all. A KDF that put the counter on the wrong side of Z passes every other test here.
 */
class ContractProvisioningTest {

    private class Case(val name: String, val frame: ByteArray, val receiverKeyD: ByteArray, val expected: JsonObject)

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun hexString(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** The fixed-width scalar of a key — what a port must produce, padded as the curve defines it. */
    private fun scalarOf(key: PrivateKey, width: Int): String {
        val d = (key as ECPrivateKey).s.toByteArray().let { raw ->
            // BigInteger.toByteArray() is signed and variable-width; the wire scalar is neither.
            when {
                raw.size == width -> raw
                raw.size > width  -> raw.copyOfRange(raw.size - width, raw.size)
                else              -> ByteArray(width - raw.size) + raw
            }
        }
        return hexString(d)
    }

    private fun corpus(set: String): List<Case> {

        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")

        val file = File(dir, "vectors/Contract.provisioning.vectors.json")
        require(file.isFile) { "contract-provisioning corpus not found at $file" }

        return JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonArray(set).map { element ->
                val c = element.asJsonObject
                Case(name         = c.get("name").asString,
                     frame        = hex(c.get("frame").asString),
                     receiverKeyD = hex(c.get("receiverKeyD").asString),
                     expected     = c.getAsJsonObject("expected"))
            }
    }

    private fun p256(d: ByteArray): PrivateKey = KeyFactory.getInstance("EC")
        .generatePrivate(ECPrivateKeySpec(BigInteger(1, d), ContractProvisioning2.curve))

    private fun p521(d: ByteArray): PrivateKey = KeyFactory.getInstance("EC")
        .generatePrivate(ECPrivateKeySpec(BigInteger(1, d), ContractProvisioning20.curve))


    // ── ISO 15118-2 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every -2 case reaches the verdict the C sharp side reached`() {

        for (c in corpus("iso2")) {

            val message = Iso15118_2Codec.decodeAny(c.frame) as V2G_Message
            val verdict = Iso2ContractCheck.evaluate(message.body.bodyElement, message.header.signature)

            assertEquals(c.expected.get("signaturePresent").asBoolean, verdict.signaturePresent, "${c.name}: signaturePresent")
            assertEquals(c.expected.get("references").asInt,           verdict.references,       "${c.name}: references")
            assertEquals(c.expected.get("digestOk").asBoolean,         verdict.digestOk,         "${c.name}: digestOk")
            assertEquals(c.expected.get("signatureOk").asBoolean,      verdict.signatureOk,      "${c.name}: signatureOk")
            assertEquals(c.expected.get("signatureGrammar").asString,  verdict.signatureGrammar, "${c.name}: signatureGrammar")

            val payload = assertNotNull(Iso2ContractCheck.unpack(message.body.bodyElement), c.name)
            assertEquals(c.expected.get("emaid").asString, payload.emaid.value, "${c.name}: emaid")
        }
    }

    /**
     * The load-bearing one. Every -2 case names the scalar C# unwrapped from these exact bytes with this
     * exact key, and this port must produce it — including for `install-wrong-receiver`, where the right
     * answer is 32 bytes of nonsense rather than an error.
     */
    @Test
    fun `the unwrapped key is the one the C sharp side unwrapped`() {

        for (c in corpus("iso2")) {

            val message = Iso15118_2Codec.decodeAny(c.frame) as V2G_Message
            val payload = assertNotNull(Iso2ContractCheck.unpack(message.body.bodyElement), c.name)

            val recovered = ContractProvisioning2.recoverContractKey(
                p256(c.receiverKeyD), payload.dhPublicKey.value, payload.encryptedKey.value)

            assertEquals(c.expected.get("recoveredKeyD").asString, scalarOf(recovered, 32),
                         "${c.name}: the unwrapped scalar")

            val issuedKey = assertNotNull(publicKeyOf(payload.contractChain.certificate), c.name)
            assertEquals(c.expected.get("keyMatchesCertificate").asBoolean,
                         ContractProvisioning2.matches(recovered, issuedKey),
                         "${c.name}: keyMatchesCertificate")
        }
    }

    /**
     * CBC authenticates nothing, so the wrong key does not fail — it hands over a usable private key
     * belonging to nobody. The only thing between that and an installed contract is the certificate
     * check, and this states it on its own rather than as one assertion among many.
     */
    @Test
    fun `a wrong receiver unwraps successfully and is caught by the certificate`() {

        val c = corpus("iso2").first { it.name == "install-wrong-receiver" }
        val message = Iso15118_2Codec.decodeAny(c.frame) as V2G_Message
        val payload = assertNotNull(Iso2ContractCheck.unpack(message.body.bodyElement))
        val verdict = Iso2ContractCheck.evaluate(message.body.bodyElement, message.header.signature)

        // Everything the signature can say about this response is "fine".
        assertTrue(verdict.digestOk)
        assertTrue(verdict.signatureOk)

        val recovered = ContractProvisioning2.recoverContractKey(
            p256(c.receiverKeyD), payload.dhPublicKey.value, payload.encryptedKey.value)

        assertFalse(ContractProvisioning2.matches(
                        recovered, assertNotNull(publicKeyOf(payload.contractChain.certificate))),
                    "an unwrap with the wrong key must not pass for the real one")
    }

    /** The update message decodes and judges identically — a port that knows only one of the two stops here. */
    @Test
    fun `a renewal is judged the same way an installation is`() {

        val c = corpus("iso2").first { it.name == "update-signed" }
        val message = Iso15118_2Codec.decodeAny(c.frame) as V2G_Message

        assertFalse(message.body.bodyElement is CertificateInstallationResType,
                    "this case must be a CertificateUpdateRes")

        val verdict = Iso2ContractCheck.evaluate(message.body.bodyElement, message.header.signature)
        assertEquals(4, verdict.references)
        assertTrue(verdict.digestOk)
        assertTrue(verdict.signatureOk)
    }

    @Test
    fun `a -2 field of the wrong width is refused before anything is decrypted`() {

        val receiver = p256(hex("5b8e10c47a2d93f605c8b1e42d97a306f5b8c1e42d97a3f605c8b1e42d97a306"))

        assertFailsWith<ContractProvisioningException> {
            ContractProvisioning2.recoverContractKey(receiver, ByteArray(64), ByteArray(48))
        }

        val point = ByteArray(65).also { it[0] = 0x04 }
        assertFailsWith<ContractProvisioningException> {
            ContractProvisioning2.recoverContractKey(receiver, point, ByteArray(47))
        }
    }


    // ── ISO 15118-20 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every -20 case reaches the verdict the C sharp side reached`() {

        for (c in corpus("iso20")) {

            val res = CommonMessagesCodec.decodeAny(c.frame) as CertificateInstallationRes
            val verdict = Iso20ContractCheck.evaluate(res, res.header.signature)

            assertEquals(c.expected.get("signaturePresent").asBoolean, verdict.signaturePresent, "${c.name}: signaturePresent")
            assertEquals(c.expected.get("references").asInt,           verdict.references,       "${c.name}: references")
            assertEquals(c.expected.get("digestOk").asBoolean,         verdict.digestOk,         "${c.name}: digestOk")
            assertEquals(c.expected.get("signatureOk").asBoolean,      verdict.signatureOk,      "${c.name}: signatureOk")
        }
    }

    /**
     * The -20 half of the load-bearing check — and the one place the two protocols differ in outcome
     * rather than in bytes: a wrong receiver here is refused by GCM's tag, where -2's CBC handed over
     * nonsense.
     */
    @Test
    fun `the unwrapped -20 key is the one the C sharp side unwrapped`() {

        for (c in corpus("iso20")) {

            val res = CommonMessagesCodec.decodeAny(c.frame) as CertificateInstallationRes
            val data = res.signedInstallationData

            val recovered = try {
                ContractProvisioning20.recoverContractKey(
                    p521(c.receiverKeyD), data.dHPublicKey, assertNotNull(data.sECP521_EncryptedPrivateKey))
            } catch (_: java.security.GeneralSecurityException) {
                null
            }

            assertEquals(c.expected.get("keyRecovered").asBoolean, recovered != null, "${c.name}: keyRecovered")

            if (recovered != null)
                assertEquals(c.expected.get("recoveredKeyD").asString, scalarOf(recovered, 66),
                             "${c.name}: the unwrapped scalar")
        }
    }

    @Test
    fun `a wrong -20 receiver is refused by the tag rather than yielding nonsense`() {

        val c = corpus("iso20").first { it.name == "install-wrong-receiver" }
        val res = CommonMessagesCodec.decodeAny(c.frame) as CertificateInstallationRes

        assertFailsWith<javax.crypto.AEADBadTagException> {
            ContractProvisioning20.recoverContractKey(
                p521(c.receiverKeyD), res.signedInstallationData.dHPublicKey,
                assertNotNull(res.signedInstallationData.sECP521_EncryptedPrivateKey))
        }
    }

    @Test
    fun `a -20 field of the wrong width is refused before anything is decrypted`() {

        val oemKey = p521(hex("013b7e5c418fa2c6d095b74e128fa03d76c5b192e8437ca605d29b4718fe30c6a95d472b" +
                              "8103fae62d59b0748125fac396d0b7e5c41d4f2a3c95b8e70612fa4c8d31"))

        assertFailsWith<ContractProvisioningException> {
            ContractProvisioning20.recoverContractKey(oemKey, ByteArray(132), ByteArray(94))
        }

        val point = ByteArray(133).also { it[0] = 0x04 }
        assertFailsWith<ContractProvisioningException> {
            ContractProvisioning20.recoverContractKey(oemKey, point, ByteArray(93))
        }
    }


    @Test
    fun `the corpus still carries its negatives`() {

        val iso2 = corpus("iso2").map { it.name }.toSet()
        for (required in listOf("install-signed", "install-standalone", "install-unsigned",
                                "install-digest-tampered", "install-three-references",
                                "install-wrong-key", "install-wrong-receiver", "update-signed"))
            assertTrue(required in iso2, "the -2 $required case is gone")

        val iso20 = corpus("iso20").map { it.name }.toSet()
        for (required in listOf("install-signed", "install-unsigned", "install-digest-tampered",
                                "install-wrong-uri", "install-wrong-key", "install-wrong-receiver"))
            assertTrue(required in iso20, "the -20 $required case is gone")
    }
}
