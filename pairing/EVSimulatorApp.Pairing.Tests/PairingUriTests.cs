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

using EVSimulatorApp.Pairing;
using NUnit.Framework;

namespace EVSimulatorApp.Pairing.Tests;

/// <summary>
/// The pairing payload format — the one thing the Pi and the app must agree on exactly, and the two
/// halves never run in the same process (<c>docs/CONCEPT.md</c> §4.5, §8 #12).
/// </summary>
[TestFixture]
public class PairingUriTests
{
    private const string Minimal = "https://open.charging.cloud/evsim/pair#v=1&host=fe80::1%25wlan0&port=15118";

    [Test]
    public void AMinimalCodeParses()
    {
        var p = PairingUri.Parse(Minimal)!;

        Assert.Multiple(() =>
        {
            Assert.That(p.Version, Is.EqualTo(1));
            Assert.That(p.Host, Is.EqualTo("fe80::1%wlan0"));
            Assert.That(p.Port, Is.EqualTo(15118));
            // Absent `tp` means TLS. The default has to be the safe one: a code that forgets to say
            // must not thereby get plaintext.
            Assert.That(p.Transport, Is.EqualTo(PairingTransport.Tls));
        });
    }

    [Test]
    public void EveryFieldRoundTrips()
    {
        var original = new PairingPayload
        {
            Version = 1, Host = "192.168.4.1", Port = 15118, Transport = PairingTransport.Tcp,
            Protocols = ["iso20", "iso2"], Crypto = "secp256r1",
            NonConformant = true, NonConformanceReason = "Schannel has no P-521 & no 1.3 pinning",
            RootFingerprint = new string('a', 64), Meter = "MK1", Totp = "abc123XYZ789",
            EvseId = "DE*GEF*E12345678*1", TariffId = "T1", Currency = "EUR", UiLanguage = "de",
            WifiSsid = "EVSim:Pi", WifiPsk = "hunter2",
            Extra = new Dictionary<string, string> { ["maxSoC"] = "80" },
        };

        var reparsed = PairingUri.Parse(PairingUri.Format(original))!;

        Assert.That(reparsed, Is.EqualTo(original));
    }

    /// <summary>
    /// The security property the whole format rests on: **the data lives in the fragment**, which is
    /// never sent to a server. A code with its parameters in the query must not work by accident,
    /// or every scan would be handed to whoever runs the host.
    /// </summary>
    [Test]
    public void ParametersInTheQueryAreNotRead()
    {
        Assert.Throws<PairingFormatException>(() =>
            PairingUri.Parse("https://open.charging.cloud/evsim/pair?v=1&host=192.168.4.1&port=15118"));
    }

    [Test]
    public void TheCustomSchemeIsAnAlias()
    {
        var p = PairingUri.Parse("v2gsim://pair#v=1&host=192.168.4.1&port=15118");

        Assert.That(p?.Host, Is.EqualTo("192.168.4.1"));
    }

    /// <summary>Some other QR code is a shrug; a broken pairing code is worth reporting.</summary>
    [Test]
    public void SomethingElseEntirelyIsNotAPairingCode()
    {
        Assert.Multiple(() =>
        {
            Assert.That(PairingUri.Parse("https://example.com/"), Is.Null);
            Assert.That(PairingUri.Parse("just some text"), Is.Null);
            Assert.That(PairingUri.Parse("WIFI:S:home;T:WPA;P:secret;;"), Is.Null);
        });
    }

    [TestCase("#v=1&host=x", "port")]
    [TestCase("#v=1&port=1", "host")]
    [TestCase("#host=x&port=1", "v")]
    public void AMissingRequiredParameterIsNamed(string fragment, string expected)
    {
        var ex = Assert.Throws<PairingFormatException>(() =>
            PairingUri.Parse("https://open.charging.cloud/evsim/pair" + fragment));

        Assert.That(ex!.Message, Does.Contain(expected));
    }

    [TestCase("v=1&host=x&port=notanumber")]
    [TestCase("v=1&host=x&port=99999")]
    [TestCase("v=1&host=x&port=0")]
    [TestCase("v=x&host=x&port=1")]
    [TestCase("v=1&host=x&port=1&tp=carrierpigeon")]
    [TestCase("v=1&host=x&port=1&novalue")]
    public void MalformedValuesAreRefused(string fragment)
    {
        Assert.Throws<PairingFormatException>(() =>
            PairingUri.Parse("https://open.charging.cloud/evsim/pair#" + fragment));
    }

