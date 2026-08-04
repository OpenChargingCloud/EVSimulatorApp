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

using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;

using NUnit.Framework;

namespace EVSimulatorApp.WsBridge.Tests;

/// <summary>
/// The bridge, end to end: a real WebSocket client, the real bridge, and a station on a real socket.
/// </summary>
/// <remarks>
/// <para>
/// <b>The property being checked is that the mapping is exact.</b> WebSocket delivers whole messages
/// and TCP does not, so the interesting direction is station→browser: the recorded frames arrive
/// three bytes at a time, and the browser must receive <em>one message per frame</em>, each holding
/// the whole frame and nothing else. A bridge that forwarded TCP chunks would pass a "the bytes all
/// arrived" test and fail this one.
/// </para>
/// <para>
/// The other direction is checked by the station itself, which compares every request against the
/// recording before answering — so anything the bridge altered on the way out is a failure there.
/// </para>
/// </remarks>
[TestFixture]
public class BridgeTests
{

    private static IReadOnlyList<byte[]> RecordedFrames(JsonNode trace, string side)
        => trace["exchanges"]!.AsArray()
               .Select(e => e![side])
               .Where(f => f is not null)
               .Select(f => Convert.FromHexString(f!["frame"]!.GetValue<string>()))
               .ToList();


    [Test]
    [TestCase("iso2-ac-eim")]
    [TestCase("iso2-dc-eim")]
    [TestCase("iso20-ac-eim")]
    [TestCase("iso20-dc-eim")]
    public async Task ARecordedSessionCrossesTheBridgeOneFramePerMessage(string session)
    {

        var trace = RecordedStation.Load(session);

        await using var station = new RecordedStation(trace);

        using var stopping = new CancellationTokenSource(TimeSpan.FromSeconds(30));

        var bridge = new WsToTcpBridge(new WsToTcpBridge.Options(
                         new IPEndPoint(IPAddress.Loopback, 0), [], Log: _ => { }));

        bridge.Start();
        _ = bridge.RunAsync(stopping.Token);

        using var client = new ClientWebSocket();
        await client.ConnectAsync(
            new Uri($"ws://127.0.0.1:{bridge.Port}/?host=127.0.0.1&port={station.Port}"),
            stopping.Token);

        var requests  = RecordedFrames(trace, "request");
        var responses = RecordedFrames(trace, "response");
        var received  = new List<byte[]>();

        foreach (var request in requests)
        {

            await client.SendAsync(request, WebSocketMessageType.Binary, true, stopping.Token);

            if (received.Count >= responses.Count) break;

            received.Add(await ReceiveMessageAsync(client, stopping.Token));

        }

        Assert.Multiple(() => {

            Assert.That(station.Complaint, Is.Null, "the station refused what arrived through the bridge");

            Assert.That(station.Served, Is.EqualTo(station.Exchanges),
                        $"{session}: the station served {station.Served} of {station.Exchanges} exchanges");

            // One message per frame — not "the same bytes overall". A bridge that forwarded TCP
            // chunks would give a different count with identical concatenated content.
            Assert.That(received, Has.Count.EqualTo(responses.Count), $"{session}: message count");

            for (var i = 0; i < received.Count; i++)
                Assert.That(Convert.ToHexString(received[i]).ToLowerInvariant(),
                            Is.EqualTo(Convert.ToHexString(responses[i]).ToLowerInvariant()),
                            $"{session}: message {i}");

        });

    }


    /// <summary>
    /// A target the private-range rule refuses never reaches a socket, and the browser is told why.
    /// </summary>
    /// <remarks>
    /// The refusal arrives as a WebSocket close rather than an HTTP status, because by then the
    /// upgrade has already happened — but it arrives, with words in it. A bridge that closed silently
    /// would look exactly like a station that is not there.
    /// </remarks>
    [Test]
    public async Task APublicTargetIsRefusedWithAReasonTheBrowserCanRead()
    {

        using var stopping = new CancellationTokenSource(TimeSpan.FromSeconds(15));

        var said   = new List<string>();
        var bridge = new WsToTcpBridge(new WsToTcpBridge.Options(
                         new IPEndPoint(IPAddress.Loopback, 0), [], Log: said.Add));

        bridge.Start();
        _ = bridge.RunAsync(stopping.Token);

        using var client = new ClientWebSocket();
        await client.ConnectAsync(
            new Uri($"ws://127.0.0.1:{bridge.Port}/?host=93.184.216.34&port=15118"), stopping.Token);

        var result = await client.ReceiveAsync(new ArraySegment<byte>(new byte[64]), stopping.Token);

        Assert.Multiple(() => {
            Assert.That(result.MessageType, Is.EqualTo(WebSocketMessageType.Close));
            Assert.That(client.CloseStatus, Is.EqualTo(WebSocketCloseStatus.PolicyViolation));
            Assert.That(client.CloseStatusDescription, Does.Contain("private or link-local"));
            Assert.That(said, Has.Some.Contains("refused"), "the refusal was not logged");
        });

    }


    /// <summary>And a target that is allowed but not on the list is refused too.</summary>
    [Test]
    public async Task AnAllowListNarrowsWhatThePrivateRangeRulePermits()
    {

        using var stopping = new CancellationTokenSource(TimeSpan.FromSeconds(15));

        var bridge = new WsToTcpBridge(new WsToTcpBridge.Options(
                         new IPEndPoint(IPAddress.Loopback, 0),
                         [BridgeTarget.Parse("192.168.4.1:15118")],
                         Log: _ => { }));

        bridge.Start();
        _ = bridge.RunAsync(stopping.Token);

        using var client = new ClientWebSocket();
        await client.ConnectAsync(
            new Uri($"ws://127.0.0.1:{bridge.Port}/?host=10.0.0.1&port=15118"), stopping.Token);

        await client.ReceiveAsync(new ArraySegment<byte>(new byte[64]), stopping.Token);

        Assert.That(client.CloseStatusDescription, Does.Contain("not among the targets"));

    }


    private static async Task<byte[]> ReceiveMessageAsync(WebSocket socket, CancellationToken cancel)
    {

        using var message = new MemoryStream();
        var buffer = new byte[8192];

        while (true)
        {

            var result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), cancel);

            if (result.MessageType == WebSocketMessageType.Close)
                throw new InvalidOperationException(
                    $"the bridge closed: {socket.CloseStatusDescription}");

            message.Write(buffer, 0, result.Count);

            if (result.EndOfMessage) return message.ToArray();

        }

    }

}
