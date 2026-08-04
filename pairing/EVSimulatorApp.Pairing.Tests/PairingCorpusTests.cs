/*
 * Copyright (c) 2021-2026 GraphDefined GmbH <achim.friedland@graphdefined.com>
 * This file is part of WWCP ISO/IEC 15118 <https://github.com/OpenChargingCloud/WWCP_ISO15118>
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

using NUnit.Framework;

using System.Text;
using System.Text.Json;

namespace EVSimulatorApp.Pairing.Tests;

/// <summary>
/// The pairing-payload corpus: scanned strings and what a parser must make of each.
/// </summary>
/// <remarks>
/// <para>
/// The app's QR half has to exist in Kotlin and Swift, and the payload format is the one thing the
/// Pi and the phone must agree on <b>exactly</b> — they are the two halves that never run in the
/// same process. So the rules travel as data rather than as three readings of a README.
/// </para>
/// <para>
/// <b>The interesting cases are the refusals.</b> A pairing code is an image anyone can tape over a
/// display, so most of what this file pins is what must be rejected or reported: data in the query
/// rather than the fragment, a repeated parameter, a public target, silence about the crypto
/// profile. A port that parses the happy case and shrugs at those has not implemented the format.
/// </para>
/// <para>
/// Warning kinds travel as lower-camel strings rather than as C# enum names, for the same reason the
/// revocation reason and the root fingerprint do: they end up in front of a user, and a spelling
/// that differs per back end is a difference nothing catches until someone compares two screens.
/// </para>
/// </remarks>
[TestFixture]
public class PairingCorpusTests
{

    private const string FileName = "Pairing.payload.vectors.json";

    private static string VectorPath =>
        Path.Combine(TestContext.CurrentContext.TestDirectory, "Vectors", FileName);

    private static string SourcePath()
    {
        var dir = new DirectoryInfo(TestContext.CurrentContext.TestDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "EVSimulatorApp.Pairing.Tests.csproj")))
            dir = dir.Parent;

        Assert.That(dir, Is.Not.Null, "could not find the test project directory");
        var vectors = Path.Combine(dir!.FullName, "Vectors");
        Directory.CreateDirectory(vectors);
        return Path.Combine(vectors, FileName);
    }

    /// <summary>PascalCase enum name → the lower-camel spelling every back end reports.</summary>
    private static string Spelling(PairingWarningKind kind) =>
        char.ToLowerInvariant(kind.ToString()[0]) + kind.ToString()[1..];


    private const string Base = "https://open.charging.cloud/evsim/pair";

    private static readonly (string Name, string Input, string What)[] Inputs =
    [
        ("minimal",
         $"{Base}#v=1&host=192.168.4.1&port=15118",
         "The least a code can carry. Note what it still warns about: no crypto profile stated, no "
       + "trust anchor, no proximity proof — silence is not conformance."),

        ("full",
         $"{Base}#v=1&host=fe80::1%25wlan0&port=15118&tp=tls&crypto=secp521r1&proto=iso2,iso20"
       + "&root=ab12&meter=key&totp=123456789012&evseId=DE*ABC*E1&tariffId=T1&currency=EUR&uiLanguage=de",
         "Everything understood, conformant, link-local. The only shape that warns about nothing."),

        ("alt-scheme",
         $"v2gsim://pair#v=1&host=192.168.4.1&port=15118&crypto=secp521r1&root=ab&totp=1",
         "The in-app scheme is an alias, and parses identically."),

        ("plaintext",
         $"{Base}#v=1&host=192.168.4.1&port=15118&tp=tcp&crypto=secp521r1&root=ab&totp=1",
         "Plaintext TCP: reported, not refused. The session simply will not be encrypted, and a "
       + "human decides whether that is acceptable."),

        ("weakened-crypto",
         $"{Base}#v=1&host=192.168.4.1&port=15118&crypto=secp256r1&root=ab&totp=1",
         "A curve below the -20 conformant profile."),

        ("declared-non-conformance",
         $"{Base}#v=1&host=192.168.4.1&port=15118&crypto=secp521r1&root=ab&totp=1&nc=1"
       + "&ncwhy=lab%20unit%2C%20no%20HSM",
         "The peer says so itself, and its reason is carried verbatim — that is the whole mechanism: "
       + "a relaxed session is relaxed because the counterpart asked, on the record."),

        ("public-target",
         $"{Base}#v=1&host=203.0.113.9&port=15118&crypto=secp521r1&root=ab&totp=1",
         "A routable address. Blocking: the intended counterpart is a Pi on your own network."),

        ("hostname-is-not-resolved",
         $"{Base}#v=1&host=localhost&port=15118&crypto=secp521r1&root=ab&totp=1",
         "'localhost' counts as PUBLIC. Resolving a name would mean a DNS query on behalf of a code "
       + "nobody has approved yet — a callback to whoever made it. The decision is made on the text, "
       + "so anything that is not an address or .local is treated as public."),

        ("mdns-name",
         $"{Base}#v=1&host=evsim-pi.local&port=15118&crypto=secp521r1&root=ab&totp=1",
         "…and .local is the exception, because it cannot leave the link."),

        ("wifi-credentials",
         $"{Base}#v=1&host=192.168.4.1&port=15118&crypto=secp521r1&root=ab&totp=1&wifi=EVSim%3Ahunter2",
         "Joining stores a password on the device, so the code says it carries one."),

        ("unknown-parameters",
         $"{Base}#v=1&host=192.168.4.1&port=15118&crypto=secp521r1&root=ab&totp=1&futureThing=x",
         "Carried, never interpreted, and reported. An app that silently discarded it could not warn "
       + "that the code contained something it could not read."),

        ("unsupported-version",
         $"{Base}#v=2&host=192.168.4.1&port=15118&crypto=secp521r1&root=ab&totp=1",
         "Blocking: in a version this build cannot read, the fields it thinks it understands may not "
       + "mean what it thinks."),

        // ── not pairing codes at all ──────────────────────────────────────
        ("some-other-qr",       "https://example.com/", "Not a pairing code: a shrug, not an error."),
        ("plain-text",          "hello world",          "Not even a URI."),

        // ── pairing codes that are broken ─────────────────────────────────
        ("query-instead-of-fragment",
         $"{Base}?v=1&host=192.168.4.1&port=15118",
         "THE ONE THAT MATTERS MOST. Parameters in the query are not read, because a query is sent "
       + "to the server: a format that worked either way would hand every scan to whoever runs the "
       + "host. Malformed, not merely warned about."),

        ("repeated-parameter",
         $"{Base}#v=1&host=192.168.4.1&host=10.0.0.1&port=15118",
         "Refused rather than resolved. 'First wins' and 'last wins' are both defensible, and an "
       + "attacker needs only the sheet and the connector to disagree about which."),

        ("missing-host",        $"{Base}#v=1&port=15118",                    "A required field is absent."),
        ("port-not-a-number",   $"{Base}#v=1&host=192.168.4.1&port=abc",     "Not a port."),
        ("port-out-of-range",   $"{Base}#v=1&host=192.168.4.1&port=70000",   "Not a port either."),
        ("unknown-transport",   $"{Base}#v=1&host=192.168.4.1&port=15118&tp=quic", "An unknown transport is refused, not defaulted."),
        ("not-a-pair",          $"{Base}#v=1&host&port=15118",               "A fragment entry that is not key=value."),
        ("empty-fragment",      $"{Base}#",                                  "A pairing code with nothing in it."),
    ];


    /// <summary>
    /// Writes the corpus. <see cref="ExplicitAttribute"/> like every other generator here: it is an
    /// oracle for two other languages and must change deliberately.
    /// </summary>
    [Test, Explicit("Regenerates Vectors/Pairing.payload.vectors.json — run deliberately")]
    public void RegenerateTheCorpus()
    {

        var cases = Inputs.Select(input =>
        {
            PairingPayload? payload = null;
            string outcome;
            string? error = null;

            try
            {
                payload = PairingUri.Parse(input.Input);
                outcome = payload is null ? "notAPairingCode" : "parsed";
            }
            catch (PairingFormatException e)
            {
                outcome = "malformed";
                error   = e.Message;
            }

            return new
            {
                name  = input.Name,
                what  = input.What,
                input = input.Input,
                outcome,
                error,
                payload = payload is null ? null : new
                {
                    version    = payload.Version,
                    host       = payload.Host,
                    port       = payload.Port,
                    transport  = payload.Transport.ToString().ToLowerInvariant(),
                    protocols  = payload.Protocols,
                    crypto     = payload.Crypto,
                    nonConformant        = payload.NonConformant,
                    nonConformanceReason = payload.NonConformanceReason,
                    rootFingerprint      = payload.RootFingerprint,
                    meter      = payload.Meter,
                    totp       = payload.Totp,
                    evseId     = payload.EvseId,
                    tariffId   = payload.TariffId,
                    currency   = payload.Currency,
                    uiLanguage = payload.UiLanguage,
                    wifiSsid   = payload.WifiSsid,
                    wifiPsk    = payload.WifiPsk,
                    extra      = payload.Extra,
                },
                warnings = payload is null
                    ? []
                    : PairingWarnings.For(payload)
                                     .Select(w => new { kind = Spelling(w.Kind), blocking = w.IsBlocking })
                                     .ToArray(),
            };
        }).ToArray();

        var json = JsonSerializer.Serialize(new
        {
            note = "Scanned pairing codes and what a parser must make of each. The format is the one "
                 + "thing the Pi and the phone must agree on exactly, and they never run in the same "
                 + "process. Most of what is pinned here is refusals: a pairing code is an image "
                 + "anyone can tape over a display. 'outcome' is parsed / notAPairingCode / "
                 + "malformed, and the distinction between the last two matters at the scanner — one "
                 + "is a shrug, the other is worth telling the user about.",
            cases,
        }, new JsonSerializerOptions { WriteIndented = true });

        File.WriteAllText(SourcePath(), json, new UTF8Encoding(false));
        TestContext.Out.WriteLine($"wrote {cases.Length} cases to {SourcePath()}");

    }


    /// <summary>The corpus still describes what this implementation does.</summary>
    [Test]
    public void EveryCaseMatchesThisImplementation()
    {

        Assert.That(File.Exists(VectorPath), Is.True, $"corpus missing: {VectorPath}");

        var corpus = JsonDocument.Parse(File.ReadAllText(VectorPath)).RootElement;
        var failures = new List<string>();

        foreach (var c in corpus.GetProperty("cases").EnumerateArray())
        {
            var name  = c.GetProperty("name").GetString()!;
            var input = c.GetProperty("input").GetString()!;

            string outcome;
            PairingPayload? payload = null;
            try
            {
                payload = PairingUri.Parse(input);
                outcome = payload is null ? "notAPairingCode" : "parsed";
            }
            catch (PairingFormatException) { outcome = "malformed"; }

            if (outcome != c.GetProperty("outcome").GetString())
                failures.Add($"{name}: outcome is {outcome}, corpus says {c.GetProperty("outcome").GetString()}");

            if (payload is not null)
            {
                var actual = PairingWarnings.For(payload).Select(w => Spelling(w.Kind)).Order().ToList();
                var expected = c.GetProperty("warnings").EnumerateArray()
                                .Select(w => w.GetProperty("kind").GetString()!).Order().ToList();

                if (!actual.SequenceEqual(expected))
                    failures.Add($"{name}: warnings {string.Join(",", actual)} vs {string.Join(",", expected)}");
            }
        }

        Assert.That(failures, Is.Empty, string.Join("\n", failures));

    }


    /// <summary>
    /// The corpus covers the refusals, not merely the happy path. A regeneration that quietly dropped
    /// them would leave a green suite and a format nobody checks.
    /// </summary>
    [Test]
    public void TheCorpusCoversTheRefusals()
    {

        var cases = JsonDocument.Parse(File.ReadAllText(VectorPath)).RootElement
                                .GetProperty("cases").EnumerateArray().ToList();

        var names = cases.Select(c => c.GetProperty("name").GetString()).ToList();

        Assert.Multiple(() =>
        {
            Assert.That(names, Does.Contain("query-instead-of-fragment"),
                        "the single most important rule in the format");
            Assert.That(names, Does.Contain("repeated-parameter"));
            Assert.That(names, Does.Contain("hostname-is-not-resolved"));
            Assert.That(names, Does.Contain("public-target"));

            Assert.That(cases.Count(c => c.GetProperty("outcome").GetString() == "malformed"),
                        Is.GreaterThanOrEqualTo(6), "a format is defined as much by what it refuses");
            Assert.That(cases.Any(c => c.GetProperty("outcome").GetString() == "notAPairingCode"),
                        "'not a pairing code' is a distinct outcome and needs a case");

            // The minimal code must warn about all three silences, or "silence is not conformance"
            // is only a sentence in a README.
            var minimal = cases.Single(c => c.GetProperty("name").GetString() == "minimal");
            var kinds = minimal.GetProperty("warnings").EnumerateArray()
                               .Select(w => w.GetProperty("kind").GetString()).ToList();
            Assert.That(kinds, Does.Contain("weakenedCrypto"));
            Assert.That(kinds, Does.Contain("noTrustAnchor"));
            Assert.That(kinds, Does.Contain("noProximityProof"));
        });

    }

}
