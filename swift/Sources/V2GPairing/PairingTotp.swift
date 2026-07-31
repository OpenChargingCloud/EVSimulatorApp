import CryptoKit
import Foundation

/// The rotating pairing code.
///
/// ## Not RFC 6238
///
/// The name is TOTP and the shape is familiar — HMAC over a slot number, a starting offset from the
/// low nibble of the last hash byte — but the last step is different: `length` characters are taken as
/// `alphabet[hash[(offset + i) % 32] % 62]`, rather than RFC 4226's dynamic truncation to six digits.
/// Deliberately, because these codes are read by a camera rather than typed by a person, so a wider
/// alphabet and a longer code cost nothing and buy entropy.
///
/// A port written from the name alone would compile, run, and never once agree with the Pi — and it
/// would fail as *"pairing does not work"*, with nothing on either screen to say why. Hence
/// `Vectors/Pairing.totp.vectors.json`, which pins the exact characters for fixed instants.
///
/// The modulo at the end is biased: 256 is not a multiple of 62, so the first eight characters of the
/// alphabet come up very slightly more often. It is inherited from the wire format rather than chosen
/// here, and it is not worth a divergence — a code that differs by a hair in distribution but agrees
/// exactly with the other end is worth more than a fairer one that does not.
public enum PairingTotpGenerator {

    public static let defaultAlphabet = Array("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
    public static let defaultLength = 12
    public static let defaultValiditySeconds: Int64 = 30

    public struct Slots: Equatable, Sendable {
        public let previous: String
        public let current: String
        public let next: String
        public let remainingSeconds: Int64
    }

    /// The three codes the ±1 window accepts, and how long the current one lasts.
    ///
    /// All three come from the *same* instant rather than from three calls at different times — the
    /// verifier needs them simultaneously, and computing them one at a time would let a slot boundary
    /// fall in the middle and produce a set that never existed.
    public static func slots(sharedSecret: String,
                             at: Date,
                             validitySeconds: Int64 = defaultValiditySeconds,
                             length: Int = defaultLength,
                             alphabet: [Character] = defaultAlphabet) -> Slots {

        let key = SymmetricKey(data: Data(sharedSecret.trimmingCharacters(in: .whitespaces).utf8))

        // `Int64(at.timeIntervalSince1970.rounded(.down))` rather than a truncating cast: truncation
        // rounds towards zero, so every instant before 1970 would land in the slot above its own.
        let seconds = Int64(at.timeIntervalSince1970.rounded(.down))
        let slot = Int64((Double(seconds) / Double(validitySeconds)).rounded(.down))
        let remaining = validitySeconds - (seconds - slot * validitySeconds)

        return Slots(previous: code(slot: slot - 1, key: key, length: length, alphabet: alphabet),
                     current:  code(slot: slot,     key: key, length: length, alphabet: alphabet),
                     next:     code(slot: slot + 1, key: key, length: length, alphabet: alphabet),
                     remainingSeconds: remaining)
    }

    /// Just the code for right now — what a display needs.
    public static func current(sharedSecret: String,
                               at: Date,
                               validitySeconds: Int64 = defaultValiditySeconds,
                               length: Int = defaultLength,
                               alphabet: [Character] = defaultAlphabet) -> String {
        slots(sharedSecret: sharedSecret, at: at, validitySeconds: validitySeconds,
              length: length, alphabet: alphabet).current
    }

    private static func code(slot: Int64, key: SymmetricKey, length: Int, alphabet: [Character]) -> String {

        // The slot number big-endian — network order, not whatever this CPU prefers. Both ends must
        // agree byte for byte, and this is the one place where "whatever the platform does" would
        // diverge silently.
        let value = UInt64(bitPattern: slot)
        let slotBytes = (0 ..< 8).map { UInt8((value >> (56 - $0 * 8)) & 0xFF) }

        let hash = Array(HMAC<SHA256>.authenticationCode(for: slotBytes, using: key))
        let offset = Int(hash[hash.count - 1] & 0x0F)

        // `% hash.count` wraps: a code longer than 32 characters reuses hash bytes from the start.
        // Not a great property, but it is the property, and a port that ran off the end would crash
        // rather than disagree — which is why the corpus has a 40-character case.
        var code = ""
        code.reserveCapacity(length)
        for i in 0 ..< length {
            code.append(alphabet[Int(hash[(offset + i) % hash.count]) % alphabet.count])
        }
        return code
    }
}


public enum PairingTotpResult: String, Sendable {
    /// Valid for a slot in the window, and not seen before. Let the connection through.
    case accepted = "Accepted"

    /// Not a code for any slot in the window — wrong secret, or a stale screenshot.
    case unknown = "Unknown"

    /// A code already honoured. Distinct from ``unknown`` on purpose: a replay is evidence of someone
    /// re-presenting an observed code, which is worth logging differently from a wrong guess.
    case replayed = "Replayed"

