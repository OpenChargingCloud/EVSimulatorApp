/*
 * Copyright (c) 2021-2026 GraphDefined GmbH <achim.friedland@graphdefined.com>
 * This file is part of EVSimulatorApp
 *
 * Licensed under the Affero GPL license, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.gnu.org/licenses/agpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

using EVSimulatorApp.Pairing;
using NUnit.Framework;

namespace EVSimulatorApp.Pairing.Tests;

/// <summary>
/// The Tier-1 pairing check — the part that makes a scanned code a proximity proof.
/// </summary>
/// <remarks>
/// <c>docs/CONCEPT.md</c> §6.3 lists these as the negative tests worth having: expired slot,
/// replayed code, wrong shared secret, clock skew at ±1 and ±2 slots, and a screenshot of an old
/// code. They demonstrate the property better than any explanation, so they are written as those
/// scenarios rather than as method-coverage.
/// </remarks>
[TestFixture]
public class PairingTotpVerifierTests
{
    private const string Secret = "a-shared-secret-of-sufficient-length";
    private static readonly TimeSpan Slot = TimeSpan.FromSeconds(30);
    private static readonly DateTimeOffset T0 = new(2026, 7, 30, 12, 0, 0, TimeSpan.Zero);

    private sealed class FakeClock(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset now = now;
        public override DateTimeOffset GetUtcNow() => now;
        public void Advance(TimeSpan by) => now += by;
    }

    private static (PairingTotpVerifier Secc, FakeClock Clock) Secc(string secret = Secret)
    {
        var clock = new FakeClock(T0);
        return (new PairingTotpVerifier(secret, Slot, clock), clock);
    }

    [Test]
    public void TheCurrentCodeIsAccepted()
    {
        var (secc, _) = Secc();

        Assert.That(secc.Verify(secc.Current().Totp), Is.EqualTo(PairingTotpResult.Accepted));
    }

    /// <summary>
    /// The one-shot rule, and the reason the whole thing works. Without it the ±1 tolerance is a
    /// three-slot replay window: anyone who sees the code can present it again while it is current.
    /// </summary>
    [Test]
    public void TheSameCodeIsNeverAcceptedTwice()
    {
        var (secc, _) = Secc();
        var code = secc.Current().Totp;

        Assert.Multiple(() =>
        {
            Assert.That(secc.Verify(code), Is.EqualTo(PairingTotpResult.Accepted));
            Assert.That(secc.Verify(code), Is.EqualTo(PairingTotpResult.Replayed));
            Assert.That(secc.Verify(code), Is.EqualTo(PairingTotpResult.Replayed));
        });
    }

    /// <summary>
    /// A replay is reported distinctly from a wrong guess. Both refuse the connection, but only one
    /// is evidence that somebody observed a real code and re-presented it.
    /// </summary>
    [Test]
    public void AReplayIsDistinguishableFromAWrongGuess()
    {
        var (secc, _) = Secc();
        var code = secc.Current().Totp;
        secc.Verify(code);

        Assert.Multiple(() =>
        {
            Assert.That(secc.Verify(code), Is.EqualTo(PairingTotpResult.Replayed));
            Assert.That(secc.Verify("zzzzzzzzzzzz"), Is.EqualTo(PairingTotpResult.Unknown));
        });
    }

    /// <summary>
    /// ±1 slot of skew is tolerated, because the phone's clock is not trustworthy and the EV sends
    /// what it *read* rather than what it thinks the time is.
    /// </summary>
    [TestCase(-1)]
    [TestCase(0)]
    [TestCase(+1)]
    public void CodesWithinOneSlotOfSkewAreAccepted(int slots)
    {
        var (secc, clock) = Secc();

        // The EV reads a code whose clock is `slots` away from the SECC's.
        var (ev, evClock) = Secc();
        evClock.Advance(Slot * slots);
        var read = ev.Current().Totp;

        Assert.That(secc.Verify(read), Is.EqualTo(PairingTotpResult.Accepted), $"{slots:+#;-#;0} slots");
    }

    [TestCase(-2)]
    [TestCase(+2)]
    [TestCase(+10)]
    public void BeyondOneSlotTheCodeIsUnknown(int slots)
    {
        var (secc, _) = Secc();
        var (ev, evClock) = Secc();
        evClock.Advance(Slot * slots);

        Assert.That(secc.Verify(ev.Current().Totp), Is.EqualTo(PairingTotpResult.Unknown));
    }

    /// <summary>A screenshot taken two minutes ago — the scenario the rotating code exists to defeat.</summary>
    [Test]
    public void AScreenshotFromTwoMinutesAgoIsRefused()
    {
        var (secc, clock) = Secc();
        var photographed = secc.Current().Totp;

        clock.Advance(TimeSpan.FromMinutes(2));

        Assert.That(secc.Verify(photographed), Is.EqualTo(PairingTotpResult.Unknown));
    }

    [Test]
    public void AnotherSecretDoesNotProduceAcceptableCodes()
    {
        var (secc, _) = Secc();
        var (attacker, _) = Secc("a-completely-different-secret-x");

        Assert.That(secc.Verify(attacker.Current().Totp), Is.EqualTo(PairingTotpResult.Unknown));
    }

    [TestCase("")]
    [TestCase("   ")]
    public void NothingUsableIsMalformedRatherThanUnknown(string presented)
    {
        var (secc, _) = Secc();

        Assert.That(secc.Verify(presented), Is.EqualTo(PairingTotpResult.Malformed));
    }

    /// <summary>
    /// A code stays spent for as long as it could still be presented. Forgetting it too early would
    /// re-open the replay window that the one-shot rule closes.
    /// </summary>
    [Test]
    public void ASpentCodeStaysSpentForAsLongAsItCouldBeReplayed()
    {
        var (secc, clock) = Secc();
        var code = secc.Current().Totp;
        secc.Verify(code);

        clock.Advance(Slot);   // still inside the ±1 window

        Assert.That(secc.Verify(code), Is.EqualTo(PairingTotpResult.Replayed),
                    "the code left the spent-cache while it was still presentable");
    }

    /// <summary>
    /// The cache must not grow without bound on a device that runs for weeks. A gate that leaks a
    /// dictionary entry per pairing attempt is a slow denial of service against the charger.
    /// </summary>
    [Test]
    public void TheSpentCacheStaysBoundedOverALongRun()
    {
        var (secc, clock) = Secc();

        for (var i = 0; i < 200; i++)
        {
            secc.Verify(secc.Current().Totp);
            clock.Advance(Slot);
        }

        Assert.Multiple(() =>
        {
            // Entries live three slots, so a steady one-per-slot rate settles at a handful — not 200.
            Assert.That(secc.SpentCount, Is.LessThanOrEqualTo(4));
            Assert.That(secc.Verify(secc.Current().Totp), Is.EqualTo(PairingTotpResult.Accepted),
                        "still working after 200 slots");
        });
    }

    /// <summary>The secret is a credential, so a too-short one is refused at construction.</summary>
    [Test]
    public void AWeakSharedSecretIsRefused()
    {
        Assert.Throws<ArgumentException>(() => new PairingTotpVerifier("short"));
    }
}
