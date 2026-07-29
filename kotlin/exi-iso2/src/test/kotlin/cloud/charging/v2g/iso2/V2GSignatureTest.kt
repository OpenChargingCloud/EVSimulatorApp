package cloud.charging.v2g.iso2

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ISO 15118-2 XMLDSig signing and verification (§7.9): SHA-256 over an element's EXI fragment feeds
 * a SignedInfo Reference; the SignedInfo fragment is ECDSA-P256 signed, with the SignatureValue as
 * raw `r‖s`.
 *
 * These exercise the crypto on top of the fragment codecs. The fragment octets themselves are
 * pinned to cbV2G in [FragmentTest] — that separation matters: a signature test can only tell you
 * the two sides of *this* implementation agree, so the interop claim rests on the fragment bytes
 * being right, not on anything here.
 */
class V2GSignatureTest {

    private fun p256(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    /** A signable AuthorizationReq (Id="ID1") and its EXI fragment. */
    private fun signedElementFragment(): ByteArray =
        Iso15118_2Codec.encodeFragment_AuthorizationReq(
            AuthorizationReqType(
                id = "ID1",
                genChallenge = ByteArray(16) { (it + 1).toByte() },
            )
        )

    private fun signedInfo(): SignedInfoType =
        V2GSignature.buildSignedInfo("ID1", V2GSignature.digest(signedElementFragment()))

    @Test
    fun `sign then verify round-trips`() {
        val key = p256()
        val si = signedInfo()

        val signatureValue = V2GSignature.sign(si, key.private)

        assertEquals(64, signatureValue.size, "P-256 r||s is 32+32 bytes, not DER")
        assertTrue(V2GSignature.verify(si, signatureValue, key.public))
    }

    @Test
    fun `verify fails for a tampered signature`() {
        val key = p256()
        val si = signedInfo()
        val signatureValue = V2GSignature.sign(si, key.private)

        signatureValue[0] = (signatureValue[0].toInt() xor 0xFF).toByte()   // flip a byte of r

        assertFalse(V2GSignature.verify(si, signatureValue, key.public))
    }

    @Test
    fun `verify fails for the wrong key`() {
        val signer = p256()
        val other = p256()
        val si = signedInfo()

        val signatureValue = V2GSignature.sign(si, signer.private)

        assertFalse(V2GSignature.verify(si, signatureValue, other.public))
    }

    @Test
    fun `verify fails when the signed content changed`() {
        val key = p256()
        val signatureValue = V2GSignature.sign(signedInfo(), key.private)

        // Same key, same reference id — but a different digest, so a different SignedInfo fragment.
        val tampered = V2GSignature.buildSignedInfo("ID1", ByteArray(32) { 0x7F })

        assertFalse(V2GSignature.verify(tampered, signatureValue, key.public))
    }

    @Test
    fun `a reference digest matches its signed element`() {
        val fragment = signedElementFragment()
        val reference = signedInfo().reference.single()

        assertTrue(V2GSignature.verifyReference(reference, fragment))

        val otherFragment = Iso15118_2Codec.encodeFragment_AuthorizationReq(
            AuthorizationReqType(id = "ID1", genChallenge = ByteArray(16) { 0x00 })
        )
        assertFalse(V2GSignature.verifyReference(reference, otherFragment),
            "a different signed element must not satisfy the reference")
    }

    @Test
    fun `the SignedInfo fragment is stable for the same content`() {
        assertContentEquals(
            V2GSignature.signedInfoFragment(signedInfo()),
            V2GSignature.signedInfoFragment(signedInfo()),
            "signing input must not depend on anything but the content",
        )
    }

    @Test
    fun `a signature survives encode, decode and re-encode of the message`() {
        val key = p256()
        val si = signedInfo()
        val signature = V2GSignature.buildSignature(si, V2GSignature.sign(si, key.private))

        val message = V2G_Message(
            header = MessageHeaderType(sessionID = ByteArray(8), notification = null, signature = signature),
            body = BodyType(
                AuthorizationReqType(id = "ID1", genChallenge = ByteArray(16) { (it + 1).toByte() })
            ),
        )

        val bytes = Iso15118_2Codec.encode(message)
        val decoded = Iso15118_2Codec.decodeAny(bytes) as V2G_Message

        assertContentEquals(bytes, Iso15118_2Codec.encode(decoded), "message round trip is not the identity")

        // The decoded signature must still verify, and still cover the decoded body.
        val decodedSignature = decoded.header.signature!!
        assertTrue(
            V2GSignature.verify(decodedSignature.signedInfo, decodedSignature.signatureValue.value, key.public),
            "signature did not survive the wire round trip",
        )
        assertTrue(
            V2GSignature.verifyReference(
                decodedSignature.signedInfo.reference.single(),
                Iso15118_2Codec.encodeFragment_AuthorizationReq(decoded.body.bodyElement as AuthorizationReqType),
            ),
            "reference digest does not cover the decoded body",
        )
    }
}
