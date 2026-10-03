import CryptoKit
import Foundation

/// Binds an ISO 15118-20 session to the peer that opened it, so a paused session can only be resumed
/// by that same peer.
///
/// ## Why this type exists
///
/// -20 obliges the SECC to check that a resumption request came from the same EVCC it set the session
/// up with — a *shall*, with only the method left open. Omitting the check is not a missed hardening
/// but a hole: a second EV that names another's SessionID inherits that EV's authorization, EIM or
/// Plug & Charge alike, and charges on someone else's contract. This stack's own SECC omitted it
/// until 2026-08-08, having been written by analogy to -2, which has no such requirement.
///
/// ## What it computes
///
/// The standard's own worked example: `SHA-512(SessionID ‖ SHA-512(peer leaf certificate))`, the
/// certificate taken from the verified TLS handshake. The nesting is the standard's rather than an
/// embellishment — the stored value is a hash *of a hash*. That example is *should*-level, so a
/// deployment may substitute its own method and this type is the seam where it would. The leaf only:
/// never the chain.
///
/// ## Not symmetric
///
/// The station binds a session to the **vehicle's** leaf and the car binds the same session to the
/// **station's**, so one session has two different binding values and each side checks the one it is
/// owed. A port that computed only one would pass whichever half it happened to implement.
///
/// ## Why a certificate is always there to hash
///
/// -20 permits nothing but full-handshake TLS, and defines a full handshake as one where both ends
/// authenticate by certificate — so in any conformant session both sides hold the other's leaf. Plain
/// TCP is already outside the protocol; this stack speaks it to reach peers that offer nothing else,
/// and there a resume simply cannot be verified. See ``Evcc20Base/resumeBinding`` for what the car
/// does about that, which is deliberately not what the station does.
public enum SessionBinding20 {

    /// The binding for `sessionId` against `peerLeafCertificate` (DER), or `nil` when either is
    /// missing — an unverifiable session, not a matching one.
    public static func compute(sessionId: [UInt8]?, peerLeafCertificate: [UInt8]?) -> [UInt8]? {

        guard let sessionId, !sessionId.isEmpty,
              let peerLeafCertificate, !peerLeafCertificate.isEmpty
        else { return nil }

        let certificateHash = SHA512.hash(data: Data(peerLeafCertificate))
        return Array(SHA512.hash(data: Data(sessionId) + Data(certificateHash)))
    }

    /// Whether a resume presenting `presented` may join a session stored with `stored`. A missing
    /// value on either side never matches.
    ///
    /// Constant-time, because this comparison decides who gets to charge on whose authorization, and
    /// an attacker who can time it can otherwise search the space a byte at a time.
    public static func matches(_ stored: [UInt8]?, _ presented: [UInt8]?) -> Bool {

        guard let stored, !stored.isEmpty, let presented, !presented.isEmpty,
              stored.count == presented.count
        else { return false }

        var difference: UInt8 = 0
        for i in stored.indices { difference |= stored[i] ^ presented[i] }
        return difference == 0
    }
}


/// What a paused session hands to the connection that resumes it.
///
/// All three together, because a session id offered without its binding is a session nobody can
/// claim, and one offered without its energy service is a session that has forgotten what it was
/// doing — a resumed -20 session does not renegotiate the service, so it would otherwise stop
/// expecting the message types a bidirectional transfer requires.
public struct ResumableSession: Sendable {

    public let sessionId: [UInt8]
    public let binding: [UInt8]?
    public let energyServiceId: UInt16

    public init(sessionId: [UInt8], binding: [UInt8]?, energyServiceId: UInt16) {
        self.sessionId       = sessionId
        self.binding         = binding
        self.energyServiceId = energyServiceId
    }
}
