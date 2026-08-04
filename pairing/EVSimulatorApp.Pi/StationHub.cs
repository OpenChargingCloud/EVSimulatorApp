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

using System.Text.Json;
using System.Text.Json.Serialization;

using EVSimulatorApp.Pairing;

namespace EVSimulatorApp.Pi;

/// <summary>
/// What the display page is told, and what the station knows. One place, so the page cannot say
/// something the station is not doing.
/// </summary>
/// <remarks>
/// Deliberately free of sockets and HTTP: this is the part with rules in it — which code is current,
/// when it rotates, whether a vehicle is attached, what has been logged — and it is testable without
/// a server. <see cref="PairingWebApp"/> is the thin layer that puts it on a wire.
/// </remarks>
public sealed class StationHub
{
    private readonly PairingPayload template;
    private readonly PairingTotpVerifier totp;
    private readonly TimeProvider clock;
    private readonly int logCapacity;

    private readonly Lock gate = new();
    private readonly Queue<StationEvent> log = new();
    private readonly List<Func<StationEvent, Task>> subscribers = [];

    private int connections;

    /// <param name="template">
    /// The station's live configuration. The code shown is derived from it every time, so the page
    /// cannot advertise a profile the station is not running — the reason §4.5 puts the generator on
    /// the Pi rather than on a sticker.
    /// </param>
    /// <param name="logCapacity">
    /// How many lines a newly-opened page is given. Bounded because this runs for days on a device
    /// with 1 GB of RAM and nobody scrolls back an hour.
    /// </param>
    public StationHub(PairingPayload template, PairingTotpVerifier totp,
                      TimeProvider? clock = null, int logCapacity = 200)
    {
        this.template    = template;
        this.totp        = totp;
        this.clock       = clock ?? TimeProvider.System;
        this.logCapacity = logCapacity;
    }

    /// <summary>The pairing URI for the current slot, and how long it lasts.</summary>
    public (string Uri, TimeSpan Remaining) CurrentPairing()
    {
        var (code, remaining) = totp.Current();
        return (PairingUri.Format(template with { Totp = code }), remaining);
    }

    /// <summary>Whether a vehicle is attached right now.</summary>
    public bool Connected { get { lock (gate) return connections > 0; } }

    /// <summary>The backlog a page gets on connect, so it does not open blank mid-session.</summary>
    public IReadOnlyList<StationEvent> Backlog()
    {
        lock (gate) return log.ToArray();
    }

    public IDisposable Subscribe(Func<StationEvent, Task> onEvent)
    {
        lock (gate) subscribers.Add(onEvent);
        return new Unsubscriber(this, onEvent);
    }

    // ── What the station reports ────────────────────────────────────────────────────────────────

    /// <summary>A new slot began; every page should re-render its code.</summary>
    public Task RotateAsync()
    {
        var (uri, remaining) = CurrentPairing();
        return PublishAsync(StationEvent.Pairing(Now, uri, (int) remaining.TotalSeconds));
    }

    /// <summary>A vehicle attached or detached.</summary>
    public Task ConnectionChangedAsync(bool attached, string peer)
    {
        bool connected;
        lock (gate)
        {
            connections = Math.Max(0, connections + (attached ? 1 : -1));
            connected = connections > 0;
        }
        return PublishAsync(StationEvent.Status(Now, connected, peer));
    }

    /// <summary>One line of ISO 15118 traffic or session state, for the page's log window.</summary>
    public Task LogAsync(string level, string text) =>
        PublishAsync(StationEvent.Log(Now, level, text));

    private string Now => clock.GetUtcNow().ToString("HH:mm:ss.fff");

    private async Task PublishAsync(StationEvent e)
    {
        Func<StationEvent, Task>[] targets;
        lock (gate)
        {
            // Only the log is replayed to a page that opens later; pairing and status are answered
            // from live state on connect, so keeping them here would just show a page an old code.
            if (e.Type is "log")
            {
                log.Enqueue(e);
                while (log.Count > logCapacity) log.Dequeue();
            }
            targets = subscribers.ToArray();
        }

        // A page that has gone away must not stop the others being told, and must never propagate
        // back into the SECC: this is a display, and the session outranks it.
        foreach (var target in targets)
        {
            try { await target(e); }
            catch { /* dropped subscriber; Unsubscriber removes it */ }
        }
    }

    private sealed class Unsubscriber(StationHub hub, Func<StationEvent, Task> onEvent) : IDisposable
    {
        public void Dispose()
        {
            lock (hub.gate) hub.subscribers.Remove(onEvent);
        }
    }
}

/// <summary>One message to the display page.</summary>
public sealed record StationEvent(
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("at")] string At,
    [property: JsonPropertyName("uri")] string? Uri = null,
    [property: JsonPropertyName("seconds")] int? Seconds = null,
    [property: JsonPropertyName("connected")] bool? Connected = null,
    [property: JsonPropertyName("peer")] string? Peer = null,
    [property: JsonPropertyName("level")] string? Level = null,
    [property: JsonPropertyName("text")] string? Text = null)
{
    public static StationEvent Pairing(string at, string uri, int seconds) =>
        new("pairing", at, Uri: uri, Seconds: seconds);

    public static StationEvent Status(string at, bool connected, string peer) =>
        new("status", at, Connected: connected, Peer: peer);

    public static StationEvent Log(string at, string level, string text) =>
        new("log", at, Level: level, Text: text);

    public string ToJson() => JsonSerializer.Serialize(this, JsonOptions);

    private static readonly JsonSerializerOptions JsonOptions =
        new() { DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull };
}
