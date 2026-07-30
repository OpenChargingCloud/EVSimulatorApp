using System.Net.WebSockets;
using System.Text;

using EVSimulatorApp.Pairing;

using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;

namespace EVSimulatorApp.Pi;

/// <summary>
/// The station's display, served: the page, the vendored QR renderer, and a WebSocket that pushes
/// each new slot, the connection state and the session log.
/// </summary>
/// <remarks>
/// <para>
/// Thin on purpose. Everything with a rule in it lives in <see cref="StationHub"/> and
/// <see cref="PairingPage"/>, which are testable without a server; this maps them onto HTTP. The
/// split is what lets the interesting parts be tested on a laptop while only the *hosting* waits
/// for the Pi — and hosting turns out not to need the Pi either, since none of this is
/// platform-specific.
/// </para>
/// <para>
/// <b>Push rather than poll.</b> A page that reloads on a timer and a station whose clock has
/// drifted disagree silently, and the symptom is a code the station has already stopped accepting.
/// Pushing means the code on screen is the one the station just minted.
/// </para>
/// </remarks>
public static class PairingWebApp
{
    public const string LivePath = "/ws";

    /// <summary>Maps the three routes onto an existing app.</summary>
    /// <param name="qrScriptPath">
    /// The vendored <c>qrcode.min.js</c>. Served from disk, never from a CDN: a display whose code
    /// comes off the internet is a display someone else can change.
    /// </param>
    public static WebApplication MapPairingDisplay(this WebApplication app, StationHub hub,
                                                   string qrScriptPath)
    {
        app.UseWebSockets();

        app.MapGet("/", () =>
        {
            var (uri, remaining) = hub.CurrentPairing();
            var payload = PairingUri.Parse(uri)!;
            return Results.Content(
                PairingPage.Render(payload, payload.Totp, remaining, LivePath),
                "text/html; charset=utf-8");
        });

        app.MapGet("/" + PairingPage.QrScriptPath, () =>
            File.Exists(qrScriptPath)
                ? Results.File(qrScriptPath, "text/javascript")
                // Fail loudly rather than serving a page whose QR silently never appears.
                : Results.Problem($"the vendored QR renderer is missing at {qrScriptPath}", statusCode: 500));

        app.Map(LivePath, async (HttpContext http) =>
        {
            if (!http.WebSockets.IsWebSocketRequest)
            {
                http.Response.StatusCode = StatusCodes.Status400BadRequest;
                return;
            }

            using var socket = await http.WebSockets.AcceptWebSocketAsync();
            await ServeAsync(hub, socket, http.RequestAborted);
        });

        return app;
    }

    private static async Task ServeAsync(StationHub hub, WebSocket socket, CancellationToken ct)
    {
        // One writer at a time: a WebSocket permits a single outstanding send, and the hub fans out
        // from whichever thread reported the event.
        var writing = new SemaphoreSlim(1, 1);

        async Task Send(StationEvent e)
        {
            await writing.WaitAsync(ct);
            try
            {
                if (socket.State is WebSocketState.Open)
                    await socket.SendAsync(Encoding.UTF8.GetBytes(e.ToJson()),
                                           WebSocketMessageType.Text, endOfMessage: true, ct);
            }
            finally { writing.Release(); }
        }

        using var subscription = hub.Subscribe(Send);

        // State first, so a page opening mid-session is immediately right rather than blank until
        // the next rotation.
        var (uri, remaining) = hub.CurrentPairing();
        await Send(StationEvent.Pairing("", uri, (int) remaining.TotalSeconds));
        await Send(StationEvent.Status("", hub.Connected, ""));
        foreach (var line in hub.Backlog())
            await Send(line);

        // Nothing is expected *from* the page. Reading is how a close handshake is noticed.
        var buffer = new byte[256];
        try
        {
            while (socket.State is WebSocketState.Open && !ct.IsCancellationRequested)
            {
                var received = await socket.ReceiveAsync(buffer, ct);
                if (received.MessageType is WebSocketMessageType.Close) break;
            }
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException) { }
    }
}
