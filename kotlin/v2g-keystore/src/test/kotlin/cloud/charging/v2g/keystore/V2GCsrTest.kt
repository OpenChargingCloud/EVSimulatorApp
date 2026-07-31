package cloud.charging.v2g.keystore

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CSR generation, and the two things about it worth checking.
 *
 * A CSR proves possession of a private key, so it is signed by that key — which makes it the first
 * real customer of [V2GSigner]. The signer is asked for a signature and never for the key, which is
 * what would let a secure-element key produce one.
 */
class V2GCsrTest {

    private fun signer(): V2GSigner {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return InMemoryP256Signer(pair.private, pair.public.encoded)
    }

    /**
     * **The check that matters.** A CSR whose signature does not verify is not malformed in any way
     * a parser notices — it simply gets rejected by whichever CA receives it, which is a miserable
     * place to discover a conversion bug. So the request is verified here with the public key it
     * carries, exactly as a CA would.
     */
    @Test
    fun theRequestVerifiesUnderItsOwnPublicKey() {

        val csr = V2GCsr.parse(V2GCsr.build("CN=DE8AA1A2B3C4D5", signer()))
        val verifier = JcaContentVerifierProviderBuilder().build(csr.subjectPublicKeyInfo)

        assertTrue(csr.isSignatureValid(verifier),
                   "a CSR whose signature does not verify would be refused by the CA and by nothing " +
                   "before it")
    }

    @Test
    fun theSubjectAndPublicKeySurvive() {

        val signer = signer()
        val csr = V2GCsr.parse(V2GCsr.build("CN=DE8AA1A2B3C4D5", signer))

        assertEquals("CN=DE8AA1A2B3C4D5", csr.subject.toString())
        assertTrue(csr.subjectPublicKeyInfo.encoded.contentEquals(signer.publicKeyDer),
                   "the CSR must carry the very key the signer holds, or possession proves nothing")
    }

    /**
     * `r‖s` → `SEQUENCE { INTEGER r, INTEGER s }`, including the case that catches naive conversions:
     * a half whose high bit is set needs a leading zero, because DER integers are signed.
     */
    @Test
    fun theRawSignatureIsConvertedToDerIncludingTheHighBitCase() {

        // r has its high bit set, s does not.
        val raw = ByteArray(64).also {
            it[0] = 0xFF.toByte()
            it[32] = 0x01
        }

        val der = V2GCsr.derFromRawRs(raw)

        assertEquals(0x30, der[0].toInt() and 0xFF, "a DER SEQUENCE")
        // r: INTEGER, 33 bytes (32 + the leading zero the high bit forces).
        assertEquals(0x02, der[2].toInt() and 0xFF)
        assertEquals(33, der[3].toInt() and 0xFF,
                     "a half with the high bit set must gain a leading zero, or it reads as negative")
    }

    /** A raw signature handed straight to PKCS#10 would be the natural mistake. It is not what this
     *  produces — the encoded lengths differ, and only one of them verifies. */
    @Test
    fun theDerFormIsNotTheRawForm() {
        val raw = ByteArray(64) { 0x7F }
        assertTrue(V2GCsr.derFromRawRs(raw).size != raw.size)
    }

    /** Only P-256 so far, and it says so rather than producing a request with the wrong algorithm
     *  identifier and an r‖s of the wrong width. */
    @Test
    fun anUnsupportedCurveIsRefusedRatherThanMisEncoded() {

        val p521Signer = object : V2GSigner {
            override val curve = V2GKeyCurve.P521
            override val protection = V2GKeyProtection.SoftwareInMemory
            override val publicKeyDer = ByteArray(0)
            override fun signature(octets: ByteArray) = ByteArray(132)
        }

        val refused = assertFailsWith<IllegalArgumentException> {
            V2GCsr.build("CN=DE8AA1A2B3C4D5", p521Signer)
        }
        assertTrue(refused.message!!.contains("P521"))
    }
}
