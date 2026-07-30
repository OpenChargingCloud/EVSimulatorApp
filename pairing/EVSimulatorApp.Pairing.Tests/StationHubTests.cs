using EVSimulatorApp.Pairing;
using EVSimulatorApp.Pi;
using NUnit.Framework;

namespace EVSimulatorApp.Pairing.Tests;

/// <summary>
/// What the station tells its display.
/// </summary>
/// <remarks>
/// The hub holds every rule the display has; <c>PairingWebApp</c> only puts it on a socket. So this
/// is where the properties that matter are checked — that the code shown is the station's own, that
/// the secret never leaves, and that a display which has gone away cannot affect the station.
/// </remarks>
[TestFixture]
public class StationHubTests
{
    private const string Secret = "a-shared-secret-of-sufficient-length";
    private static readonly TimeSpan Slot = TimeSpan.FromSeconds(10);

    private sealed class FakeClock(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset now = now;
        public override DateTimeOffset GetUtcNow() => now;
        public void Advance(TimeSpan by) => now += by;
    }

    private static PairingPayload Template() => new()
    {
        Version = 1, Host = "192.168.4.1", Port = 15118, Transport = PairingTransport.Tls,
        Crypto = PairingWarnings.ConformantCurve, Protocols = ["iso2", "iso20"],
    };

    private static (StationHub Hub, FakeClock Clock) Hub(int logCapacity = 200)
    {
        var clock = new FakeClock(new DateTimeOffset(2026, 7, 31, 12, 0, 0, TimeSpan.Zero));
        return (new StationHub(Template(), new PairingTotpVerifier(Secret, Slot, clock), clock, logCapacity),
                clock);
    }

    private static List<StationEvent> Collect(StationHub hub)
    {
        var seen = new List<StationEvent>();
        hub.Subscribe(e => { seen.Add(e); return Task.CompletedTask; });
        return seen;
    }

    /// <summary>The code offered is the station's own configuration, every time.</summary>
    [Test]
    public void ThePairingUriIsDerivedFromTheLiveConfiguration()
    {
        var (hub, _) = Hub();
        var parsed = PairingUri.Parse(hub.CurrentPairing().Uri)!;

        Assert.Multiple(() =>
        {
            Assert.That(parsed.Host, Is.EqualTo("192.168.4.1"));
            Assert.That(parsed.Crypto, Is.EqualTo(PairingWarnings.ConformantCurve));
            Assert.That(parsed.Totp, Is.Not.Null);
        });
    }

    [Test]
    public async Task RotatingPublishesANewCode()
    {
        var (hub, clock) = Hub();
        var seen = Collect(hub);
        var before = PairingUri.Parse(hub.CurrentPairing().Uri)!.Totp;

        clock.Advance(Slot);
        await hub.RotateAsync();

        var published = seen.Single(e => e.Type is "pairing");
        Assert.Multiple(() =>
        {
            Assert.That(PairingUri.Parse(published.Uri!)!.Totp, Is.Not.EqualTo(before));
            Assert.That(published.Seconds, Is.GreaterThan(0));
        });
    }

    /// <summary>
    /// The shared secret never reaches an event. Only the derived code does — a display that leaked
    /// it would turn the proximity proof into a permanent credential for anyone watching.
    /// </summary>
    [Test]
    public async Task TheSharedSecretNeverLeaves()
    {
        var (hub, _) = Hub();
        var seen = Collect(hub);

        await hub.RotateAsync();
        await hub.ConnectionChangedAsync(true, "fe80::2");
        await hub.LogAsync("rx", "SessionSetupReq");

        Assert.That(seen.Select(e => e.ToJson()), Has.None.Contains(Secret));
    }

    [Test]
    public async Task ConnectingAndDisconnectingIsReported()
    {
        var (hub, _) = Hub();
        var seen = Collect(hub);

        await hub.ConnectionChangedAsync(true, "fe80::2");
        Assert.That(hub.Connected, Is.True);

        await hub.ConnectionChangedAsync(false, "fe80::2");
        Assert.Multiple(() =>
        {
            Assert.That(hub.Connected, Is.False);
            Assert.That(seen.Where(e => e.Type is "status").Select(e => e.Connected),
                        Is.EqualTo(new bool?[] { true, false }));
        });
    }

    /// <summary>
    /// A page opening later gets the log, but <b>not</b> a stale pairing code — that is answered
    /// from live state on connect. Replaying an old one would put a code on screen the station has
    /// already stopped accepting.
    /// </summary>
    [Test]
    public async Task OnlyTheLogIsReplayedToALateDisplay()
    {
        var (hub, _) = Hub();
        await hub.RotateAsync();
        await hub.ConnectionChangedAsync(true, "fe80::2");
        await hub.LogAsync("rx", "SessionSetupReq");

        Assert.That(hub.Backlog().Select(e => e.Type), Is.EqualTo(new[] { "log" }));
    }

    /// <summary>Bounded: this runs for days on a device with a gigabyte of RAM.</summary>
    [Test]
    public async Task TheLogIsBounded()
    {
        var (hub, _) = Hub(logCapacity: 10);

        for (var i = 0; i < 100; i++) await hub.LogAsync("rx", $"line {i}");

        var backlog = hub.Backlog();
        Assert.Multiple(() =>
        {
            Assert.That(backlog, Has.Count.EqualTo(10));
            Assert.That(backlog.Last().Text, Is.EqualTo("line 99"), "the newest lines are the ones kept");
        });
    }

    /// <summary>
    /// A display that has gone away must not stop the others being told, and must never propagate
    /// back into the station: this is a screen, and the charging session outranks it.
    /// </summary>
    [Test]
    public async Task ABrokenDisplayDoesNotAffectTheStationOrTheOthers()
    {
        var (hub, _) = Hub();
        hub.Subscribe(_ => throw new IOException("socket gone"));
        var healthy = Collect(hub);

        Assert.DoesNotThrowAsync(async () => await hub.LogAsync("rx", "SessionSetupReq"));
        await hub.LogAsync("tx", "SessionSetupRes");

        Assert.That(healthy, Has.Count.EqualTo(2));
    }

    [Test]
    public void UnsubscribingStopsDelivery()
    {
        var (hub, _) = Hub();
        var seen = new List<StationEvent>();
        var subscription = hub.Subscribe(e => { seen.Add(e); return Task.CompletedTask; });

        subscription.Dispose();
        hub.LogAsync("rx", "after").GetAwaiter().GetResult();

        Assert.That(seen, Is.Empty);
    }

    /// <summary>Log lines carry peer-controlled text, so they must survive as data, not markup.</summary>
    [Test]
    public async Task LogTextIsCarriedVerbatim()
    {
        var (hub, _) = Hub();
        var seen = Collect(hub);

        await hub.LogAsync("err", "<script>alert(1)</script>");

        Assert.That(seen.Single().Text, Is.EqualTo("<script>alert(1)</script>"),
                    "the hub carries it; the page renders it with textContent");
    }
}
