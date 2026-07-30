using System.Net;
using System.Net.Sockets;

using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

using Vanaheimr.V2G.Simulation.Metering;
using Vanaheimr.V2G.Simulation.StateMachines;
using Vanaheimr.V2G.Simulation.StateMachines.Iso2;
using Vanaheimr.V2G.Simulation.Transport;

namespace EVSimulatorApp.Pi;

/// <summary>
/// The station itself: a V2G listener whose accept is gated by the pairing check, running -2
/// sessions and reporting them to the display.
/// </summary>
/// <remarks>
/// <para>
/// This is the piece that makes the display a station rather than a screen. The three seams it
/// joins were each built and tested separately — <see cref="PairingAdmission"/> (who may connect),
/// <c>TcpV2GListener</c> (the socket) and <c>Secc2</c> (the session) — and the joining is
/// deliberately thin, because a bug here is a bug in wiring rather than in a rule.
/// </para>
/// <para>
/// <b>A display failure never reaches the session.</b> Every report to the hub is fire-and-forget
/// past a catch: a charging session must not end because a browser tab closed.
/// </para>
/// </remarks>
public sealed class SeccStation(
    IPEndPoint endpoint,
    PairingAdmission admission,
    StationHub hub,
    SigningMeter? meter,
    ILogger<SeccStation> logger) : BackgroundService
{
    /// <summary>Where the station is actually listening — the port may have been chosen for us.</summary>
    public IPEndPoint? LocalEndpoint { get; private set; }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var listener = new TcpV2GListener(endpoint)
        {
            // Gate the accept, not the session: an unadmitted peer gets no handshake and no reply.
            Admit = peer =>
            {
                var ok = admission.IsAdmitted(peer);
                if (!ok)
                {
                    logger.LogWarning("refused {Peer}: no valid pairing code presented", peer);
                    Report(hub.LogAsync("err", $"refused {peer} — no pairing code"));
                }
                return ok;
            },
        };

        LocalEndpoint = listener.LocalEndpoint;
        logger.LogInformation("SECC listening on {Endpoint}", LocalEndpoint);
        Report(hub.LogAsync("rx", $"SECC listening on {LocalEndpoint}"));

        while (!stoppingToken.IsCancellationRequested)
        {
            Stream stream;
            try { stream = await listener.AcceptAsync(stoppingToken); }
            catch (OperationCanceledException) { break; }
            catch (SocketException e)
            {
                logger.LogWarning(e, "accept failed");
                continue;
            }

            // One session per connection, and a failing one must not take the listener with it.
            _ = Task.Run(() => RunSessionAsync(stream, stoppingToken), CancellationToken.None);
        }
    }

    private async Task RunSessionAsync(Stream stream, CancellationToken ct)
    {
        var peer = "vehicle";
        Report(hub.ConnectionChangedAsync(true, peer));
        Report(hub.LogAsync("rx", "connection accepted — pairing code was valid"));

        try
        {
            var secc = new Secc2(PowerMode.Ac, TimeSpan.FromSeconds(60), TimeProvider.System)
                       { InstalledMeter = meter };
            await secc.RunAsync(stream, ct);
            Report(hub.LogAsync("tx", secc.Paused ? "session paused" : "session complete"));
        }
        catch (Exception e)
        {
            logger.LogWarning(e, "session ended");
            Report(hub.LogAsync("err", $"session ended: {e.Message}"));
        }
        finally
        {
            await stream.DisposeAsync();
            Report(hub.ConnectionChangedAsync(false, peer));
        }
    }

    /// <summary>
    /// Reporting to the display is best-effort and never awaited into the session path. A screen
    /// that has gone away, or a slow browser, must not slow or fail a charging session.
    /// </summary>
    private void Report(Task reporting) =>
        _ = reporting.ContinueWith(t => logger.LogDebug(t.Exception, "display report failed"),
                                   TaskContinuationOptions.OnlyOnFaulted);
}
