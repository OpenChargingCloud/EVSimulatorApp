package cloud.charging.v2g.evcc

import java.security.MessageDigest

/**
 * Binds an ISO 15118-20 session to the peer that opened it, so a paused session can only be resumed
 * by that same peer.
 *
 * ## Why this object exists
 *
 * -20 obliges the SECC to check that a resumption request came from the same EVCC it set the session
 * up with — a *shall*, with only the method left open. Omitting the check is not a missed hardening
 * but a hole: a second EV that names another's SessionID inherits that EV's authorization, EIM or Plug
 * & Charge alike, and charges on someone else's contract. This stack's own SECC omitted it until
 * 2026-08-08, having been written by analogy to -2, which has no such requirement.
 *
 * ## What it computes
 *
 * The standard's own worked example: `SHA-512(SessionID || SHA-512(peer leaf certificate))`, the
 * certificate taken from the verified TLS handshake. The nesting is the standard's rather than an
 * embellishment — the stored value is a hash *of a hash*. That example is *should*-level, so a
 * deployment may substitute its own method and this object is the seam where it would. The leaf only:
 * never the chain.
 *
 * ## Not symmetric
 *
 * The station binds a session to the **vehicle's** leaf and the car binds the same session to the
 * **station's**, so one session has two different binding values and each side checks the one it is
 * owed. A port that computed only one would pass whichever half it happened to implement.
 *
 * ## Why a certificate is always there to hash
 *
 * -20 permits nothing but full-handshake TLS, and defines a full handshake as one where both ends
 * authenticate by certificate — so in any conformant session both sides hold the other's leaf. Plain
 * TCP is already outside the protocol; this stack speaks it to reach peers that offer nothing else,
 * and there a resume simply cannot be verified. See [Evcc20Base.resumeBinding] for what the car does
 * about that, which is deliberately not what the station does.
 */
object SessionBinding20 {

    /**
     * The binding for [sessionId] against [peerLeafCertificate] (DER), or `null` when either is
     * missing — an unverifiable session, not a matching one.
     */
    fun compute(sessionId: ByteArray?, peerLeafCertificate: ByteArray?): ByteArray? {

        if (sessionId == null || sessionId.isEmpty()) return null
        if (peerLeafCertificate == null || peerLeafCertificate.isEmpty()) return null

        val certificateHash = MessageDigest.getInstance("SHA-512").digest(peerLeafCertificate)
        return MessageDigest.getInstance("SHA-512").digest(sessionId + certificateHash)
    }

    /**
     * Whether a resume presenting [presented] may join a session stored with [stored]. A missing value
     * on either side never matches.
     *
     * `MessageDigest.isEqual` because this comparison decides who gets to charge on whose
     * authorization, and an attacker who can time a byte-at-a-time comparison can search the space.
     */
    fun matches(stored: ByteArray?, presented: ByteArray?): Boolean =
        stored != null && stored.isNotEmpty() &&
        presented != null && presented.isNotEmpty() &&
        MessageDigest.isEqual(stored, presented)
}

/**
 * What a paused session hands to the connection that resumes it.
 *
 * All three together, because a session id offered without its binding is a session nobody can claim,
 * and one offered without its energy service is a session that has forgotten what it was doing — a
 * resumed -20 session does not renegotiate the service, so it would otherwise stop expecting the
 * message types a bidirectional transfer requires.
 */
class ResumableSession(
    val sessionId: ByteArray,
    val binding: ByteArray?,
    val energyServiceId: UShort,
)
