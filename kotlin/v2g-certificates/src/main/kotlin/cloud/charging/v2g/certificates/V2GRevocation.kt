package cloud.charging.v2g.certificates

import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.util.Date

/**
 * What a revocation check found — **three** answers, not two.
 *
 * The third is the whole reason this type exists. "Not on the list" and "no usable list" look
 * identical to a naive check and are not remotely the same thing: the second is the classic soft-fail
 * hole, where whoever wants a revoked credential accepted simply arranges for the list to be
 * unavailable. A boolean cannot express that difference, so this is not a boolean.
 *
 * What to *do* about [Unknown] is a policy the app owns — refuse, warn, or proceed — and the only
 * thing settled here is that it cannot be mistaken for [NotRevoked] by accident.
 */
sealed class V2GRevocationStatus {

    /** A CRL that verified, was current, and does not list this certificate. */
    data object NotRevoked : V2GRevocationStatus()

    /**
     * @param reason RFC 5280's reason, spelled the same in every back end.
     *
     * Normalised rather than passed through: the JVM calls it `KEY_COMPROMISE` and other platforms
     * spell it otherwise, and this string is something an app shows a user. The fingerprint format
     * taught the same lesson one module over — a display string whose shape differs per platform is
     * a difference nothing catches until someone compares two screens.
     */
    data class Revoked(val on: Date, val reason: String?) : V2GRevocationStatus()

    /** No answer could be obtained. [why] says which of the several reasons it was. */
    data class Unknown(val why: String) : V2GRevocationStatus()
}


/**
 * Checks a certificate against a certificate revocation list.
 *
 * ## What is checked before the list is believed
 *
 * A CRL is **attacker-supplied input** in exactly the way a certificate chain is, and it fails in a
 * direction that is easy to miss: a forged or substituted CRL does not need to make false claims, it
 * only needs to be *empty*. So before its contents count for anything:
 *
 * * its signature must verify under the issuing CA — otherwise anyone can mint one;
 * * it must be current — an expired CRL is a snapshot of the past, and treating it as authoritative
 *   is how a revocation gets outrun by simply waiting;
 * * its issuer must be the certificate's issuer — a valid CRL from a different CA says nothing about
 *   this certificate, and reading it as "not listed" would be a straightforward bypass.
 *
 * Each of those failures yields [V2GRevocationStatus.Unknown], never [V2GRevocationStatus.NotRevoked].
 *
 * ## What is not here
 *
 * **Fetching.** Where a CRL comes from — a URL in the certificate, a cache, a file the user picked —
 * is the app's business, and network I/O has no place in a check that has to be testable offline.
 * [V2GCertificate.crlDistributionPointUris] says where to look.
 *
 * **OCSP.** ISO 15118-20 staples an OCSP response into the TLS handshake for the *station's* chain,
 * which is the transport's business and not the wallet's. A contract certificate is a separate
 * question, and CRLs are what the ISO 15118 PKI actually publishes for it.
 */
object V2GRevocationChecker {

    /** `KEY_COMPROMISE` → `keyCompromise`: RFC 5280's own spelling, and the same in every back end. */
    internal fun normalised(jvmReason: String?): String? = jvmReason?.split('_')
        ?.filter { it.isNotEmpty() }
        ?.mapIndexed { i, word ->
            if (i == 0) word.lowercase() else word.lowercase().replaceFirstChar { it.uppercase() }
        }?.joinToString("")

    fun parseCrl(der: ByteArray): X509CRL =
        CertificateFactory.getInstance("X.509").generateCRL(der.inputStream()) as X509CRL

    /**
     * @param certificate the certificate in question
     * @param issuer the CA that issued it, whose key must have signed the CRL
     * @param crl the list, DER-encoded
     */
    fun check(certificate: V2GCertificate,
              issuer: V2GCertificate,
              crl: ByteArray,
              now: Date = Date()): V2GRevocationStatus {

        val list = runCatching { parseCrl(crl) }.getOrElse {
            return V2GRevocationStatus.Unknown("the CRL could not be parsed: ${it.message}")
        }

        // The signature first: everything below is only meaningful if this list came from the CA it
        // claims to. An unverified CRL that happens to be empty is the cheapest possible bypass.
        runCatching { list.verify(issuer.x509.publicKey) }.getOrElse {
            return V2GRevocationStatus.Unknown(
                "the CRL's signature does not verify under the issuing CA, so its contents mean nothing.")
        }

        if (list.issuerX500Principal != certificate.x509.issuerX500Principal)
            return V2GRevocationStatus.Unknown(
                "this CRL was issued by ${list.issuerX500Principal}, which did not issue the " +
                "certificate — it says nothing about it either way.")

        list.nextUpdate?.let {
            if (now.after(it))
                return V2GRevocationStatus.Unknown(
                    "the CRL expired on $it; a stale list cannot say whether a certificate has been " +
                    "revoked since.")
        } ?: return V2GRevocationStatus.Unknown(
            "the CRL states no nextUpdate, so there is no point at which it stops being believed.")

        if (now.before(list.thisUpdate))
            return V2GRevocationStatus.Unknown("the CRL is not valid until ${list.thisUpdate}.")

        val entry = list.getRevokedCertificate(certificate.x509.serialNumber)
            ?: return V2GRevocationStatus.NotRevoked

        return V2GRevocationStatus.Revoked(entry.revocationDate, normalised(entry.revocationReason?.name))
    }
}
