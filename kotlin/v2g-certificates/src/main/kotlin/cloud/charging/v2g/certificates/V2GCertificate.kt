package cloud.charging.v2g.certificates

import org.bouncycastle.asn1.ASN1IA5String
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.x509.CRLDistPoint
import org.bouncycastle.asn1.x509.DistributionPointName
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * An X.509 certificate, read.
 *
 * A thin surface over the JVM's own X.509, deliberately shaped like Swift's `V2GCertificate` and
 * C#'s use of `System.Security.Cryptography.X509Certificates` rather than like `java.security.cert`,
 * so the three back ends read alike at the call site.
 *
 * ## What it does not do
 *
 * **No validation.** Nothing here checks a signature, a chain, an expiry or a key usage. Parsing and
 * trusting are different operations, and conflating them is how a "the certificate parsed" check
 * quietly becomes a security decision. Chains go through [V2GChainValidator]; trust anchors through
 * [V2GTrustStore].
 */
class V2GCertificate(val der: ByteArray) {

    internal val x509: X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate

    /**
     * The subject's Common Name, if it has one.
     *
     * The RFC 2253 subject is parsed for it — the JVM has no direct equivalent of C#'s
     * `GetNameInfo(SimpleName)`. Equivalent for the single-CN subjects this ever sees.
     */
    val commonName: String? get() = commonNames.firstOrNull()

    /** Every Common Name in the subject, in order. */
    val commonNames: List<String>
        get() = x509.subjectX500Principal.name
            .split(',')
            .map { it.trim() }
            .filter { it.startsWith("CN=") }
            .map { it.removePrefix("CN=") }

    /**
     * The eMAID this certificate carries, or `null` if its Common Name cannot be one.
     *
     * ISO 15118-2 constrains `eMAIDType` to **14 or 15 characters** (`V2G_CI_MsgDataTypes.xsd`) —
     * provider and instance identifiers, check digit optional. A Common Name outside that range
     * cannot be sent as an eMAID, so a contract certificate carrying one is unusable for -2 Plug &
     * Charge however well it parses. Length is all that is checked, because length is all the schema
     * constrains; the richer EMAID grammar is a separate check, and claiming it here without
     * implementing it would be worse than not claiming it.
     *
     * A **-2** rule: ISO 15118-20 has no eMAID type and never sends the identity from the
     * certificate, so the same credential can be perfectly usable there.
     */
    val emaid: String? get() = commonName?.takeIf { it.length in EMAID_LENGTH }

    /** True when a Common Name exists but is not a usable eMAID — what a wallet should report rather
     *  than silently drop, since the certificate is otherwise well-formed. */
    val hasUnusableEmaid: Boolean get() = commonName != null && emaid == null

    val subject: String get() = x509.subjectX500Principal.name
    val issuer:  String get() = x509.issuerX500Principal.name

    val serialNumber: ByteArray get() = x509.serialNumber.toByteArray()
    val notValidBefore: Date get() = x509.notBefore
    val notValidAfter:  Date get() = x509.notAfter

    /** True when subject and issuer names match. **Not** a claim that the signature verifies. */
    val isSelfIssued: Boolean
        get() = x509.subjectX500Principal == x509.issuerX500Principal

    /**
     * True when `basicConstraints` marks this as a CA.
     *
     * A *missing* extension counts as "not a CA" — `getBasicConstraints()` returns -1 — which is both
     * what RFC 5280 means and the safe reading. The evil-certificate factory has a
     * `no_basic_constraints` variant precisely because treating absence as permission is a real and
     * exploited mistake.
     */
    val isCertificateAuthority: Boolean get() = x509.basicConstraints >= 0

    /** How many CAs may appear below this one; `null` when unconstrained or not a CA. */
    val maxPathLength: Int?
        get() = x509.basicConstraints.let {
            when {
                it < 0                 -> null
                it == Int.MAX_VALUE    -> null
                else                   -> it
            }
        }

    /** True when the extended key usage permits `serverAuth` — a purpose a contract certificate has
     *  no business carrying, since it would let the same credential be presented as a station. */
    val permitsServerAuth: Boolean
        get() = x509.extendedKeyUsage?.contains(SERVER_AUTH_OID) == true

    /**
     * Where this certificate says its revocation list lives — the CRL distribution points, as URIs.
     *
     * Reading them is this module's business; *fetching* is the app's. A check that reaches the
     * network cannot be run offline, and one that cannot be run offline is one nobody runs.
     */
    val crlDistributionPointUris: List<String>
        get() = x509.getExtensionValue("2.5.29.31")?.let { raw ->
            // The extension value is an OCTET STRING wrapping the real DER.
            val inner = ASN1OctetString.getInstance(raw).octets
            CRLDistPoint.getInstance(inner).distributionPoints
                .mapNotNull { it.distributionPoint }
                .filter { it.type == DistributionPointName.FULL_NAME }
                .flatMap { GeneralNames.getInstance(it.name).names.asList() }
                .filter { it.tagNo == GeneralName.uniformResourceIdentifier }
                .map { ASN1IA5String.getInstance(it.name).string }
        } ?: emptyList()

    /** The subject public key, DER-encoded — for asking whether two certificates carry the *same*
     *  key, which separates a CA renewing itself from a different CA taking its name. */
    val publicKeyDer: ByteArray get() = x509.publicKey.encoded

    /**
     * True when this certificate's key produced [other]'s signature — the check behind a vouched root
     * rotation. A **signature** check: matching issuer and subject names prove nothing, since both
     * are written by whoever made the certificate.
     */
    fun hasSigned(other: V2GCertificate): Boolean =
        runCatching { other.x509.verify(x509.publicKey) }.isSuccess

    override fun equals(other: Any?) = other is V2GCertificate && der.contentEquals(other.der)
    override fun hashCode() = der.contentHashCode()

    companion object {
        /** ISO 15118-2 `eMAIDType`: 14 characters without the check digit, 15 with it. */
        val EMAID_LENGTH = 14..15
        private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
    }
}


/**
 * A contract certificate and the sub-CA certificates that lead towards a root — what an app installs
 * when it scans a provisioning QR code, and what `PaymentDetailsReq` carries on the wire.
 *
 * [linksUp] checks that each certificate's issuer name equals the next one's subject name. A **name**
 * check: the chain is ordered and plausibly connected, which is what the wire format requires. It is
 * emphatically not a claim that any signature verifies or that the chain reaches a trusted root —
 * that is [V2GChainValidator]'s question, and keeping them apart is what stops "the certificate
 * loaded" from quietly becoming "the certificate is good".
 */
class V2GCertificateChain(val leaf: V2GCertificate, val subCertificates: List<V2GCertificate> = emptyList()) {

    companion object {
        /** Parses a chain given leaf-first DER, as both the QR payload and the wire order it. */
        fun of(der: List<ByteArray>): V2GCertificateChain {
            require(der.isNotEmpty()) { "a certificate chain needs at least the leaf certificate." }
            return V2GCertificateChain(V2GCertificate(der.first()), der.drop(1).map(::V2GCertificate))
        }
    }

    /** Every certificate, leaf first — the order the wire wants. */
    val all: List<V2GCertificate> get() = listOf(leaf) + subCertificates

    val linksUp: Boolean
        get() = all.zipWithNext().all { (lower, upper) -> lower.issuer == upper.subject }

    val emaid: String? get() = leaf.emaid
}
