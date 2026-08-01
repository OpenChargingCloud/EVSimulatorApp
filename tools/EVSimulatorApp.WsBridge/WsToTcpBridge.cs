using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

using Vanaheimr.V2G.Simulation.Transport;

namespace EVSimulatorApp.WsBridge;

/// <summary>
/// A WebSocket listener that forwards each connection to a station over TCP, one V2GTP frame per
/// WebSocket message (<c>docs/CONCEPT.md</c> B1).
/// </summary>
/// <remarks>
/// <para>
/// <b>Why a bridge rather than a WebSocket listener in the SECC.</b> This touches nothing that
/// carries conformance evidence. The station is unmodified — ours, Josev's, or a real one — and what
/// crosses the wire to it is exactly what an EVCC would have sent. A WebSocket listener grafted into
/// a SECC would be a second transport inside the implementation being tested.
/// </para>
/// <para>
/// <b>It is not ISO 15118 on the browser's side, and that is fine as long as it is said.</b>
/// V2GTP-over-WebSocket is a development and inspection transport. It exists so a browser — which
/// cannot open a TCP socket — can drive and watch a session. Conformance evidence it is not.
/// </para>
/// <para>
/// <b>One frame per message.</b> WebSocket delivers whole messages and V2GTP already carries its own
/// length header, so the mapping is exact: browser→station writes a message's bytes to the socket,
/// and station→browser reassembles frames with <see cref="V2GFrameReader"/> and sends one message
/// each. Anything else would hand a browser arbitrary TCP chunks and make it do the framing.
/// </para>
/// </remarks>
public sealed class WsToTcpBridge(WsToTcpBridge.Options options)
{

    /// <param name="Listen">Where to accept WebSocket connections. Loopback by default, deliberately.</param>
    /// <param name="Allowed">
    /// When non-empty, the only stations that may be reached. Empty means anything the private-range
    /// rule permits.
    /// </param>
    /// <param name="Tls">Whether to speak TLS to the station.</param>
    /// <param name="PinnedRoot">
    /// The station root's SHA-256, lower-case hex — the same value a pairing code's <c>root</c>
    /// carries. Required when <paramref name="Tls"/> is set: see <see cref="ValidateAgainstPinnedRoot"/>.
    /// </param>
    /// <param name="Log">Where connection notices go. Every accepted and refused connection is named.</param>
    public sealed record Options(
        IPEndPoint                      Listen,
        IReadOnlyCollection<BridgeTarget> Allowed,
        bool                            Tls        = false,
        String?                         PinnedRoot = null,
        Action<String>?                 Log        = null)
    {
        public void Say(String what) => (Log ?? Console.WriteLine)(what);
    }


    private readonly TcpListener listener = new(options.Listen);


    /// <summary>The port actually bound. Differs from the requested one when that was 0.</summary>
    public int Port => ((IPEndPoint) listener.LocalEndpoint).Port;


    public void Start()
    {
        listener.Start();
        options.Say($"listening on ws://{listener.LocalEndpoint}");
    }


    /// <summary>Accepts until cancelled. One connection at a time is not a limit here — accept and
    /// serve are separate, and a slow station holds up only its own session.</summary>
    public async Task RunAsync(CancellationToken cancel)
    {
        while (!cancel.IsCancellationRequested)
        {
            TcpClient client;

            try { client = await listener.AcceptTcpClientAsync(cancel); }
            catch (OperationCanceledException) { return; }
            catch (SocketException) { return; }

            _ = ServeAsync(client, cancel);
        }
    }


    private async Task ServeAsync(TcpClient client, CancellationToken cancel)
    {

        var from = client.Client.RemoteEndPoint?.ToString() ?? "?";

        try
        {

            using (client)
            await using (var stream = client.GetStream())
            {

                var request = await WebSocketHandshake.AcceptAsync(stream, cancel);

                if (!BridgeTarget.TryParse(request.Target, options.Allowed, out var target, out var refusal))
                {
                    // After the upgrade, so the refusal has to travel as a WebSocket close rather than
                    // as an HTTP status. A browser sees the reason either way, which is the point.
                    options.Say($"{from} refused: {refusal}");

                    using var refused = WebSocket.CreateFromStream(stream, isServer: true, null,
                                                                   TimeSpan.FromSeconds(30));
                    await refused.CloseAsync(WebSocketCloseStatus.PolicyViolation, refusal, cancel);
                    return;
                }

                options.Say($"{from} → {target}"
                          + (options.Tls ? " over TLS" : "")
                          + (request.Origin is { } origin ? $"  (origin {origin})" : ""));

                using var socket = WebSocket.CreateFromStream(stream, isServer: true, null,
                                                              TimeSpan.FromSeconds(30));

                await using var station = await ConnectAsync(target, cancel);

                await PumpAsync(socket, station, target, cancel);

            }

        }
        catch (HandshakeRefusedException e) { options.Say($"{from} refused: {e.Message}"); }
        catch (OperationCanceledException)  { }
        catch (Exception e)                 { options.Say($"{from} ended: {e.Message}"); }

    }


