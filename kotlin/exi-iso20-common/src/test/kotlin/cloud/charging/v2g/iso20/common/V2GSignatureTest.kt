package cloud.charging.v2g.iso20.common

import org.bouncycastle.crypto.params.Ed448PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ISO 15118-20 XMLDSig signing and verification, for both algorithms of the -20 signature suite:
 * ECDSA over P-521 and Ed448.
 *
 * As on the -2 side, these exercise the crypto on top of the fragment codecs; the fragment octets
 * are what carries the interop claim, and they are pinned separately. The tests in *this* file can
 * only show that the implementation agrees with itself — P-521 signatures are randomised, so there
 * is nothing to compare against but a verification of what we just produced.
 *
 * Ed448 is no longer in that position: it is deterministic, and [Ed448RfcVectorTest] holds it to
 * RFC 8032 §7.4's published signatures byte for byte. That is the stronger claim, and it is the one
 * that would catch a signer using a non-empty context or the prehashed variant — neither of which
 * anything here can see.
 */
class V2GSignatureTest {

    private fun p521(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp521r1"))
        }.generateKeyPair()

    private fun ed448(): Ed448PrivateKeyParameters =
        Ed448PrivateKeyParameters(SecureRandom())

    /** A signable OEMProvisioningCertificateChain (Id="ID1") and its EXI fragment. */
    private fun signedElementFragment(cert: Byte = 0x11): ByteArray =
        CommonMessagesCodec.encodeFragment_OEMProvisioningCertificateChain(
            SignedCertificateChainType(
                id = "ID1",
                certificate = ByteArray(8) { cert },
                subCertificates = null,
            )
        )

    private fun signedInfo(algorithm: String = V2GSignature.ECDSA_SHA512): SignedInfoType =
        V2GSignature.buildSignedInfo("ID1", V2GSignature.digest(signedElementFragment()), algorithm)

    // ---- ECDSA P-521 ----------------------------------------------------------------------

    @Test
    fun `ECDSA sign then verify round-trips`() {
        val key = p521()
        val si = signedInfo()

        val signatureValue = V2GSignature.sign(si, key.private)

        assertEquals(132, signatureValue.size, "P-521 r||s is 66+66 bytes, not DER")
        assertTrue(V2GSignature.verify(si, signatureValue, key.public))
    }

    @Test
    fun `ECDSA verify fails for a tampered signature`() {
        val key = p521()
        val si = signedInfo()
        val signatureValue = V2GSignature.sign(si, key.private)

        signatureValue[0] = (signatureValue[0].toInt() xor 0xFF).toByte()

        assertFalse(V2GSignature.verify(si, signatureValue, key.public))
    }

    @Test
    fun `ECDSA verify fails for the wrong key`() {
        val si = signedInfo()
        val signatureValue = V2GSignature.sign(si, p521().private)

        assertFalse(V2GSignature.verify(si, signatureValue, p521().public))
    }

    // ---- Ed448 ----------------------------------------------------------------------------

    @Test
    fun `Ed448 sign then verify round-trips`() {
        val priv = ed448()
        val si = signedInfo(V2GSignature.EDDSA_ED448)

        val signatureValue = V2GSignature.signEd448(si, priv)

        assertEquals(114, signatureValue.size, "Ed448 signatures are 114 bytes")
        assertTrue(V2GSignature.verifyEd448(si, signatureValue, priv.generatePublicKey()))
    }

    @Test
    fun `Ed448 verify fails for a tampered signature`() {
        val priv = ed448()
        val si = signedInfo(V2GSignature.EDDSA_ED448)
        val signatureValue = V2GSignature.signEd448(si, priv)

        signatureValue[0] = (signatureValue[0].toInt() xor 0xFF).toByte()

        assertFalse(V2GSignature.verifyEd448(si, signatureValue, priv.generatePublicKey()))
    }

    @Test
    fun `Ed448 verify fails for the wrong key`() {
        val si = signedInfo(V2GSignature.EDDSA_ED448)
        val signatureValue = V2GSignature.signEd448(si, ed448())

        assertFalse(V2GSignature.verifyEd448(si, signatureValue, ed448().generatePublicKey()))
    }

    @Test
    fun `Ed448 verify fails when the signed content changed`() {
        val priv = ed448()
        val signatureValue = V2GSignature.signEd448(signedInfo(V2GSignature.EDDSA_ED448), priv)

        val tampered = V2GSignature.buildSignedInfo(
            "ID1", ByteArray(64) { 0x7F }, V2GSignature.EDDSA_ED448,
        )

        assertFalse(V2GSignature.verifyEd448(tampered, signatureValue, priv.generatePublicKey()))
    }

