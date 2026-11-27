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
import cloud.charging.v2g.tp.V2GTP
import cloud.charging.v2g.tp.V2GTPDecodeResult
import cloud.charging.v2g.tp.V2GTPDispatcher

import cloud.charging.v2g.iso2.Iso15118_2Codec
import cloud.charging.v2g.iso2.MeteringReceiptReqType
import cloud.charging.v2g.iso2.SignatureValueType as I2SignatureValueType
import cloud.charging.v2g.iso2.V2G_Message

import cloud.charging.v2g.iso20.common.AuthorizationReq
import cloud.charging.v2g.iso20.common.CertificateInstallationReq
import cloud.charging.v2g.iso20.common.CommonMessagesCodec
import cloud.charging.v2g.iso20.common.MeteringConfirmationReq
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
        val payload = encode(substitute(message, signatureValue))
        // A side-stream frame keeps the payload type it came with: the dispatcher frames by message
        // set, and would put a CommonMessages document back on 0x8002.
        if (set == null) {
            val out = ByteArray(V2GTP.HEADER_SIZE + payload.size)
            V2GTP.writeHeader(out, MeteringConfirmation.PAYLOAD_TYPE, payload.size.toUInt())
            payload.copyInto(out, V2GTP.HEADER_SIZE)
            return out
        }
        return V2GTPDispatcher.encode(set, payload)
    }

    /**
     * Whether a signed `MeteringConfirmationReq`'s reference digest is the digest of its own request
     * fragment — the half of the check that verifying `SignedInfo` does not do. The C# side needs it
     * because a re-recording takes the recorded `SignedInfo` whole; a replay compares it as bytes, so
     * here it is belt and braces, and kept so the two harnesses check the same things.
     */
    fun digestHolds(message: MeteringConfirmationReq): Boolean {
        val signature = message.header.signature ?: return false
        val reference = signature.signedInfo.reference.singleOrNull() ?: return false
        if (reference.uRI != "#${message.signedMeteringData.id}") return false
        val suite = XmlDsigSuite.entries.firstOrNull { it.digestMethod == reference.digestMethod.algorithm }
            ?: return false
        return suite.digest(MeteringConfirmation.signedOctets(message.header, message.signedMeteringData))
                    .contentEquals(reference.digestValue)
    }

    /**
     * The -2 twin of the above: whether a signed `MeteringReceiptReq`'s reference digest is the digest
     * of its own body-element fragment. -2 names the element by its `Id` attribute, and with a signing
     * meter fitted that fragment carries the meter's `SigMeterReading`, the same two-signer shape.
     */
    fun digestHolds(message: V2G_Message): Boolean {
        val receipt   = message.body.bodyElement as? MeteringReceiptReqType ?: return false
        val signature = message.header.signature ?: return false
        val reference = signature.signedInfo.reference.singleOrNull() ?: return false
        if (reference.uRI != "#${receipt.id}") return false
        val suite = XmlDsigSuite.entries.firstOrNull { it.digestMethod == reference.digestMethod.algorithm }
            ?: return false
        return suite.digest(Iso15118_2Codec.encodeFragment_MeteringReceiptReq(receipt))
                    .contentEquals(reference.digestValue)
    }

    /** One frame's decoded message, for a test that reads what a recorded frame says. */
    fun messageOf(frame: ByteArray): Any = decode(frame).second

    /**
     * Verifies a frame's own signature against its own `SignedInfo`. The half the substitution throws
     * away — without it a port could emit any 64 bytes it liked and still compare equal.
     */
    fun verifiesWith(frame: ByteArray, publicKey: PublicKey): Boolean {
        val (_, message) = decode(frame)
        return when (message) {
            // A receipt has its digest checked as well, as on the C# side: it is the -2 message signed
            // over a meter signature, like MeteringConfirmationReq below.
            is V2G_Message -> message.header.signature?.let {
                (message.body.bodyElement !is MeteringReceiptReqType || digestHolds(message)) &&
                XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(it.signedInfo),
                                      it.signatureValue.value, publicKey, it.signedInfo.signatureMethod.algorithm)
            } ?: false

            is AuthorizationReq -> message.header.signature?.let {
                XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(it.signedInfo),
                                      it.signatureValue.value, publicKey, it.signedInfo.signatureMethod.algorithm)
            } ?: false

            // The other signed request a -20 EV sends, and the only one it sends *before*
            // authorizing. JCA names the hash rather than deriving it from the curve, so the P-521
            // OEM key needs no separate arm here — only its public key has to be built on the right
            // curve, which is what `publicKey`'s parameter is for.
            is CertificateInstallationReq -> message.header.signature?.let {
                XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(it.signedInfo),
                                      it.signatureValue.value, publicKey, it.signedInfo.signatureMethod.algorithm)
            } ?: false

            // The digest as well, as on the C# side — see digestHolds.
            is MeteringConfirmationReq -> message.header.signature?.let {
                digestHolds(message) &&
                XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(it.signedInfo),
                                      it.signatureValue.value, publicKey, it.signedInfo.signatureMethod.algorithm)
            } ?: false

            else -> false
        }
    }

    /**
     * A public key from the two field elements the trace records, on the curve it names.
     *
     * `P-256` is the default because it is what every trace recorded before contract provisioning
     * used, and what a contract key still uses; a -20 OEM provisioning key is `P-521`.
     */
    fun publicKey(xHex: String, yHex: String, curve: String = "P-256"): PublicKey {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(if (curve == "P-521") "secp521r1" else "secp256r1"))
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

        is CertificateInstallationReq -> {
            val signature = message.header.signature
                ?: error("substituting a signature into an unsigned CertificateInstallationReq")
            CertificateInstallationReq(
                message.header.copy(signature = signature.copy(
                    signatureValue = CSignatureValueType(null, signatureValue))),
                message.oEMProvisioningCertificateChain,
                message.listOfRootCertificateIDs,
                message.maximumContractCertificateChains,
                message.prioritizedEMAIDs)
        }

        is MeteringConfirmationReq -> {
            val signature = message.header.signature
                ?: error("substituting a signature into an unsigned MeteringConfirmationReq")
            MeteringConfirmationReq(
                message.header.copy(signature = signature.copy(
                    signatureValue = CSignatureValueType(null, signatureValue))),
                message.signedMeteringData)
        }

        else -> throw UnsupportedOperationException(
            "the trace corpus does not model a signature on ${message::class.simpleName}. " +
            "Add it here rather than letting the comparison skip it.")
    }

    private fun encode(message: Any): ByteArray = when (message) {
        is V2G_Message                -> Iso15118_2Codec.encode(message)
        is AuthorizationReq           -> CommonMessagesCodec.encode(message)
        is CertificateInstallationReq -> CommonMessagesCodec.encode(message)
        is MeteringConfirmationReq    -> CommonMessagesCodec.encode(message)
        else -> throw UnsupportedOperationException("cannot re-encode a ${message::class.simpleName}.")
    }

    /** One frame, decoded. The message set is null for the metering-confirmation side stream
     *  (0x8102, [V2G20-1084]), which the dispatcher does not route: it is read here as the
     *  CommonMessages document it carries, the way the car reads its answer. */
    private fun decode(frame: ByteArray): Pair<MessageSet?, Any> {
        if (V2GTP.tryReadHeader(frame)?.payloadType == MeteringConfirmation.PAYLOAD_TYPE)
            return null to CommonMessagesCodec.decodeAny(frame.copyOfRange(V2GTP.HEADER_SIZE, frame.size))
        return when (val result = V2GTPDispatcher.decode(frame)) {
            is V2GTPDecodeResult.Decoded -> result.set to result.message
            is V2GTPDecodeResult.Failed  -> throw TraceMismatch("trace: ${result.error}")
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
