using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

using EVSimulatorApp.Pairing;
using EVSimulatorApp.Pi;

using Vanaheimr.V2G.Simulation.Metering;

// The Pi-side host: the pairing display, and the rotation that drives it.
//
// Everything here is plain .NET 10 — nothing about it is Raspberry-Pi-specific, which is the point.
// What genuinely needs the hardware is one layer down: binding to the WLAN interface, AP mode, and
// PLC. This runs and is exercised identically on a laptop.

var builder = WebApplication.CreateBuilder(args);
builder.Logging.AddSimpleConsole(o => { o.SingleLine = true; o.TimestampFormat = "HH:mm:ss "; });

var config = builder.Configuration;

// The station's live configuration. The display is derived from this every rotation, so it cannot
// advertise a profile the station is not running (docs/CONCEPT.md §4.5).
// The shared secret is provisioned out of band and never rendered — only the code derived from it
// reaches the screen (§4.6). Refusing to start without one is deliberate: a display with no
// proximity proof is a sticker, and it should be a decision rather than a default.
var secret = config["station:totpSecret"]
             ?? throw new InvalidOperationException(
                 "station:totpSecret is required — the rotating code is what makes this a proximity "
               + "proof rather than a photograph. Set it, or run a static display on purpose.");

// One profile, two projections: the listener's transport and the display's declaration. Keeping
// them as separate config keys is how a screen ends up advertising TLS beside a plaintext port.
var certificatePath = config["station:certificate"];
var profile = StationProfile.Parse(
    int.Parse(config["station:protocol"] ?? "2"),
    config["station:transport"] ?? "tcp",
    certificatePath is { Length: > 0 }
        // Exportable on purpose: a TLS-1.3-only session on macOS runs on the BouncyCastle fallback
        // (docs/pki-model.md), and its credential bridge exports the key to PKCS#8. Loading without
        // this flag fails every -20 accept with "the private key is not exportable" — found by
        // running it, not by reading it.
        ? X509CertificateLoader.LoadPkcs12FromFile(certificatePath, config["station:certificatePassword"],
                                                   X509KeyStorageFlags.Exportable)
        : null);

// The address on screen is the one thing a person acts on, so it is detected rather than assumed:
// the first wireless interface's routable IPv4, which on the Pi in AP mode is 192.168.4.1 and on a
// development laptop is that laptop's address. station:host still wins where the two cannot be
// guessed apart — behind NAT, or when the phone reaches the station by a name.
var (host, hostBecause) = config["station:host"] is { Length: > 0 } configuredHost
                              ? (configuredHost, "station:host")
                              : StationAddress.Detect();

// The display's declaration, projected from the profile rather than configured beside it.
var template = profile.Declare(
    host:                 host,
    port:                 int.Parse(config["station:port"] ?? "15118"),
    evseId:               config["station:evseId"],
    rootFingerprint:      config["station:root"],
    meter:                config["station:meter"],
    nonConformant:        config["station:nc"] is "1" or "true",
    nonConformanceReason: config["station:ncwhy"]);

var slot      = TimeSpan.FromSeconds(int.Parse(config["station:slotSeconds"] ?? "10"));
var verifier  = new PairingTotpVerifier(secret, slot);
var hub       = new StationHub(template, verifier);
var admission = new PairingAdmission(verifier);

// A meter is opt-in. Without one the station reports unsigned readings, which is what every station
// in the field does; with one, SigMeterReading carries a signature the app can check (§4.3).
SigningMeter? meter = config["station:meterId"] is { Length: > 0 } meterId
    ? new SigningMeter(meterId, ECDsa.Create(ECCurve.NamedCurves.nistP256), TimeProvider.System)
    : null;

builder.Services.AddSingleton(admission);
builder.Services.AddSingleton(hub);
builder.Services.AddHostedService(sp => new SeccStation(
    new IPEndPoint(IPAddress.IPv6Any, int.Parse(config["station:listenPort"] ?? "15118")),
    profile, admission, hub, meter, sp.GetRequiredService<ILogger<SeccStation>>()));

var app = builder.Build();

// The vendored renderer, out of the DynamicQRCodes submodule rather than a CDN.
var qrScript = config["station:qrScript"]
               ?? Path.Combine(AppContext.BaseDirectory,
                               "../../../../../libs/DynamicQRCodes/TOTP/JavaScript-Web/QRCodeSVG/qrcode.min.js");

app.MapPairingDisplay(hub, Path.GetFullPath(qrScript), admission);

// Rotation. Aligned to the slot the verifier actually uses rather than to a timer of its own: the
// page must never show a code the station has stopped accepting, and two independent clocks is how
// that happens.
_ = Task.Run(async () =>
{
    while (!app.Lifetime.ApplicationStopping.IsCancellationRequested)
    {
        var (_, remaining) = hub.CurrentPairing();
        try { await Task.Delay(remaining + TimeSpan.FromMilliseconds(50), app.Lifetime.ApplicationStopping); }
        catch (OperationCanceledException) { break; }
        await hub.RotateAsync();
    }
});

app.Logger.LogInformation("pairing display on {Urls}", string.Join(", ", app.Urls.DefaultIfEmpty("(default)")));
// Named, not silent: a screen advertising an address nobody can reach is the failure this detection
// exists to avoid, and the log is where that becomes visible without scanning the code.
app.Logger.LogInformation("display advertises {Host} — {Because}", host, hostBecause);
app.Run();

/// <summary>Exposed so the tests can drive the same wiring the binary uses.</summary>
public partial class Program;
