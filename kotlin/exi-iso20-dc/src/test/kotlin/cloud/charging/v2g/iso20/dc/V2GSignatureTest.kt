package cloud.charging.v2g.iso20.dc

import org.bouncycastle.crypto.params.Ed448PrivateKeyParameters
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ISO 15118-20 DC signing, over this message set's own XMLDSig types.
 *
 * The suite is CommonMessages' — SHA-512, ECDSA P-521 or Ed448 — and the crypto itself is pinned
 * there, including the two parameters a round trip cannot see (the P1363 signature format and
 * Ed448's empty context string). What these add is that the same suite works against *this* set's
 * duplicated `SignedInfoType` and its own fragment grammar, which is a different encoding and so a
 * different set of signed octets.
 */
class V2GSignatureTest {

    private fun p521(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp521r1"))
        }.generateKeyPair()

    private fun ed448(): Ed448PrivateKeyParameters = Ed448PrivateKeyParameters(SecureRandom())

    private fun rational(value: Short) = RationalNumberType(exponent = 0, value = value)

    private fun header() =
        MessageHeaderType(sessionID = ByteArray(8), timeStamp = 1_700_000_000uL, signature = null)

    /** The element this set signs, and its EXI fragment. */
    private fun signedElement(power: Short = 22000) = DC_ChargeParameterDiscoveryResType(
        header = header(),
        responseCode = ResponseCode.OK,
        dC_CPDResEnergyTransferMode = DC_CPDResEnergyTransferModeType(
            eVSEMaximumChargePower = rational(power),
            eVSEMinimumChargePower = rational(100),
            eVSEMaximumChargeCurrent = rational(200),
            eVSEMinimumChargeCurrent = rational(2),
            eVSEMaximumVoltage = rational(900),
            eVSEMinimumVoltage = rational(200),
            eVSEPowerRampLimitation = null,
        ),
    )

    private fun signedElementFragment(power: Short = 22000): ByteArray =
        DCCodec.encodeFragment_DC_ChargeParameterDiscoveryRes(signedElement(power))

    private fun signedInfo(algorithm: String = V2GSignature.ECDSA_SHA512, power: Short = 22000) =
        V2GSignature.buildSignedInfo("ID1", V2GSignature.digest(signedElementFragment(power)), algorithm)

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

    @Test
    fun `Ed448 sign then verify round-trips`() {
        val priv = ed448()
        val si = signedInfo(V2GSignature.EDDSA_ED448)

        val signatureValue = V2GSignature.signEd448(si, priv)

        assertEquals(114, signatureValue.size, "Ed448 signatures are 114 bytes")
        assertTrue(V2GSignature.verifyEd448(si, signatureValue, priv.generatePublicKey()))
    }

    @Test
    fun `Ed448 verify fails for the wrong key`() {
        val si = signedInfo(V2GSignature.EDDSA_ED448)
        val signatureValue = V2GSignature.signEd448(si, ed448())

        assertFalse(V2GSignature.verifyEd448(si, signatureValue, ed448().generatePublicKey()))
    }

    @Test
    fun `a reference digest matches its signed element`() {
        val reference = signedInfo().reference.single()

        assertTrue(V2GSignature.verifyReference(reference, signedElementFragment()))
        assertFalse(
            V2GSignature.verifyReference(reference, signedElementFragment(power = 11000)),
            "a different signed element must not satisfy the reference",
        )
    }

    /**
     * The reason this set needs its own helper rather than borrowing CommonMessages'. Each -20
     * message set carries its own copy of the XMLDSig schema, so the same logical SignedInfo encodes
     * to different octets under different sets' grammars — and signing the wrong octets produces a
     * signature that verifies locally and nowhere else.
     */
    @Test
    fun `the SignedInfo fragment is this message set's own encoding`() {
        val fragment = V2GSignature.signedInfoFragment(signedInfo())

        assertTrue(fragment.isNotEmpty())
        assertContentEquals(
            fragment,
            DCCodec.encodeFragment_SignedInfo(DCCodec.decodeFragment_SignedInfo(fragment)),
            "SignedInfo does not survive its own fragment round trip",
        )
    }

    @Test
    fun `an Ed448 signature survives encode, decode and re-encode of the message`() {
        val priv = ed448()
        val si = signedInfo(V2GSignature.EDDSA_ED448)
        val signature = V2GSignature.buildSignature(si, V2GSignature.signEd448(si, priv))

        val message = DC_ChargeParameterDiscoveryRes(
            header = MessageHeaderType(ByteArray(8), 1_700_000_000uL, signature),
            responseCode = ResponseCode.OK,
            dC_CPDResEnergyTransferMode = signedElement().dC_CPDResEnergyTransferMode,
        )

        val bytes = DCCodec.encode(message)
        val decoded = DCCodec.decodeAny(bytes) as DC_ChargeParameterDiscoveryRes

        assertContentEquals(bytes, DCCodec.encode(decoded), "message round trip is not the identity")

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
