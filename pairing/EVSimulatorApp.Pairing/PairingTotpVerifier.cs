using cloud.charging.open.utils.QRCodes.TOTP;

namespace EVSimulatorApp.Pairing;

/// <summary>
/// The Tier-1 pairing check (<c>docs/CONCEPT.md</c> §4.6): the SECC verifies the TOTP the EV read
/// off its display, and gates the V2G TCP accept on it.
/// </summary>
/// <remarks>
/// <para>
/// This is what the rotating code buys, and it is a security property rather than a convenience.
/// A printed sticker, once photographed, replays forever. A code that changes every slot means the
/// EV proves it had <b>visual line-of-sight to this display within the last ~30 s</b> — a
/// <em>proximity proof</em>, which is precisely what SLAC's security content is in real CCS
/// ("these two endpoints share a physical medium"). Different medium, same class of guarantee.
/// </para>
/// <para>
/// It sits before the V2G session for a structural reason, not an expedient one: <b>SLAC is not
/// part of the V2G message set either.</b> It runs first, outside the session, and gates everything
/// after it. A TOTP check on the TCP accept occupies exactly that slot, works identically for -2 and
/// -20, and needs no schema deviation — which matters because -2 has no in-band room for one
/// (`evccIDType` is 6 bytes of hexBinary).
/// </para>
/// </remarks>
public sealed class PairingTotpVerifier
{
    private readonly string sharedSecret;
    private readonly TimeSpan validity;
    private readonly TimeProvider clock;

    /// <summary>Codes already spent, and the moment each may be forgotten.</summary>
    private readonly Dictionary<string, DateTimeOffset> spent = new(StringComparer.Ordinal);
    private readonly Lock gate = new();

    /// <param name="sharedSecret">
    /// Provisioned out of band — a one-time static setup code, or the test-PKI bootstrap. It is
    /// <b>never</b> in the rotating code; only the derived TOTP is.
    /// </param>
    public PairingTotpVerifier(string sharedSecret, TimeSpan? validity = null, TimeProvider? clock = null)
    {
        if (sharedSecret.Length < 16)
            throw new ArgumentException("the shared secret must be at least 16 characters", nameof(sharedSecret));

        this.sharedSecret = sharedSecret;
        this.validity     = validity ?? TimeSpan.FromSeconds(30);
        this.clock        = clock ?? TimeProvider.System;
    }

    /// <summary>
    /// How many spent codes are being remembered. Diagnostic — a Pi status page can show it, and it
    /// is the only way to see from outside that the cache is bounded rather than merely believed to
    /// be.
    /// </summary>
    public int SpentCount
    {
        get { lock (gate) { Forget(clock.GetUtcNow()); return spent.Count; } }
    }

    /// <summary>The code to display right now, and how long it lasts.</summary>
    public (string Totp, TimeSpan Remaining) Current()
    {
        var (totp, remaining, _) = QRCodeTOTPGenerator.GenerateTOTP(
            sharedSecret, validity, Timestamp: clock.GetUtcNow());
        return (totp, remaining);
    }

    /// <summary>
    /// Checks a presented code, spending it if it is good.
    /// </summary>
    /// <remarks>
    /// <para>
    /// Accepts the previous, current and next slot. That ±1 tolerance is not laxity: it absorbs
    /// clock skew between two devices, and <b>the phone's clock is not trustworthy</b> — so the EV
    /// sends what it read, never what it thinks the time is, and the SECC decides.
    /// </para>
    /// <para>
    /// Each code is accepted <b>once</b>. Without that, the ±1 window is a three-slot replay window:
    /// anyone who observes a code can present it again while it is still current. The one-shot cache
    /// turns it into a single-use credential, which is the difference between "was seen recently"
    /// and "is here now".
    /// </para>
    /// </remarks>
    public PairingTotpResult Verify(string presented)
    {
        if (string.IsNullOrWhiteSpace(presented))
            return PairingTotpResult.Malformed;

        var now = clock.GetUtcNow();
        var (previous, current, next, _, _) = QRCodeTOTPGenerator.GenerateTOTPs(
            sharedSecret, validity, Timestamp: now);

        // Constant-time against all three, so a timing difference cannot reveal which slot matched
        // — or how many leading characters of a guess were right.
        var matched = FixedTimeEquals(presented, previous)
                    | FixedTimeEquals(presented, current)
                    | FixedTimeEquals(presented, next);

        if (!matched)
            return PairingTotpResult.Unknown;

        lock (gate)
        {
            Forget(now);

            // Held for two slots past its own validity: long enough that a code cannot be replayed
            // once it leaves the ±1 window, short enough that the cache cannot grow without bound.
            return spent.TryAdd(presented, now + validity * 3)
                       ? PairingTotpResult.Accepted
                       : PairingTotpResult.Replayed;
        }
    }

    private void Forget(DateTimeOffset now)
    {
        foreach (var (code, expiry) in spent.Where(kv => kv.Value <= now).ToList())
            spent.Remove(code);
    }

    /// <summary>
    /// Length-independent constant-time comparison. Not `==`: the codes are a credential, and
    /// `String.Equals` returns as soon as two characters differ.
    /// </summary>
    private static bool FixedTimeEquals(string a, string b)
    {
        var difference = (uint) a.Length ^ (uint) b.Length;
        for (var i = 0; i < a.Length; i++)
            difference |= (uint) a[i] ^ (uint) b[i % Math.Max(b.Length, 1)];
        return difference == 0;
    }
}

public enum PairingTotpResult
{
    /// <summary>Valid for a slot in the window, and not seen before. Let the connection through.</summary>
    Accepted,

    /// <summary>Not a code for any slot in the window — wrong secret, or a stale screenshot.</summary>
    Unknown,

    /// <summary>
    /// A code we have already honoured. Distinct from <see cref="Unknown"/> on purpose: a replay is
    /// evidence of someone re-presenting an observed code, which is worth logging differently from
    /// a wrong guess.
    /// </summary>
    Replayed,

    /// <summary>Nothing usable was presented.</summary>
    Malformed,
}
