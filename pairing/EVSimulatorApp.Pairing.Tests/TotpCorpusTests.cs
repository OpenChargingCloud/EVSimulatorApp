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

using System.Text.Json;
using System.Text.Json.Serialization;
using cloud.charging.open.utils.QRCodes.TOTP;
using EVSimulatorApp.Pairing;
using NUnit.Framework;

namespace EVSimulatorApp.Pairing.Tests;

/// <summary>
/// Generates and checks the TOTP corpus — the second half of what a phone needs in order to speak to
/// the Pi, and the half where a silent divergence is worst.
/// </summary>
/// <remarks>
/// <para>
/// A pairing payload that drifts fails visibly: a field is missing, a warning is absent, something on
/// screen looks wrong. A TOTP that drifts fails as <b>"pairing does not work"</b>, with no way to see
/// why from either end — every code is simply rejected, and both sides are certain they are right.
/// The generator is a hash: it either agrees exactly or it agrees not at all.
/// </para>
/// <para>
/// So this pins the algorithm rather than the API: for a fixed secret and fixed instants, the exact
/// characters. It is not RFC 6238 — the generator draws <c>TOTPLength</c> characters from a 62-character
/// alphabet by <c>hash[(offset + i) % 32] % 62</c> rather than doing RFC 4226 dynamic truncation to
/// digits, deliberately, because these codes are read by machines and a bigger alphabet buys entropy.
/// A port written from the description "it's TOTP" would compile, run, and never agree.
/// </para>
/// <para>
/// Timestamps here are absolute and hard-coded. A corpus generated from "now" would regenerate
/// differently every run and pin nothing at all.
/// </para>
/// </remarks>
[TestFixture]
public class TotpCorpusTests
{

    /// <summary>Not a real secret — a corpus input. Long enough to satisfy the 16-character floor.</summary>
    private const String Secret = "corpus-shared-secret-0123456789";

    private static String VectorPath
    {
        get
        {
            var directory = new DirectoryInfo(AppContext.BaseDirectory);
            while (directory is not null && !File.Exists(Path.Combine(directory.FullName, "EVSimulatorApp.slnx")))
                directory = directory.Parent;

            return Path.Combine(directory!.FullName,
                                "pairing/EVSimulatorApp.Pairing.Tests/Vectors/Pairing.totp.vectors.json");
        }
    }

    private static readonly JsonSerializerOptions Json = new() {
        WriteIndented          = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };


    /// <summary>The instants the corpus pins, as Unix seconds.</summary>
    /// <remarks>
    /// Chosen to sit on both sides of the interesting boundaries: exactly on a slot edge, one second
    /// before one, one second after one. Off-by-one in the slot arithmetic is the mistake a port
    /// actually makes, and a corpus sampled at random instants would miss it about 29 times in 30.
    /// </remarks>
    private static readonly (String Name, Int64 At, Int32 Validity, UInt32 Length)[] Instants = [
        ("slot-edge",           1_700_000_010, 30, 12),   // 1700000010 % 30 == 0
        ("one-second-before",   1_700_000_039, 30, 12),
        ("one-second-after",    1_700_000_040, 30, 12),
        ("mid-slot",            1_700_000_025, 30, 12),
        ("epoch-ish",                   90_061, 30, 12),
        ("short-validity",      1_700_000_010, 10, 12),
        ("long-validity",       1_700_000_010, 60, 12),
        ("short-code",          1_700_000_010, 30,  6),
        ("long-code",           1_700_000_010, 30, 40),   // longer than the 32-byte hash: the
                                                          // `% currentHash.Length` wrap is load-bearing
    ];