    private async Task<Stream> ConnectAsync(BridgeTarget target, CancellationToken cancel)
    {

        if (!options.Tls)
            // The cast picks the SslStream overload over the BouncyCastle one, which a bare `null`
            // cannot choose between. Either would do nothing here; being explicit is free.
            return await TcpV2GClient.ConnectAsync(target.Host, target.Port, (TlsOptions?) null, cancel);

        var pin = options.PinnedRoot
                      ?? throw new InvalidOperationException(
                             "TLS without a pinned root. The browser cannot check a chain — that is "
                           + "the whole reason this bridge terminates TLS — so if this does not "
                           + "check it, nothing does.");

        return await TcpV2GClient.ConnectAsync(target.Host, target.Port, new TlsOptions {
            // Both, because the bridge does not know which protocol the session will negotiate, and
            // docs/pki-model.md pins -2 to 1.2 and -20 to 1.3. Verify what was negotiated rather than
            // assuming it.
            EnabledSslProtocols         = SslProtocols.Tls12 | SslProtocols.Tls13,
            ServerCertificateValidation = (_, _, chain, _) => ValidateAgainstPinnedRoot(chain, pin),
        }, cancel);

    }


    /// <summary>
    /// Trusts exactly one root: the one named on the command line, and only if the station sends it.
    /// </summary>
    /// <remarks>
    /// <para>
    /// <b>This is the bridge earning its keep.</b> A browser validates <c>wss://</c> against its own
    /// trust store and gives JavaScript no way to see the chain, so a web front end simply cannot
    /// honour the pairing code's <c>root</c> fingerprint. Terminating TLS here puts that check back —
    /// in a process the operator started, with the fingerprint they passed it.
    /// </para>
    /// <para>
    /// Not the platform trust store: a V2G root is not a web CA, so the system anchors cannot apply.
    /// The chain is rebuilt with the pinned certificate as the only anchor, which is the same thing
    /// the Kotlin and Swift transports do.
    /// </para>
    /// </remarks>
    internal static bool ValidateAgainstPinnedRoot(X509Chain? presented, String pinnedRootHex)
    {

        if (presented is null || presented.ChainElements.Count == 0)
            return false;

        var pin = Convert.FromHexString(pinnedRootHex.Replace(":", "").Trim());

        var sent = presented.ChainElements.Select(e => e.Certificate).ToList();

        var anchor = sent.FirstOrDefault(c => SHA256.HashData(c.RawData).AsSpan().SequenceEqual(pin));
        if (anchor is null)
            return false;                     // the station never sent the root that was pinned

        // A self-signed station certificate that IS the pinned root: the pin is the whole identity,
        // and there is no path left to build.
        if (sent.Count == 1)
            return true;

        using var chain = new X509Chain();
        chain.ChainPolicy.TrustMode         = X509ChainTrustMode.CustomRootTrust;
        chain.ChainPolicy.RevocationMode    = X509RevocationMode.NoCheck;
        chain.ChainPolicy.CustomTrustStore.Add(anchor);

        foreach (var extra in sent.Where(c => !ReferenceEquals(c, anchor)).Skip(1))
            chain.ChainPolicy.ExtraStore.Add(extra);

        return chain.Build(sent[0]);

    }


    /// <summary>Both directions at once, ending when either does.</summary>
    private async Task PumpAsync(WebSocket socket, Stream station, BridgeTarget target,
                                 CancellationToken cancel)
    {

        using var ending = CancellationTokenSource.CreateLinkedTokenSource(cancel);

        var frames = 0;

        var toStation = Task.Run(async () =>
        {
            try     { frames += await PumpToStationAsync(socket, station, ending.Token); }
            finally { ending.Cancel(); }
        }, CancellationToken.None);

        var toBrowser = Task.Run(async () =>
        {
            try     { frames += await PumpToBrowserAsync(station, socket, ending.Token); }
            finally { ending.Cancel(); }
        }, CancellationToken.None);

        await Task.WhenAll(toStation, toBrowser).ContinueWith(_ => { }, CancellationToken.None);

        options.Say($"{target} closed after {frames} frames");

    }


    private static async Task<int> PumpToStationAsync(WebSocket socket, Stream station,
                                                      CancellationToken cancel)
    {

        var buffer = new byte[64 * 1024];
        var frames = 0;

        while (!cancel.IsCancellationRequested)
        {

            using var message = new MemoryStream();
            WebSocketReceiveResult result;

            do
            {
                result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), cancel);

                if (result.MessageType == WebSocketMessageType.Close) return frames;

                // Text would mean somebody is speaking a different protocol to this port, and
                // guessing what they meant is how a bridge becomes an attack surface.
                if (result.MessageType != WebSocketMessageType.Binary)
                    throw new V2GFramingException(
                        "a text message arrived; V2GTP frames are binary");

                message.Write(buffer, 0, result.Count);
            }
            while (!result.EndOfMessage);

            await station.WriteAsync(message.ToArray(), cancel);
            await station.FlushAsync(cancel);
            frames++;

        }

        return frames;

    }


    private static async Task<int> PumpToBrowserAsync(Stream station, WebSocket socket,
                                                      CancellationToken cancel)
    {

        var reader = new V2GFrameReader(station);
        var frames = 0;

        while (!cancel.IsCancellationRequested)
        {

            var frame = await reader.ReadFrameAsync(cancel);
            if (frame is null) break;                  // the station closed between frames

            await socket.SendAsync(frame, WebSocketMessageType.Binary, endOfMessage: true, cancel);
            frames++;

        }

        if (socket.State == WebSocketState.Open)
            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "the station closed",
                                    CancellationToken.None);

        return frames;

    }

}
