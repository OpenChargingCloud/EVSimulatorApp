package cloud.charging.v2g.pairing

import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

/**
 * The rotating pairing code.
 *
 * ## Not RFC 6238
 *
 * The name is TOTP and the shape is familiar — HMAC over a slot number, a starting offset from the
 * low nibble of the last hash byte — but the last step is different: `length` characters are taken as
 * `alphabet[hash[(offset + i) % 32] % 62]`, rather than RFC 4226's dynamic truncation to six digits.
 * Deliberately, because these codes are read by a camera rather than typed by a person, so a wider
 * alphabet and a longer code cost nothing and buy entropy.
 *
 * A port written from the name alone would compile, run, and never once agree with the Pi — and it
 * would fail as *"pairing does not work"*, with nothing on either screen to say why. Hence
 * `Vectors/Pairing.totp.vectors.json`, which pins the exact characters for fixed instants.
 *
 * The modulo at the end is biased: 256 is not a multiple of 62, so the first eight characters of the
 * alphabet come up very slightly more often. It is inherited from the wire format rather than chosen
 * here, and it is not worth a divergence — a code that differs by a hair in distribution but agrees
 * exactly with the other end is worth more than a fairer one that does not.
 */
object PairingTotpGenerator {

    const val DEFAULT_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val DEFAULT_LENGTH = 12
    const val DEFAULT_VALIDITY_SECONDS = 30L

    data class Slots(val previous: String, val current: String, val next: String,
                     val remainingSeconds: Long)

    /**
     * The three codes the ±1 window accepts, and how long the current one lasts.
     *
     * All three come from the *same* instant rather than from three calls at different times — the
     * verifier needs them simultaneously, and computing them one at a time would let a slot boundary
     * fall in the middle and produce a set that never existed.
     */
    fun slots(sharedSecret: String,
              at: Instant,
              validitySeconds: Long = DEFAULT_VALIDITY_SECONDS,
              length: Int = DEFAULT_LENGTH,
              alphabet: String = DEFAULT_ALPHABET): Slots {

        val mac = mac(sharedSecret)
        val seconds = at.epochSecond
        val slot = Math.floorDiv(seconds, validitySeconds)
        val remaining = validitySeconds - Math.floorMod(seconds, validitySeconds)

        return Slots(
            previous = code(slot - 1, mac, length, alphabet),
            current  = code(slot,     mac, length, alphabet),
            next     = code(slot + 1, mac, length, alphabet),
            remainingSeconds = remaining)
    }

    /** Just the code for right now — what a display needs. */
    fun current(sharedSecret: String,
                at: Instant,
                validitySeconds: Long = DEFAULT_VALIDITY_SECONDS,
                length: Int = DEFAULT_LENGTH,
                alphabet: String = DEFAULT_ALPHABET): String =
        slots(sharedSecret, at, validitySeconds, length, alphabet).current

    private fun mac(sharedSecret: String): Mac {
        val secret = sharedSecret.trim()
        require(secret.length >= 16) { "the shared secret must be at least 16 characters" }
        return Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
    }

    private fun code(slot: Long, mac: Mac, length: Int, alphabet: String): String {

        // The slot number big-endian — network order, not the JVM's or .NET's. Both ends must agree
        // byte for byte, and this is the one place where "whatever the platform does" would diverge.
        val slotBytes = ByteArray(8) { ((slot ushr (56 - it * 8)) and 0xFF).toByte() }

        val hash = mac.doFinal(slotBytes)
        val offset = hash[hash.size - 1].toInt() and 0x0F

        // `% hash.size` wraps: a code longer than 32 characters reuses hash bytes from the start.
        // Not a great property, but it is the property, and a port that ran off the end would throw
        // rather than disagree — which is why the corpus has a 40-character case.
        return buildString(length) {
            for (i in 0 until length) {
                val byte = hash[(offset + i) % hash.size].toInt() and 0xFF
                append(alphabet[byte % alphabet.length])
            }
        }
    }
}


enum class PairingTotpResult {
    /** Valid for a slot in the window, and not seen before. Let the connection through. */
    ACCEPTED,

    /** Not a code for any slot in the window — wrong secret, or a stale screenshot. */
    UNKNOWN,