    /// Nothing usable was presented.
    case malformed = "Malformed"
}


/// The Tier-1 pairing check (`docs/CONCEPT.md` §4.6): whoever holds the shared secret verifies the code
/// the other side read off a display, and gates the connection on it.
///
/// This is what the rotating code buys, and it is a security property rather than a convenience. A
/// printed sticker, once photographed, replays forever. A code that changes every slot means the peer
/// proves it had **visual line-of-sight to this display within the last ~30 s** — a *proximity proof*,
/// which is precisely what SLAC's security content is in real CCS ("these two endpoints share a
/// physical medium"). Different medium, same class of guarantee.
///
/// A port of C#'s `PairingTotpVerifier`, held to the same script. It lives on the phone because the app
/// is a simulator and can stand on either side of the pairing — when it plays the station, this is the
/// gate.
public final class PairingTotpVerifier {

    private let sharedSecret: String
    private let validitySeconds: Int64
    private let clock: () -> Date

    /// Codes already spent, and the moment each may be forgotten.
    private var spent: [String: Date] = [:]
    private let gate = NSLock()

    /// - Parameter sharedSecret: provisioned out of band — a one-time static setup code, or the
    ///   test-PKI bootstrap. It is **never** in the rotating code; only the derived TOTP is.
    public init(sharedSecret: String,
                validitySeconds: Int64 = PairingTotpGenerator.defaultValiditySeconds,
                clock: @escaping () -> Date = Date.init) {
        precondition(sharedSecret.count >= 16, "the shared secret must be at least 16 characters")
        self.sharedSecret = sharedSecret
        self.validitySeconds = validitySeconds
        self.clock = clock
    }

    /// How many spent codes are being remembered. Diagnostic — the only way to see from outside that
    /// the cache is bounded rather than merely believed to be.
    public var spentCount: Int {
        gate.lock(); defer { gate.unlock() }
        forget(now: clock())
        return spent.count
    }

    /// The code to display right now, and how long it lasts.
    public func current() -> (totp: String, remainingSeconds: Int64) {
        let slots = PairingTotpGenerator.slots(sharedSecret: sharedSecret, at: clock(),
                                               validitySeconds: validitySeconds)
        return (slots.current, slots.remainingSeconds)
    }

    /// Checks a presented code, spending it if it is good.
    ///
    /// Accepts the previous, current and next slot. That ±1 tolerance is not laxity: it absorbs clock
    /// skew between two devices, and **the phone's clock is not trustworthy** — so the peer sends what
    /// it *read*, never what it thinks the time is, and this side decides.
    ///
    /// Each code is accepted **once**. Without that, the ±1 window is a three-slot replay window:
    /// anyone who observes a code can present it again while it is still current. The one-shot cache
    /// turns it into a single-use credential, which is the difference between "was seen recently" and
    /// "is here now".
    public func verify(_ presented: String) -> PairingTotpResult {

        guard !presented.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .malformed }

        let now = clock()
        let slots = PairingTotpGenerator.slots(sharedSecret: sharedSecret, at: now,
                                               validitySeconds: validitySeconds)

        // Bitwise `|` against all three, never `||`: short-circuiting would let a timing difference
        // reveal which slot matched — or how many leading characters of a guess were right.
        let matched = (Self.fixedTimeEquals(presented, slots.previous) ? 1 : 0)
                    | (Self.fixedTimeEquals(presented, slots.current)  ? 1 : 0)
                    | (Self.fixedTimeEquals(presented, slots.next)     ? 1 : 0)

        guard matched == 1 else { return .unknown }

        gate.lock(); defer { gate.unlock() }
        forget(now: now)

        guard spent[presented] == nil else { return .replayed }

        // Held for two slots past its own validity: long enough that a code cannot be replayed once
        // it leaves the ±1 window, short enough that the cache cannot grow without bound.
        spent[presented] = now.addingTimeInterval(TimeInterval(validitySeconds * 3))
        return .accepted
    }

    private func forget(now: Date) {
        spent = spent.filter { $0.value > now }
    }

    /// Length-independent constant-time comparison. Not `==`: the codes are a credential, and string
    /// equality returns as soon as two characters differ.
    private static func fixedTimeEquals(_ a: String, _ b: String) -> Bool {
        let left = Array(a.unicodeScalars), right = Array(b.unicodeScalars)
        // An empty comparand cannot be equal to anything this is asked about, and indexing into it
        // would trap rather than return false. C# gets away with the same expression because a
        // generated code is never empty — which is a reason it has not happened, not a reason it
        // cannot.
        guard !right.isEmpty else { return left.isEmpty }
        var difference = UInt32(left.count) ^ UInt32(right.count)
        for i in left.indices {
            difference |= left[i].value ^ right[i % max(right.count, 1)].value
        }
        return difference == 0
    }
}
