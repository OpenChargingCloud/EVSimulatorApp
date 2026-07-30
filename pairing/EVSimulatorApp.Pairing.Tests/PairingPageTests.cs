using System.Text.Json;

using EVSimulatorApp.Pairing;
using NUnit.Framework;

namespace EVSimulatorApp.Pairing.Tests;

/// <summary>
/// The Pi's pairing display.
/// </summary>
/// <remarks>
/// The risk this page carries is not that it looks wrong. It is that <b>what it shows drifts from
/// what the station is actually doing</b> — a display advertising TLS beside a station listening in
/// plaintext is worse than no display, because it is believed. So most of these tests are about the
/// page being a function of live configuration, and about what must never reach it.
/// </remarks>
[TestFixture]
public class PairingPageTests
{
    private static PairingPayload Payload(
        PairingTransport tp = PairingTransport.Tls, string? crypto = "secp521r1",
        bool nc = false, string? ncWhy = null) =>
        new()
        {
            Version = 1, Host = "192.168.4.1", Port = 15118, Transport = tp, Crypto = crypto,
            Protocols = ["iso2", "iso20"], NonConformant = nc, NonConformanceReason = ncWhy,
            RootFingerprint = new string('a', 64), EvseId = "DE*GEF*E12345678*1",
        };

    private static string Page(PairingPayload? p = null, string? totp = "abc123XYZ789") =>
        PairingPage.Render(p ?? Payload(), totp, TimeSpan.FromSeconds(23));

    /// <summary>
    /// The whole point of generating the page on the Pi: the code it shows is the code the station's
    /// own configuration produces, not one written next to it by hand.
    /// </summary>
    /// <remarks>
    /// Asserted against the <em>JSON literal</em> the QR renderer is handed, not against the raw
    /// URI. The first version of this test looked for the plain string and failed — correctly, as it
    /// turned out: the URI is HTML-escaped where it is displayed and JSON-escaped where it is
    /// scripted, and neither is the raw form. What matters is that the QR gets the right bytes.
    /// </remarks>
    [Test]
    public void TheQrIsGivenTheUriTheConfigurationProduces()
    {
        var payload = Payload();
        var expected = PairingUri.Format(payload with { Totp = "abc123XYZ789" });

        Assert.That(Page(payload), Does.Contain(JsonSerializer.Serialize(expected)));
    }

    /// <summary>And it round-trips: what a scanner reads back is what the station configured.</summary>
    [Test]
    public void WhatIsRenderedParsesBackToWhatWasConfigured()
    {
        var payload = Payload();
        var page = Page(payload);

        // Take the URI out of the page itself rather than rebuilding it, so this cannot pass by
        // agreeing with the renderer about a value neither of them got from the configuration.
        var at = page.IndexOf("msg: \"", StringComparison.Ordinal) + "msg: ".Length;
        var literal = page[at..(page.IndexOf(" }", at, StringComparison.Ordinal))];
        var reparsed = PairingUri.Parse(JsonSerializer.Deserialize<string>(literal)!)!;

        Assert.Multiple(() =>
        {
            Assert.That(reparsed.Host, Is.EqualTo(payload.Host));
            Assert.That(reparsed.Port, Is.EqualTo(payload.Port));
            Assert.That(reparsed.Transport, Is.EqualTo(payload.Transport));
            Assert.That(reparsed.Crypto, Is.EqualTo(payload.Crypto));
            Assert.That(reparsed.RootFingerprint, Is.EqualTo(payload.RootFingerprint));
            Assert.That(reparsed.Totp, Is.EqualTo("abc123XYZ789"));
        });
    }

    /// <summary>
    /// The station's warnings about itself belong on its own display, not only on the phone. The
    /// operator standing in front of the Pi is the person who can fix a weakened profile.
    /// </summary>
    [Test]
    public void AWeakenedStationSaysSoOnItsOwnDisplay()
    {
        var page = Page(Payload(tp: PairingTransport.Tcp, crypto: "secp256r1"));

        Assert.Multiple(() =>
        {
            Assert.That(page, Does.Contain("PlaintextTransport"));
            Assert.That(page, Does.Contain("WeakenedCrypto"));
            Assert.That(page, Does.Contain("TCP (plaintext)"));
        });
    }