    [Test, Explicit("Regenerates the corpus every back end is held to. Run deliberately.")]
    public void RegenerateTheCorpus()
    {

        var slots = new List<Object>();

        foreach (var (name, at, validity, length) in Instants)
        {

            var timestamp = DateTimeOffset.FromUnixTimeSeconds(at);
            var window    = TimeSpan.FromSeconds(validity);

            var (previous, current, next, remaining, _) =
                QRCodeTOTPGenerator.GenerateTOTPs(Secret, window, length, Timestamp: timestamp);

            slots.Add(new {
                name,
                at,
                validitySeconds  = validity,
                length,
                previous,
                current,
                next,
                remainingSeconds = (Int32) remaining.TotalSeconds,
            });

        }

        var corpus = new {
            note = "Generated by TotpCorpusTests.RegenerateTheCorpus. The TOTP algorithm as the Pi "
                 + "actually computes it: HMAC-SHA256 over the big-endian 8-byte slot number, a "
                 + "starting offset from the low nibble of the last hash byte, then `length` "
                 + "characters taken as alphabet[hash[(offset + i) % 32] % 62]. This is NOT RFC 6238 "
                 + "— it is a wider alphabet and a longer code, because these codes are read by "
                 + "machines. A port written from the name alone will not agree.",
            secret   = Secret,
            alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
            slots,
            verifier = VerifierScript(),
        };

        Directory.CreateDirectory(Path.GetDirectoryName(VectorPath)!);
        File.WriteAllText(VectorPath, JsonSerializer.Serialize(corpus, Json));

        TestContext.Out.WriteLine($"{slots.Count} slot vectors + {VerifierScript().Count} verifier steps → {VectorPath}");

    }


    /// <summary>
    /// A script for the verifier: presentations in order, against a clock that only moves when the
    /// script says so.
    /// </summary>
    /// <remarks>
    /// The verifier is stateful — accepting a code changes what happens next — so a set of independent
    /// cases could not express what matters about it. Replay is only visible as a <em>sequence</em>.
    /// </remarks>
    private static List<Object> VerifierScript()
    {

        var steps = new List<Object>();
        var start = DateTimeOffset.FromUnixTimeSeconds(1_700_000_025);   // mid-slot
        var clock = new FakeClock(start);
        var verifier = new PairingTotpVerifier(Secret, TimeSpan.FromSeconds(30), clock);

        void Step(String what, Int32 advanceSeconds, Func<String> code)
        {
            clock.Advance(TimeSpan.FromSeconds(advanceSeconds));
            var presented = code();
            steps.Add(new {
                what,
                atUnixSeconds = clock.GetUtcNow().ToUnixTimeSeconds(),
                presented,
                expected      = verifier.Verify(presented).ToString(),
                spentAfter    = verifier.SpentCount,
            });
        }

        String Slot(Int32 offsetSlots)
        {
            var at = clock.GetUtcNow() + TimeSpan.FromSeconds(30 * offsetSlots);
            var (_, current, _, _, _) = QRCodeTOTPGenerator.GenerateTOTPs(
                                            Secret, TimeSpan.FromSeconds(30), 12, Timestamp: at);
            return current;
        }

        Step("the current code is accepted",                          0, () => Slot(0));
        Step("the same code again is a replay, not a fresh accept",   1, () => Slot(0));
        Step("the previous slot's code is accepted — clock skew",     1, () => Slot(-1));
        Step("and it too is one-shot",                                1, () => Slot(-1));
        Step("the next slot's code is accepted — skew the other way", 1, () => Slot(1));
        Step("a code two slots ahead is outside the window",          1, () => Slot(2));
        Step("a code two slots behind is outside the window",         1, () => Slot(-2));
        Step("a wrong code is Unknown",                               1, () => "000000000000");
        Step("an empty presentation is Malformed",                    1, () => "");
        Step("whitespace is Malformed too",                           1, () => "   ");
        Step("a code of the wrong length cannot match",               1, () => "abc");

        // Far enough ahead that everything spent above has been forgotten: the cache is bounded, and
        // a code that has left the window can be presented again without being called a replay —
        // it is simply Unknown by then, which is the honest answer.
        Step("after four slots the spent cache has been swept",     120, () => "000000000000");

        return steps;

    }


