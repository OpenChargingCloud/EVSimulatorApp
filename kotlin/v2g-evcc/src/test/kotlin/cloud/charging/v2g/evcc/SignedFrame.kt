package cloud.charging.v2g.evcc

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

import cloud.charging.v2g.tp.MessageSet
import cloud.charging.v2g.tp.V2GTPDecodeResult
import cloud.charging.v2g.tp.V2GTPDispatcher

import cloud.charging.v2g.iso2.Iso15118_2Codec
import cloud.charging.v2g.iso2.SignatureValueType as I2SignatureValueType
import cloud.charging.v2g.iso2.V2G_Message

import cloud.charging.v2g.iso20.common.AuthorizationReq
import cloud.charging.v2g.iso20.common.CommonMessagesCodec
import cloud.charging.v2g.iso20.common.SignatureValueType as CSignatureValueType

/**
 * The Kotlin half of the signature-aware comparison, mirroring `SignedFrame.cs`.
 *
 * A signed frame cannot be compared byte for byte: ECDSA's nonce is random, so the same message
 * signed twice differs. But **only the signature value is random** — the body is deterministic, and
 * so is `SignedInfo`, which holds a digest of the signed element. So the recorded signature is
 * substituted into the frame a port produced, the result is re-encoded and compared exactly as
 * before, and the produced signature is verified separately against the corpus key. Everything but
 * those 64 bytes is still checked exactly, including `SignedInfo`, therefore the digest, therefore
 * which octets were signed.
 *
 * See the C# original for the full argument. This is the same mechanism in the same order, and the
 * two must agree — a port that passed here and failed there would mean the mechanism itself differs
 * between back ends, which would be worse than either failing.
 */
internal object SignedFrame {

    /** Re-encodes [frame] with [signatureValue] in place of whatever signature it carried. */
    fun withSignatureValue(frame: ByteArray, signatureValue: ByteArray): ByteArray {
        val (set, message) = decode(frame)
        return V2GTPDispatcher.encode(set, encode(substitute(message, signatureValue)))
    }

    /**
     * Verifies a frame's own signature against its own `SignedInfo`. The half the substitution throws
     * away — without it a port could emit any 64 bytes it liked and still compare equal.
     */
    fun verifiesWith(frame: ByteArray, publicKey: PublicKey): Boolean {
        val (_, message) = decode(frame)
        return when (message) {
            is V2G_Message -> message.header.signature?.let {
                XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(it.signedInfo),
                                      it.signatureValue.value, publicKey)
            } ?: false

            is AuthorizationReq -> message.header.signature?.let {
                XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(it.signedInfo),
                                      it.signatureValue.value, publicKey)
            } ?: false

            else -> false
        }
    }

    /** A P-256 public key from the two field elements the trace records. */
    fun publicKey(xHex: String, yHex: String): PublicKey {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)

        val point = ECPoint(BigInteger(1, hex(xHex)), BigInteger(1, hex(yHex)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
    }

    // Deliberately a closed `when` that throws on anything unlisted rather than a reflective walk.
    // A trace whose signed message this does not model must fail loudly; the alternative is a corpus
    // that silently skips the comparison for exactly the messages it was built to compare.
    private fun substitute(message: Any, signatureValue: ByteArray): Any = when (message) {

        is V2G_Message -> {
            val signature = message.header.signature
                ?: error("substituting a signature into an unsigned V2G_Message")
            message.copy(header = message.header.copy(
                signature = signature.copy(
                    signatureValue = I2SignatureValueType(null, signatureValue))))
        }

        // Not a data class, so no copy(): the generated -20 messages are plain classes. Rebuilt
        // field by field, which also means adding a field to AuthorizationReq breaks this loudly.
        is AuthorizationReq -> {
            val signature = message.header.signature
                ?: error("substituting a signature into an unsigned AuthorizationReq")
            AuthorizationReq(
                message.header.copy(signature = signature.copy(
                    signatureValue = CSignatureValueType(null, signatureValue))),
                message.selectedAuthorizationService,
                message.eIM_AReqAuthorizationMode,
                message.pnC_AReqAuthorizationMode)
        }

        else -> throw UnsupportedOperationException(
            "the trace corpus does not model a signature on ${message::class.simpleName}. " +
            "Add it here rather than letting the comparison skip it.")
    }

    private fun encode(message: Any): ByteArray = when (message) {
        is V2G_Message      -> Iso15118_2Codec.encode(message)
        is AuthorizationReq -> CommonMessagesCodec.encode(message)
        else -> throw UnsupportedOperationException("cannot re-encode a ${message::class.simpleName}.")
    }

    private fun decode(frame: ByteArray): Pair<MessageSet, Any> =
        when (val result = V2GTPDispatcher.decode(frame)) {
            is V2GTPDecodeResult.Decoded -> result.set to result.message
            is V2GTPDecodeResult.Failed  -> throw TraceMismatch("trace: ${result.error}")
        }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
