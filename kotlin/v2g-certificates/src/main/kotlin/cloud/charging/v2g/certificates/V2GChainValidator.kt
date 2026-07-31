package cloud.charging.v2g.certificates

import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.util.Date

/**
 * An ISO 15118 profile deviation: the chain is sound and the certificate is not what it claims.
 *
 * Separate from trust on purpose. These do not make a chain invalid — they make it *wrong for this
 * purpose*, which is something to show a user rather than decide for them.
 */
enum class V2GProfileFinding(val description: String) {

    /** The contract leaf also carries `serverAuth`, so the same credential could be presented as a
     *  charging station. PKIX accepts it — nothing about path building objects to an extra purpose —
     *  and the ISO 15118 profile does not. */
    SERVER_AUTH_ON_CONTRACT_CERTIFICATE(
        "the contract certificate also permits serverAuth, so it could be presented as a station."),

    /** The leaf is marked as a certificate authority. A contract certificate signs sessions, not
     *  certificates. */
    CONTRACT_CERTIFICATE_IS_MARKED_AS_CA(
        "the contract certificate is marked as a certificate authority."),

    /** No Common Name, so no eMAID, so it cannot authorize a -2 session. */
    NO_COMMON_NAME("the contract certificate has no Common Name, so it carries no eMAID.");

    /** The name the shared corpus uses. Kept separate from the enum's own spelling so a Kotlin
     *  rename cannot silently stop matching the file. */
    val corpusName: String
        get() = when (this) {
            SERVER_AUTH_ON_CONTRACT_CERTIFICATE  -> "serverAuthOnContractCertificate"
            CONTRACT_CERTIFICATE_IS_MARKED_AS_CA -> "contractCertificateIsMarkedAsCa"
            NO_COMMON_NAME                       -> "noCommonName"
        }
}


/** Why a chain could not be trusted. One right answer, no user opinion involved. */
sealed class V2GChainRejection(val description: String) {

    /** No path from the leaf to any trusted root — a missing intermediate, a broken signature, an
     *  issuer that is not a CA, a certificate out of date, or a chain to a root this app does not
     *  trust. [detail] is the validator's own words. */
    class NoPathToATrustedRoot(val detail: String) :
        V2GChainRejection("no path from this certificate to a trusted root: $detail")

    /** The store holds no roots, so "trusted" is not yet a question that can be asked. */
    data object NoTrustAnchors :
        V2GChainRejection("no root certificates are installed, so nothing can be trusted yet.")

    /**
     * The bundle's certificates do not form an ordered chain, so **which one is the contract
     * certificate is not stated**.
     *
     * ISO 15118 puts the leaf first, and that order is not decoration: it is the statement of which
     * credential is being presented. A validator that reorders the bundle and picks a plausible leaf
     * is guessing at an identity, and would happily validate a sub-CA as though it were the contract.
     */
    data object BundleDoesNotLinkUp :
        V2GChainRejection("the certificates do not form an ordered chain, so which one is the " +
                          "contract certificate is not stated.")
}


/** Whether the chain is trusted, and what is worth saying about it either way. */
class V2GChainVerdict(val rejection: V2GChainRejection?, val findings: List<V2GProfileFinding>) {
    val isTrusted: Boolean get() = rejection == null
}


/**
 * Validates a contract certificate chain against a trust store.
 *
 * ## Two questions, deliberately not one
 *
 * **Is the chain sound?** Path building to a trusted root, signatures, validity dates, CA flags,
 * path-length constraints — RFC 5280's question, answered by the JVM's own PKIX `CertPathValidator`,
 * which is where it belongs.
 *
 * **Does the leaf match the ISO 15118 profile?** Ours, and PKIX has no opinion about it. A contract
 * certificate carrying `serverAuth` builds a perfect path; it is simply a credential that could also
 * impersonate a station. So it is *reported*, not rejected.
 *
 * Both are held to `Vectors/Certificate.chain.vectors.json`, generated from `WWCP_ISO15118_PKI`
 * including its evil-certificate factory — which exists precisely to defeat validators that stop at
 * the path maths, and says so in its own comment.
 *
 * ## What is not here
 *
 * Revocation. ISO 15118-20 staples OCSP into the TLS handshake, which is the transport's business,
 * and nothing in this project checks a contract certificate against a CRL. Named rather than left to
 * be assumed — hence `isRevocationEnabled = false` below being explicit rather than defaulted.
 */
class V2GChainValidator {

    fun validate(chain: V2GCertificateChain,
                 store: V2GTrustStore,
                 now: Date = Date()): V2GChainVerdict {

        // First, because everything after it assumes we know which certificate is the leaf. A bundle
        // that does not link up has not said, and no finding about a guessed leaf would be worth
        // reporting — hence no findings here either.
        if (!chain.linksUp)
            return V2GChainVerdict(V2GChainRejection.BundleDoesNotLinkUp, emptyList())

        val findings = profileFindings(chain.leaf)

        if (store.roots.isEmpty())
            return V2GChainVerdict(V2GChainRejection.NoTrustAnchors, findings)

        return try {
            // The path excludes the trust anchor itself, which PKIXParameters supplies separately.
            val path = CertificateFactory.getInstance("X.509")
                .generateCertPath(chain.all.map { it.x509 })

            val parameters = PKIXParameters(store.roots.map { TrustAnchor(it.x509, null) }.toSet()).apply {
                isRevocationEnabled = false   // see the class comment
                date = now
            }

            CertPathValidator.getInstance("PKIX").validate(path, parameters)
            V2GChainVerdict(null, findings)

        } catch (e: Exception) {
            V2GChainVerdict(
                V2GChainRejection.NoPathToATrustedRoot(e.message ?: e::class.simpleName ?: "unknown"),
                findings)
        }
    }

    /** The profile questions, asked of the leaf alone. Everything here is a property of the
     *  certificate rather than of the path, which is why it survives a rejection. */
    private fun profileFindings(leaf: V2GCertificate): List<V2GProfileFinding> = buildList {
        if (leaf.permitsServerAuth)      add(V2GProfileFinding.SERVER_AUTH_ON_CONTRACT_CERTIFICATE)
        if (leaf.isCertificateAuthority) add(V2GProfileFinding.CONTRACT_CERTIFICATE_IS_MARKED_AS_CA)
        if (leaf.commonName == null)     add(V2GProfileFinding.NO_COMMON_NAME)
    }
}