    /// <summary>
    /// A repeated parameter is refused rather than resolved, because "first wins" and "last wins"
    /// are both defensible and a hostile code only needs the reader and the connector to disagree
    /// about which.
    /// </summary>
    [Test]
    public void ARepeatedParameterIsRefused()
    {
        var ex = Assert.Throws<PairingFormatException>(() =>
            PairingUri.Parse("https://open.charging.cloud/evsim/pair#v=1&host=192.168.4.1&port=1&host=evil.example.com"));

        Assert.That(ex!.Message, Does.Contain("host").And.Contain("more than once"));
    }

    /// <summary>
    /// Unknown parameters are carried, not dropped and not rejected: a newer Pi must be able to talk
    /// to an older app, and an app that silently discards what it cannot read also cannot warn about
    /// it. Nothing interprets them, which is what makes carrying them safe.
    /// </summary>
    [Test]
    public void UnknownParametersSurviveAndAreAnnounced()
    {
        var p = PairingUri.Parse(Minimal + "&maxEnergy=50&somethingNew=x")!;

        Assert.Multiple(() =>
        {
            Assert.That(p.Extra, Is.EquivalentTo(new Dictionary<string, string>
                                                 { ["maxEnergy"] = "50", ["somethingNew"] = "x" }));
            Assert.That(p.Warnings.Select(w => w.Kind),
                        Does.Contain(PairingWarningKind.UnknownParameters));
        });
    }

    [Test]
    public void AWifiPasswordContainingAColonSurvives()
    {
        var p = PairingUri.Parse(PairingUri.Format(new PairingPayload
        {
            Version = 1, Host = "192.168.4.1", Port = 1, Transport = PairingTransport.Tls,
            WifiSsid = "EVSim", WifiPsk = "a:b\\c",
        }))!;

        Assert.Multiple(() =>
        {
            Assert.That(p.WifiSsid, Is.EqualTo("EVSim"));
            Assert.That(p.WifiPsk, Is.EqualTo("a:b\\c"));
        });
    }
}

/// <summary>
/// Equality on <see cref="PairingPayload"/>, which a `record` does not give correctly here.
/// </summary>
/// <remarks>
/// The synthesised `Equals` compares the collection members by reference, so two payloads parsed
/// from the same string came back unequal — caught by the round-trip test above, and worth its own
/// tests because the failure is silent and intermittent-looking. A pairing flow will want to ask
/// "is this the code the user already approved?", and a wrong answer there re-prompts forever or,
/// worse, treats a different code as the approved one.
/// </remarks>
[TestFixture]
public class PairingPayloadEqualityTests
{
    private static PairingPayload Parse(string extra = "") =>
        PairingUri.Parse("https://open.charging.cloud/evsim/pair"
                       + "#v=1&host=192.168.4.1&port=15118&proto=iso2,iso20" + extra)!;

    [Test]
    public void TwoParsesOfTheSameCodeAreEqual()
    {
        Assert.Multiple(() =>
        {
            Assert.That(Parse(), Is.EqualTo(Parse()));
            Assert.That(Parse().GetHashCode(), Is.EqualTo(Parse().GetHashCode()));
        });
    }

    [Test]
    public void DifferencesInsideTheCollectionsAreSeen()
    {
        Assert.Multiple(() =>
        {
            Assert.That(Parse(), Is.Not.EqualTo(Parse() with { Protocols = ["iso20", "iso2"] }));
            Assert.That(Parse("&x=1"), Is.Not.EqualTo(Parse("&x=2")));
            Assert.That(Parse("&x=1"), Is.Not.EqualTo(Parse()));
        });
    }

    /// <summary>Two codes carrying the same extras in a different order are the same code.</summary>
    [Test]
    public void ExtraParameterOrderDoesNotMatter()
    {
        Assert.Multiple(() =>
        {
            Assert.That(Parse("&a=1&b=2"), Is.EqualTo(Parse("&b=2&a=1")));
            Assert.That(Parse("&a=1&b=2").GetHashCode(), Is.EqualTo(Parse("&b=2&a=1").GetHashCode()));
        });
    }
}
