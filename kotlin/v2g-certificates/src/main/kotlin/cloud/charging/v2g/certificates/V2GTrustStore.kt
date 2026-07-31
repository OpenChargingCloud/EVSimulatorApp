package cloud.charging.v2g.certificates

import java.security.MessageDigest
import java.util.Date

/**
 * A SHA-256 fingerprint over a certificate's DER — the only thing about a scanned root that actually
 * binds.
 *
 * A root's subject is whatever its issuer chose to write, and its issuer is itself. Anyone can mint
 * one calling itself "Hubject MO Root CA". So a confirmation dialog may *show* the name and must not
 * let the name do the convincing: the fingerprint is what a user can compare against something they
 * got another way, and it is the only field with that property.
 */
class V2GFingerprint private constructor(val bytes: ByteArray) {

    companion object {
        fun of(der: ByteArray) = V2GFingerprint(MessageDigest.getInstance("SHA-256").digest(der))
    }

    /** Uppercase hex in colon-separated pairs — the form fingerprints are published and compared in,
     *  and grouped because a human compares it by eye. */
    override fun toString() = bytes.joinToString(":") { "%02X".format(it) }

    override fun equals(other: Any?) = other is V2GFingerprint && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
}


/**
 * What installing a scanned root would mean, given what is already trusted.
 *
 * The distinctions are the point. [Renewal], [VouchedByTrustedRoot] and [ReplacementUnderKnownName]
 * are all "same name, new certificate", and a user's mental model collapses them into "an update" —
 * which is exactly why an attacker would present one.
 */
sealed class V2GRootInstallVerdict {

    /** Byte-for-byte already in the store. A no-op; say so rather than asking again. */
    data object AlreadyTrusted : V2GRootInstallVerdict()

    /** No stored root shares this subject. The plain first-contact case. */
    data object New : V2GRootInstallVerdict()

    /** Same subject **and same public key**. The same CA, re-issued. */
    data object Renewal : V2GRootInstallVerdict()

    /**
     * A **different key**, but signed by a root already in the store, and that signature verifies.
     * A vouched rotation: the CA used the key it still had to introduce its successor, which is how
     * root rollover is meant to work.
     *
     * **Softer than a replacement, and not proof.** The vouching is worth exactly as much as the
     * vouching key was sound. Whoever holds a compromised root key can introduce any successor they
     * like and it lands here, indistinguishable from an honest rotation because cryptographically it
     * is one. And a CA that *lost* its key cannot use this path at all, so its legitimate rotation
     * shows up as [ReplacementUnderKnownName]. What changes is what a dialog can honestly say — "the
     * root you already trust vouches for this one" — not whether it should ask.
     */
    data class VouchedByTrustedRoot(val fingerprintOfVouchingRoot: V2GFingerprint) : V2GRootInstallVerdict()

    /** Same subject, different key, nobody vouches. Not an update — a different CA under a known
     *  name. Present at least as loudly as [New], never as a routine refresh. */
    data object ReplacementUnderKnownName : V2GRootInstallVerdict()
}


/** Why a scanned certificate cannot be a trust anchor at all — a separate question from whether the
 *  user wants to trust it, and one no dialog is needed for. */
sealed class V2GRootDefect(val description: String) {

    data object NotACertificateAuthority :
        V2GRootDefect("the certificate is not marked as a certificate authority, so it cannot sign anything.")

    data object NeitherSelfSignedNorVouched :
        V2GRootDefect("the certificate is neither self-signed nor signed by a root already trusted, " +
                      "so there is nothing that makes it an anchor.")

    data class Expired(val on: Date) : V2GRootDefect("the certificate expired on $on.")
    data class NotYetValid(val until: Date) : V2GRootDefect("the certificate is not valid until $until.")
}


/**
 * The set of MO root certificates this app trusts.
 *
 * Persistence is deliberately left out: where the store lives — Keystore, a file, a database — is an
 * app decision, and the rules below are the same either way.
 */
interface V2GTrustStore {

    val roots: List<V2GCertificate>

    fun add(root: V2GCertificate)
    fun remove(fingerprint: V2GFingerprint)

    /**
     * Structural reasons this certificate cannot serve as a root, independent of trust. Empty does
     * not mean "trustworthy" — it means asking the user is a meaningful question.
     *
     * Note what is *not* required: being self-signed. A root introduced by its predecessor — a link
     * certificate — is issued by the old root rather than by itself, and that is the well-behaved
     * rotation path, not a defect.
     */
    fun defects(candidate: V2GCertificate, now: Date = Date()): List<V2GRootDefect> = buildList {
        if (!candidate.isCertificateAuthority) add(V2GRootDefect.NotACertificateAuthority)
        if (!candidate.isSelfIssued && vouchingRoot(candidate) == null)
            add(V2GRootDefect.NeitherSelfSignedNorVouched)
        if (now.after(candidate.notValidAfter))   add(V2GRootDefect.Expired(candidate.notValidAfter))
        if (now.before(candidate.notValidBefore)) add(V2GRootDefect.NotYetValid(candidate.notValidBefore))
    }

    /** A stored root whose key actually signed [candidate]. The signature is checked, not merely the
     *  issuer name — a name match alone would let anyone claim a voucher. */
    fun vouchingRoot(candidate: V2GCertificate): V2GCertificate? =
        roots.firstOrNull { it.hasSigned(candidate) }

    fun verdict(candidate: V2GCertificate): V2GRootInstallVerdict {

        val fingerprint = V2GFingerprint.of(candidate.der)
        if (roots.any { V2GFingerprint.of(it.der) == fingerprint })
            return V2GRootInstallVerdict.AlreadyTrusted

        val sameName = roots.filter { it.subject == candidate.subject }

        if (sameName.any { it.publicKeyDer.contentEquals(candidate.publicKeyDer) })
            return V2GRootInstallVerdict.Renewal

        // Before the name comparison decides anything: a vouched successor may legitimately carry a
        // different name, and a same-named stranger is not made friendlier by the name.
        vouchingRoot(candidate)?.let {
            return V2GRootInstallVerdict.VouchedByTrustedRoot(V2GFingerprint.of(it.der))
        }

        return if (sameName.isEmpty()) V2GRootInstallVerdict.New
               else V2GRootInstallVerdict.ReplacementUnderKnownName
    }

    fun trusts(fingerprint: V2GFingerprint): Boolean =
        roots.any { V2GFingerprint.of(it.der) == fingerprint }
}


class InMemoryTrustStore(roots: List<V2GCertificate> = emptyList()) : V2GTrustStore {

    private val stored = roots.toMutableList()

    override val roots: List<V2GCertificate> get() = stored.toList()

    /** Adding is unconditional on purpose: the decision — defects, verdict, the user's answer —
     *  belongs above this, and a store that silently refused would hide it. */
    override fun add(root: V2GCertificate) {
        val fingerprint = V2GFingerprint.of(root.der)
        if (stored.none { V2GFingerprint.of(it.der) == fingerprint }) stored += root
    }

    override fun remove(fingerprint: V2GFingerprint) {
        stored.removeAll { V2GFingerprint.of(it.der) == fingerprint }
    }
}
