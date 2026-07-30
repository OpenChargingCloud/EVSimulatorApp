using System.Net;
using System.Security.Cryptography;

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
var template = new PairingPayload
{
    Version   = 1,
    Host      = config["station:host"] ?? "192.168.4.1",
    Port      = int.Parse(config["station:port"] ?? "15118"),
    Transport = config["station:transport"] is "tcp" ? PairingTransport.Tcp : PairingTransport.Tls,
    Protocols = (config["station:protocols"] ?? "iso2,iso20").Split(',', StringSplitOptions.RemoveEmptyEntries),
    Crypto    = config["station:crypto"] ?? PairingWarnings.ConformantCurve,
    NonConformant        = config["station:nc"] is "1" or "true",
    NonConformanceReason = config["station:ncwhy"],
    RootFingerprint      = config["station:root"],
    Meter                = config["station:meter"],
    EvseId               = config["station:evseId"],
};

// The shared secret is provisioned out of band and never rendered — only the code derived from it
// reaches the screen (§4.6). Refusing to start without one is deliberate: a display with no
// proximity proof is a sticker, and it should be a decision rather than a default.
var secret = config["station:totpSecret"]
             ?? throw new InvalidOperationException(
                 "station:totpSecret is required — the rotating code is what makes this a proximity "
               + "proof rather than a photograph. Set it, or run a static display on purpose.");

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
    admission, hub, meter, sp.GetRequiredService<ILogger<SeccStation>>()));

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
app.Run();

/// <summary>Exposed so the tests can drive the same wiring the binary uses.</summary>
public partial class Program;