    /// <summary>The corpus against this implementation. What the Kotlin and Swift tests do, here.</summary>
    [Test]
    public void EveryVectorMatchesThisImplementation()
    {

        Assert.That(File.Exists(VectorPath), Is.True, $"corpus not found at {VectorPath}");

        using var document = JsonDocument.Parse(File.ReadAllText(VectorPath));
        var root = document.RootElement;

        Assert.That(root.GetProperty("secret").GetString(), Is.EqualTo(Secret));

        foreach (var slot in root.GetProperty("slots").EnumerateArray())
        {

            var name      = slot.GetProperty("name").GetString();
            var timestamp = DateTimeOffset.FromUnixTimeSeconds(slot.GetProperty("at").GetInt64());
            var window    = TimeSpan.FromSeconds(slot.GetProperty("validitySeconds").GetInt32());
            var length    = slot.GetProperty("length").GetUInt32();

            var (previous, current, next, remaining, _) =
                QRCodeTOTPGenerator.GenerateTOTPs(Secret, window, length, Timestamp: timestamp);

            Assert.Multiple(() => {
                Assert.That(previous,  Is.EqualTo(slot.GetProperty("previous").GetString()),  $"{name}: previous");
                Assert.That(current,   Is.EqualTo(slot.GetProperty("current").GetString()),   $"{name}: current");
                Assert.That(next,      Is.EqualTo(slot.GetProperty("next").GetString()),      $"{name}: next");
                Assert.That((Int32) remaining.TotalSeconds,
                            Is.EqualTo(slot.GetProperty("remainingSeconds").GetInt32()),      $"{name}: remaining");
                Assert.That(current.Length, Is.EqualTo((Int32) length),                       $"{name}: length");
            });

        }

    }


    /// <summary>
    /// The verifier script, replayed. Stateful, so it must run in order — which is exactly why it is a
    /// script and not a set of cases.
    /// </summary>
    [Test]
    public void TheVerifierScriptStillHolds()
    {

        using var document = JsonDocument.Parse(File.ReadAllText(VectorPath));
        var steps = document.RootElement.GetProperty("verifier").EnumerateArray().ToList();

        var clock    = new FakeClock(DateTimeOffset.FromUnixTimeSeconds(1_700_000_025));
        var verifier = new PairingTotpVerifier(Secret, TimeSpan.FromSeconds(30), clock);

        foreach (var step in steps)
        {

            var at = DateTimeOffset.FromUnixTimeSeconds(step.GetProperty("atUnixSeconds").GetInt64());
            clock.Advance(at - clock.GetUtcNow());

            var what      = step.GetProperty("what").GetString();
            var presented = step.GetProperty("presented").GetString()!;

            Assert.That(verifier.Verify(presented).ToString(),
                        Is.EqualTo(step.GetProperty("expected").GetString()), what);
            Assert.That(verifier.SpentCount,
                        Is.EqualTo(step.GetProperty("spentAfter").GetInt32()), $"{what} (spent count)");

        }

    }


    /// <summary>
    /// The script is only worth running if it still contains a replay and a window edge. A corpus that
    /// lost them would go on passing forever.
    /// </summary>
    [Test]
    public void TheScriptStillCoversReplayAndTheWindowEdges()
    {

        using var document = JsonDocument.Parse(File.ReadAllText(VectorPath));
        var outcomes = document.RootElement.GetProperty("verifier").EnumerateArray()
                           .Select(step => step.GetProperty("expected").GetString()).ToList();

        Assert.Multiple(() => {
            Assert.That(outcomes, Does.Contain("Accepted"));
            Assert.That(outcomes, Does.Contain("Replayed"), "a script without a replay proves nothing "
                                                          + "about the one-shot rule");
            Assert.That(outcomes, Does.Contain("Unknown"));
            Assert.That(outcomes, Does.Contain("Malformed"));
            Assert.That(outcomes.Count(o => o == "Accepted"), Is.EqualTo(3),
                        "previous, current and next — the ±1 window, no wider");
        });

    }

}


/// <summary>A clock that moves only when told to. The corpus would not be a corpus otherwise.</summary>
public sealed class FakeClock(DateTimeOffset Start) : TimeProvider
{
    private DateTimeOffset now = Start;

    public override DateTimeOffset GetUtcNow() => now;

    public void Advance(TimeSpan by) => now += by;
}
