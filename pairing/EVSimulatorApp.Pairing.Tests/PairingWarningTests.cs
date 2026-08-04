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
/// What a confirmation sheet must show in red.
/// </summary>
/// <remarks>
/// These matter more than the parser tests. A pairing code configures the session's security from a
/// photograph of a display, and "quishing" at public chargers is an established attack — so the
/// question is never "did it parse" but "what is this code asking me to give up, and does the user
/// get told".
/// </remarks>
[TestFixture]
public class PairingWarningTests
{
    private static PairingPayload Payload(
        string host = "192.168.4.1", PairingTransport tp = PairingTransport.Tls,
        string? crypto = PairingWarnings.ConformantCurve, bool nc = false, string? ncWhy = null,
        string? root = "aa", string? totp = "abc123XYZ789", string? psk = null) =>
        new()
        {
            Version = 1, Host = host, Port = 15118, Transport = tp, Crypto = crypto,
            NonConformant = nc, NonConformanceReason = ncWhy, RootFingerprint = root, Totp = totp,
            WifiSsid = psk is null ? null : "EVSim", WifiPsk = psk,
        };

    /// <summary>The well-formed, fully-declared case must be silent, or nothing else means anything.</summary>
    [Test]
    public void AConformantCodeWarnsAboutNothing()
    {
        Assert.That(Payload().Warnings, Is.Empty);
    }

    [Test]
    public void PlaintextTransportIsLoud()
    {
        Assert.That(Payload(tp: PairingTransport.Tcp).Warnings.Select(w => w.Kind),
                    Does.Contain(PairingWarningKind.PlaintextTransport));
    }

    /// <summary>
    /// Silence about the crypto profile is *not* conformance. This is the easiest thing for a
    /// hostile code to offer — say nothing, and let the reader assume the default is fine.
    /// </summary>
    [Test]
    public void AnUnstatedCryptoProfileWarnsJustLikeAWeakenedOne()
    {
        Assert.Multiple(() =>
        {
            Assert.That(Payload(crypto: null).Warnings.Select(w => w.Kind),
                        Does.Contain(PairingWarningKind.WeakenedCrypto));
            Assert.That(Payload(crypto: "secp256r1").Warnings.Select(w => w.Kind),
                        Does.Contain(PairingWarningKind.WeakenedCrypto));
        });
    }

    /// <summary>
    /// The peer's stated reason is shown **verbatim**. That is the mechanism by which a relaxed
    /// session becomes relaxed *because the counterpart asked for it, on the record* (§3.3, §4.5) —
    /// so the text must reach the sheet unedited, and the sheet must render it as untrusted text.
    /// </summary>
    [Test]
    public void TheNonConformanceReasonIsCarriedWordForWord()
    {
        const string why = "Schannel cannot pin suites; <b>demo unit</b>";

        var warning = Payload(nc: true, ncWhy: why).Warnings
                          .Single(w => w.Kind is PairingWarningKind.DeclaredNonConformance);

        Assert.That(warning.Detail, Is.EqualTo(why));
    }

    [Test]
    public void ADeclaredNonConformanceWithoutAReasonStillWarns()
    {
        var warning = Payload(nc: true, ncWhy: null).Warnings
                          .Single(w => w.Kind is PairingWarningKind.DeclaredNonConformance);

        Assert.That(warning.Detail, Does.Contain("no reason"));
    }

    [TestCase("192.168.4.1")]
    [TestCase("10.1.2.3")]
    [TestCase("172.16.0.1")]
    [TestCase("169.254.7.7")]
    [TestCase("127.0.0.1")]
    [TestCase("fe80::1%wlan0")]
    [TestCase("fd00::1")]
    [TestCase("::1")]
    [TestCase("evsim-pi.local")]
    public void PlausibleCounterpartsAreAccepted(string host)
    {
        Assert.That(PairingWarnings.IsPrivateTarget(host), Is.True, host);
    }

    /// <summary>
    /// The intended counterpart is a Pi on your own network, and real chargers are out of scope
    /// (§8 #5). A code pointing anywhere else is suspicious by construction and blocks.
    /// </summary>
    [TestCase("8.8.8.8")]
    [TestCase("172.32.0.1")]      // just outside 172.16/12
    [TestCase("192.169.0.1")]     // just outside 192.168/16
    [TestCase("2001:db8::1")]
    [TestCase("evil.example.com")]
    public void EverythingElseIsAPublicTarget(string host)
    {
        Assert.Multiple(() =>
        {
            Assert.That(PairingWarnings.IsPrivateTarget(host), Is.False, host);
            Assert.That(Payload(host: host).Warnings.Single(w => w.Kind is PairingWarningKind.PublicTarget)
                                           .IsBlocking, Is.True);
        });
    }

    /// <summary>
    /// A hostname is judged, never resolved. Resolving would mean a DNS query on behalf of a code
    /// the user has not yet approved — a callback to the attacker before anyone has agreed to
    /// anything.
    /// </summary>
    [Test]
    public void AHostnameIsNotResolvedToDecide()
    {
        // localhost resolves to a loopback address, and is still treated as public: the decision
        // is made on the text, so no lookup happens.
        Assert.That(PairingWarnings.IsPrivateTarget("localhost"), Is.False);
    }

    [Test]
    public void AStaticCodeSaysSoBecauseAPhotographOfItWorksForever()
    {
        Assert.That(Payload(totp: null).Warnings.Select(w => w.Kind),
                    Does.Contain(PairingWarningKind.NoProximityProof));
    }

    [Test]
    public void AMissingTrustAnchorWarns()
    {
        Assert.That(Payload(root: null).Warnings.Select(w => w.Kind),
                    Does.Contain(PairingWarningKind.NoTrustAnchor));
    }

    /// <summary>Joining stores the password on the device, so the scan is worth mentioning.</summary>
    [Test]
    public void AWifiPasswordInTheCodeIsAnnounced()
    {
        Assert.That(Payload(psk: "hunter2").Warnings.Select(w => w.Kind),
                    Does.Contain(PairingWarningKind.CarriesWifiCredentials));
    }

    /// <summary>
    /// An unreadable version blocks. Fields could mean something different in a version we cannot
    /// read, and guessing at the meaning of a security profile is the one thing not to do.
    /// </summary>
    [Test]
    public void AnUnknownVersionBlocks()
    {
        var payload = Payload() with { Version = 2 };

        Assert.That(payload.Warnings.Single(w => w.Kind is PairingWarningKind.UnsupportedVersion)
                           .IsBlocking, Is.True);
    }

    /// <summary>
    /// The worst realistic sticker: plaintext, no trust anchor, no proximity proof, a public target.
    /// Every one of those has to surface — a sheet that shows only the first would let the rest
    /// through.
    /// </summary>
    [Test]
    public void AHostileCodeSurfacesEveryProblemAtOnce()
    {
        var payload = Payload(host: "evil.example.com", tp: PairingTransport.Tcp,
                              crypto: null, root: null, totp: null);

        Assert.That(payload.Warnings.Select(w => w.Kind), Is.EquivalentTo(new[]
        {
            PairingWarningKind.PlaintextTransport,
            PairingWarningKind.WeakenedCrypto,
            PairingWarningKind.PublicTarget,
            PairingWarningKind.NoTrustAnchor,
            PairingWarningKind.NoProximityProof,
        }));
    }
}