    /**
     * Ed448 is deterministic (RFC 8032), so the same key over the same message must give the same
     * signature. This is what makes a wrong context string detectable at all — with a randomised
     * scheme the two would differ for innocent reasons.
     */
    @Test
    fun `Ed448 is deterministic for the same key and content`() {
        val priv = ed448()
        val si = signedInfo(V2GSignature.EDDSA_ED448)

        assertContentEquals(
            V2GSignature.signEd448(si, priv),
            V2GSignature.signEd448(si, priv),
        )
    }

    /**
     * Pins the Ed448 **context string** to the empty one RFC 8032 specifies for plain `Ed448`.
     *
     * Everything else here would happily pass with a wrong context, because sign and verify share
     * it — the same blind spot a decode∘encode round trip has. So this crosses over to a second
     * implementation of the same algorithm: BouncyCastle's JCA `Ed448` provider entry, which is the
     * spec-named algorithm and fixes the context to empty. A signature made there must verify with
     * [V2GSignature.verifyEd448] and the other way round; give the low-level signer a non-empty
     * context and both directions fail.
     */
    @Test
    fun `Ed448 uses the empty context string`() {
        val provider = BouncyCastleProvider()
        val priv = ed448()
        val si = signedInfo(V2GSignature.EDDSA_ED448)
        val message = V2GSignature.signedInfoFragment(si)

        val keyFactory = KeyFactory.getInstance("Ed448", provider)
        val jcaPrivate = keyFactory.generatePrivate(
            PKCS8EncodedKeySpec(PrivateKeyInfoFactory.createPrivateKeyInfo(priv).encoded)
        )
        val jcaPublic = keyFactory.generatePublic(
            X509EncodedKeySpec(
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(priv.generatePublicKey()).encoded
            )
        )

        val viaJca = Signature.getInstance("Ed448", provider).run {
            initSign(jcaPrivate); update(message); sign()
        }
        assertTrue(
            V2GSignature.verifyEd448(si, viaJca, priv.generatePublicKey()),
            "a JCA Ed448 signature must verify here — if it does not, the context string differs",
        )

        val viaUs = V2GSignature.signEd448(si, priv)
        assertTrue(
            Signature.getInstance("Ed448", provider).run {
                initVerify(jcaPublic); update(message); verify(viaUs)
            },
            "our Ed448 signature must verify through the JCA — if it does not, the context string differs",
        )
    }

    // ---- shared ---------------------------------------------------------------------------

    @Test
    fun `a reference digest matches its signed element`() {
        val reference = signedInfo().reference.single()

        assertTrue(V2GSignature.verifyReference(reference, signedElementFragment()))
        assertFalse(
            V2GSignature.verifyReference(reference, signedElementFragment(cert = 0x22)),
            "a different signed element must not satisfy the reference",
        )
    }

    @Test
    fun `the digest is SHA-512`() {
        assertEquals(64, V2GSignature.digest(signedElementFragment()).size,
            "-20 uses SHA-512, not -2's SHA-256")
    }

    @Test
    fun `the signature method URI is carried into SignedInfo`() {
        assertEquals(V2GSignature.EDDSA_ED448,
            signedInfo(V2GSignature.EDDSA_ED448).signatureMethod.algorithm,
            "a peer picks its verification algorithm from this URI")
    }

    @Test
    fun `an Ed448 signature survives encode, decode and re-encode of the message`() {
        val priv = ed448()
        val si = signedInfo(V2GSignature.EDDSA_ED448)
        val signature = V2GSignature.buildSignature(si, V2GSignature.signEd448(si, priv))

        val message = AuthorizationSetupReq(
            header = MessageHeaderType(sessionID = ByteArray(8), timeStamp = 1_700_000_000uL, signature = signature)
        )

        val bytes = CommonMessagesCodec.encode(message)
        val decoded = CommonMessagesCodec.decodeAny(bytes) as AuthorizationSetupReq

        assertContentEquals(bytes, CommonMessagesCodec.encode(decoded), "message round trip is not the identity")

        val decodedSignature = decoded.header.signature!!
        assertTrue(
            V2GSignature.verifyEd448(
                decodedSignature.signedInfo,
                decodedSignature.signatureValue.value,
                priv.generatePublicKey(),
            ),
            "signature did not survive the wire round trip",
        )
    }
}
