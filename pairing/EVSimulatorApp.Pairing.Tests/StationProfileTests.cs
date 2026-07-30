using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

using EVSimulatorApp.Pairing;
using EVSimulatorApp.Pi;
using NUnit.Framework;

namespace EVSimulatorApp.Pairing.Tests;

/// <summary>
/// One profile, two projections — the listener's transport and the display's declaration.
/// </summary>
/// <remarks>
/// This type exists to make a class of bug impossible rather than unlikely: a display advertising
/// TLS beside a station listening in plaintext is worse than no display, because it is believed.
/// So the tests that matter here are the ones that compare the two projections against each other.
/// </remarks>
[TestFixture]
public class StationProfileTests
{
    private static X509Certificate2 Certificate()
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest("CN=SECC", key, HashAlgorithmName.SHA256);
        return request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));
    }

    private static StationProfile Profile(int protocol, string transport) =>
        StationProfile.Parse(protocol, transport, transport is "tls" ? Certificate() : null);

    /// <summary>The property the type exists for.</summary>
    [TestCase(2, "tcp")]
    [TestCase(2, "tls")]
    [TestCase(20, "tcp")]
    [TestCase(20, "tls")]
    public void WhatIsDeclaredIsWhatIsRun(int protocol, string transport)
    {
        var profile = Profile(protocol, transport);

        var declared = profile.Declare("192.168.4.1", 15118);
        var running  = profile.ToTlsOptions();

        Assert.Multiple(() =>
        {
            Assert.That(declared.Transport is PairingTransport.Tls, Is.EqualTo(running is not null),
                        "the display and the listener disagree about TLS");
            Assert.That(declared.Protocols, Is.EqualTo(new[] { protocol is 20 ? "iso20" : "iso2" }));
        });
    }

    /// <summary>
    /// -2 ↔ TLS 1.2 and -20 ↔ TLS 1.3, pinned together rather than offered as independent knobs.
    /// "ISO 15118-2 over TLS 1.3" is possible, unsupported by ESDP and a documented interop hazard
    /// (<c>docs/pki-model.md</c>), so it must not be reachable by configuration.
    /// </summary>
    [TestCase(2, SslProtocols.Tls12)]
    [TestCase(20, SslProtocols.Tls13)]
    public void TheTlsVersionFollowsTheProtocol(int protocol, SslProtocols expected)
    {
        Assert.That(Profile(protocol, "tls").ToTlsOptions()!.EnabledSslProtocols, Is.EqualTo(expected));
    }

    [TestCase(2)]
    [TestCase(20)]
    public void TheCipherSuitesFollowTheProtocol(int protocol)
    {
        var suites = Profile(protocol, "tls").ToTlsOptions()!.CipherSuites;

        Assert.That(suites, Is.EqualTo(protocol is 20 ? TlsProfilesFor20 : TlsProfilesFor2));
    }

    private static IReadOnlyList<System.Net.Security.TlsCipherSuite> TlsProfilesFor2 =>
        Vanaheimr.V2G.Simulation.Transport.TlsProfiles.Iso2CipherSuites;
    private static IReadOnlyList<System.Net.Security.TlsCipherSuite> TlsProfilesFor20 =>
        Vanaheimr.V2G.Simulation.Transport.TlsProfiles.Iso20CipherSuites;

    /// <summary>A plaintext station declares plaintext, and the app then warns about it loudly.</summary>
    [Test]
    public void APlaintextStationDeclaresItAndTheAppWarns()
    {
        var declared = Profile(2, "tcp").Declare("192.168.4.1", 15118);

        Assert.That(declared.Warnings.Select(w => w.Kind),
                    Does.Contain(PairingWarningKind.PlaintextTransport));
    }

    /// <summary>
    /// A -2 station states <c>secp256r1</c> rather than leaving the field empty. The app reads an
    /// absent curve as "unstated" and warns about it exactly as it warns about a weakened one, so
    /// saying nothing would be indistinguishable from hiding something.
    /// </summary>
    [Test]
    public void TheCurveIsStatedRatherThanLeftEmpty()
    {
        Assert.Multiple(() =>
        {
            Assert.That(Profile(2, "tls").Declare("192.168.4.1", 15118).Crypto, Is.EqualTo("secp256r1"));
            Assert.That(Profile(20, "tls").Declare("192.168.4.1", 15118).Crypto,
                        Is.EqualTo(PairingWarnings.ConformantCurve));
        });
    }

    /// <summary>TLS without a certificate is refused at startup, not discovered at the first accept.</summary>
    [Test]
    public void TlsWithoutACertificateIsRefused()
    {
        Assert.Throws<ArgumentException>(() => StationProfile.Parse(20, "tls", null));
    }

    [TestCase(3, "tcp")]
    [TestCase(2, "carrierpigeon")]
    public void NonsenseIsRefused(int protocol, string transport)
    {
        Assert.Throws(Is.InstanceOf<ArgumentException>(),
                      () => StationProfile.Parse(protocol, transport, null));
    }

    /// <summary>Mutual TLS is a -20 notion; asking for it on -2 does not silently enable it.</summary>
    [Test]
    public void ClientCertificatesAreA20Notion()
    {
        var two    = Profile(2, "tls") with { RequireClientCertificate = true };
        var twenty = Profile(20, "tls") with { RequireClientCertificate = true };

        Assert.Multiple(() =>
        {
            Assert.That(two.ToTlsOptions()!.RequireClientCertificate, Is.False);
            Assert.That(twenty.ToTlsOptions()!.RequireClientCertificate, Is.True);
        });
    }
}