    /** A code already honoured. Distinct from [UNKNOWN] on purpose: a replay is evidence of someone
     *  re-presenting an observed code, which is worth logging differently from a wrong guess. */
    REPLAYED,

    /** Nothing usable was presented. */
    MALFORMED;

    /** The spelling the corpus uses — C#'s enum name. */
    val corpusName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}


/**
 * The Tier-1 pairing check (`docs/CONCEPT.md` §4.6): whoever holds the shared secret verifies the code
 * the other side read off a display, and gates the connection on it.
 *
 * This is what the rotating code buys, and it is a security property rather than a convenience. A
 * printed sticker, once photographed, replays forever. A code that changes every slot means the peer
 * proves it had **visual line-of-sight to this display within the last ~30 s** — a *proximity proof*,
 * which is precisely what SLAC's security content is in real CCS ("these two endpoints share a
 * physical medium"). Different medium, same class of guarantee.
 *
 * A port of C#'s `PairingTotpVerifier`, held to the same script. It lives on the phone because the app
 * is a simulator and can stand on either side of the pairing — when it plays the station, this is the
 * gate.
 */
class PairingTotpVerifier(
    sharedSecret: String,
    private val validitySeconds: Long = PairingTotpGenerator.DEFAULT_VALIDITY_SECONDS,
    private val clock: () -> Instant = Instant::now,
) {

    private val sharedSecret: String = sharedSecret.also {
        require(it.length >= 16) { "the shared secret must be at least 16 characters" }
    }

    /** Codes already spent, and the moment each may be forgotten. */
    private val spent = LinkedHashMap<String, Instant>()
    private val gate = Any()

    /** How many spent codes are being remembered. Diagnostic — the only way to see from outside that
     *  the cache is bounded rather than merely believed to be. */
    val spentCount: Int
        get() = synchronized(gate) { forget(clock()); spent.size }

    /** The code to display right now, and how long it lasts. */
    fun current(): Pair<String, Long> =
        PairingTotpGenerator.slots(sharedSecret, clock(), validitySeconds)
            .let { it.current to it.remainingSeconds }

    /**
     * Checks a presented code, spending it if it is good.
     *
     * Accepts the previous, current and next slot. That ±1 tolerance is not laxity: it absorbs clock
     * skew between two devices, and **the phone's clock is not trustworthy** — so the peer sends what
     * it *read*, never what it thinks the time is, and this side decides.
     *
     * Each code is accepted **once**. Without that, the ±1 window is a three-slot replay window:
     * anyone who observes a code can present it again while it is still current. The one-shot cache
     * turns it into a single-use credential, which is the difference between "was seen recently" and
     * "is here now".
     */
    fun verify(presented: String): PairingTotpResult {

        if (presented.isBlank()) return PairingTotpResult.MALFORMED

        val now = clock()
        val slots = PairingTotpGenerator.slots(sharedSecret, now, validitySeconds)

        // Non-short-circuiting `or`, against all three, so a timing difference cannot reveal which
        // slot matched — or how many leading characters of a guess were right.
        val matched = fixedTimeEquals(presented, slots.previous) or
                      fixedTimeEquals(presented, slots.current)  or
                      fixedTimeEquals(presented, slots.next)

        if (!matched) return PairingTotpResult.UNKNOWN

        synchronized(gate) {
            forget(now)

            // Held for two slots past its own validity: long enough that a code cannot be replayed
            // once it leaves the ±1 window, short enough that the cache cannot grow without bound.
            return if (spent.putIfAbsent(presented, now.plusSeconds(validitySeconds * 3)) == null)
                       PairingTotpResult.ACCEPTED
                   else
                       PairingTotpResult.REPLAYED
        }
    }

    private fun forget(now: Instant) {
        spent.entries.removeIf { !it.value.isAfter(now) }
    }

    /**
     * Length-independent constant-time comparison. Not `==`: the codes are a credential, and
     * `String.equals` returns as soon as two characters differ.
     */
    private fun fixedTimeEquals(a: String, b: String): Boolean {
        var difference = a.length xor b.length
        for (i in a.indices)
            difference = difference or (a[i].code xor b[i % max(b.length, 1)].code)
        return difference == 0
    }
}
