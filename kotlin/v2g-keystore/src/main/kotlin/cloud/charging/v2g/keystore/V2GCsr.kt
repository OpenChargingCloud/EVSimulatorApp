package cloud.charging.v2g.keystore

import java.io.OutputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder
import java.math.BigInteger

/**
 * Builds a PKCS#10 certificate signing request for a key this app holds.
 *
 * ## Why this exists in this module rather than next to the certificates
 *
 * A CSR proves possession of a private key, so it must be **signed by that key** — and if the key
 * lives in a secure element, its bytes never leave. That makes CSR generation the first real
 * customer of [V2GSigner]: the request is assembled, the octets are handed out, a signature comes
 * back. Had `PncEvccOptions` and this both taken a raw key, hardware backing would have been
 * impossible in two places instead of one.
 *
 * ## The conversion that is easy to get wrong
 *
 * [V2GSigner] returns **raw `r‖s`**, because that is what ISO 15118 puts on the wire and the field
 * is sized for it. PKCS#10 wants the **DER** form — `SEQUENCE { INTEGER r, INTEGER s }` — like every
 * other X.509 structure. So the signature has to be converted here, and a CSR carrying a raw
 * signature is not malformed in any way a parser would notice quickly: it simply fails to verify at
 * whichever CA receives it, which is a miserable place to find out.
 */
object V2GCsr {

    /**
     * @param subject an RFC 4514 / RFC 2253 distinguished name, e.g. `CN=DE8AA1A2B3C4D5`
     * @return the DER-encoded CertificationRequest
     */
    fun build(subject: String, signer: V2GSigner): ByteArray {

        require(signer.curve == V2GKeyCurve.P256) {
            "only P-256 CSRs are supported so far; ${signer.curve} needs its own signature algorithm " +
            "identifier and a matching r‖s width."
        }

        val builder = PKCS10CertificationRequestBuilder(
            X500Name(subject),
            SubjectPublicKeyInfo.getInstance(signer.publicKeyDer))

        return builder.build(RawEcdsaContentSigner(signer)).encoded
    }

    /** Reads a CSR back, for tests and for showing a user what is about to be sent. */
    fun parse(der: ByteArray): PKCS10CertificationRequest = PKCS10CertificationRequest(der)

    /**
     * A [ContentSigner] over a [V2GSigner]: it accumulates the bytes BouncyCastle wants signed, hands
     * them to the signer, and converts the raw `r‖s` that comes back into the DER form PKCS#10 wants.
     */
    private class RawEcdsaContentSigner(private val signer: V2GSigner) : ContentSigner {

        private val buffer = java.io.ByteArrayOutputStream()

        override fun getAlgorithmIdentifier(): AlgorithmIdentifier =
            DefaultSignatureAlgorithmIdentifierFinder().find("SHA256WITHECDSA")

        override fun getOutputStream(): OutputStream = buffer

        override fun getSignature(): ByteArray = derFromRawRs(signer.signature(buffer.toByteArray()))
    }

    /**
     * `r‖s` → `SEQUENCE { INTEGER r, INTEGER s }`.
     *
     * The two halves are fixed-width unsigned big-endian; DER integers are signed and minimally
     * encoded, so a leading zero appears exactly when the high bit is set and disappears otherwise.
     * `BigInteger(1, bytes)` gets both right, which is why it is used rather than any byte fiddling.
     */
    internal fun derFromRawRs(raw: ByteArray): ByteArray {
        require(raw.size % 2 == 0) { "a raw r‖s signature has two equal halves; got ${raw.size} bytes" }
        val half = raw.size / 2
        val r = BigInteger(1, raw.copyOfRange(0, half))
        val s = BigInteger(1, raw.copyOfRange(half, raw.size))
        return DERSequence(ASN1EncodableVector().apply {
            add(ASN1Integer(r))
            add(ASN1Integer(s))
        }).encoded
    }
}