    /// <summary>
    /// A rotating display must not accuse itself of being static. The proximity-proof warning is the
    /// one that depends on how the page is being used rather than on configuration.
    /// </summary>
    [Test]
    public void ARotatingDisplayDoesNotWarnAboutBeingStatic()
    {
        Assert.Multiple(() =>
        {
            Assert.That(Page(totp: "abc123XYZ789"), Does.Not.Contain("NoProximityProof"));
            Assert.That(Page(totp: null), Does.Contain("NoProximityProof"));
        });
    }

    // ── What must never reach the page ──────────────────────────────────────────────────────────

    /// <summary>
    /// **The shared secret is never rendered.** Only the derived code is. A display that leaked the
    /// secret would turn a proximity proof into a permanent credential for anyone who ever saw the
    /// screen — the one failure that would silently undo the entire §4.6 mechanism.
    /// </summary>
    [Test]
    public void TheSharedSecretNeverAppears()
    {
        const string secret = "a-shared-secret-of-sufficient-length";
        var verifier = new PairingTotpVerifier(secret);
        var (totp, remaining) = verifier.Current();

        var page = PairingPage.Render(Payload(), totp, remaining);

        Assert.Multiple(() =>
        {
            Assert.That(page, Does.Not.Contain(secret));
            Assert.That(page, Does.Contain(totp), "the derived code is what belongs on screen");
        });
    }

    /// <summary>
    /// Configuration text is escaped. The non-conformance reason is written by whoever set the
    /// station up, and a status page that renders it as markup is an injection hole on the one
    /// screen an operator is meant to trust.
    /// </summary>
    [Test]
    public void ConfigurationTextIsEscapedRatherThanRendered()
    {
        var page = Page(Payload(nc: true, ncWhy: "<script>alert(1)</script>"));

        Assert.Multiple(() =>
        {
            Assert.That(page, Does.Not.Contain("<script>alert(1)</script>"));
            Assert.That(page, Does.Contain("&lt;script&gt;"));
        });
    }

    /// <summary>
    /// And the URI reaches the QR script as a JSON string literal, so nothing inside it can close
    /// the script element. A pairing URI carries operator-supplied text too.
    /// </summary>
    [Test]
    public void TheUriCannotBreakOutOfTheScript()
    {
        var page = Page(Payload(nc: true, ncWhy: "</script><script>alert(1)</script>"));

        // Exactly two script elements: the vendored QR renderer and our one call, plus the reload.
        Assert.That(page.Split("<script").Length - 1, Is.EqualTo(3),
                    "an extra <script appeared — something in the payload escaped its literal");
    }

    // ── The refresh ─────────────────────────────────────────────────────────────────────────────

    /// <summary>
    /// The page reloads just *after* its slot ends, so it never shows a code the station has already
    /// stopped accepting. Reloading early would show the next code before the station honours it —
    /// both edges fail, in opposite directions.
    /// </summary>
    [Test]
    public void ThePageReloadsJustAfterItsSlotEnds()
    {
        var page = PairingPage.Render(Payload(), "abc123XYZ789", TimeSpan.FromSeconds(7));

        Assert.Multiple(() =>
        {
            Assert.That(page, Does.Contain("location.reload()"));
            Assert.That(page, Does.Contain("7250"), "reload should be the slot plus a small margin");
        });
    }

    /// <summary>A static display has nothing to refresh, and must not reload in a loop.</summary>
    [Test]
    public void AStaticDisplayDoesNotReload()
    {
        Assert.That(Page(totp: null), Does.Not.Contain("location.reload()"));
    }

    /// <summary>
    /// The QR is drawn by the vendored renderer, served locally. A display whose code comes from a
    /// CDN is a display someone else can change.
    /// </summary>
    [Test]
    public void TheQrRendererIsLocal()
    {
        var page = Page();

        Assert.Multiple(() =>
        {
            Assert.That(page, Does.Contain($"src=\"{PairingPage.QrScriptPath}\""));
            Assert.That(page, Does.Not.Contain("//cdn").And.Not.Contain("http://").And.Not.Contain("https://cdn"));
        });
    }
}
