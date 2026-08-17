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
using System.Net.Sockets;
using System.Text.Json.Nodes;

using NUnit.Framework;

namespace EVSimulatorApp.WsBridge.Tests;

/// <summary>
/// A station that answers exactly what one recorded session recorded, over a real socket.
/// </summary>
/// <remarks>
/// <para>
/// The C# twin of the ones in <c>kotlin/v2g-session</c> and <c>swift/Tests/V2GSessionTests</c>, and
/// here for the same reason: it writes each response in <see cref="Fragment"/>-byte pieces, so every
/// frame arrives across several reads. That is the one thing an in-memory stream can never produce,
/// and it is exactly what <see cref="V2GFrameReader"/> exists to survive — a bridge that assumed one
/// read per frame would forward halves of messages to a browser and look fine doing it.
/// </para>
/// <para>
/// It also compares each request against the recording before answering, so a bridge that altered
/// what passed through it fails here rather than looking like a success.
/// </para>
/// <para>
/// Loopback only, and the port is whatever was free.
/// </para>
/// </remarks>
public sealed class RecordedStation : IAsyncDisposable
{

    /// <summary>Small enough that every frame in the corpus is split several times.</summary>
    private const int Fragment = 3;

    private readonly JsonArray   exchanges;
    private readonly TcpListener listener;
    private readonly Task        serving;
    private readonly CancellationTokenSource stopping = new();

    public int     Port      { get; }
    public string? Complaint { get; private set; }
    public int     Served    { get; private set; }
    public int     Exchanges => exchanges.Count;


    public RecordedStation(JsonNode trace)
    {

        exchanges = trace["exchanges"]!.AsArray();

        listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        Port = ((IPEndPoint) listener.LocalEndpoint).Port;

        serving = Task.Run(ServeAsync);

    }


    private async Task ServeAsync()
    {

        try
        {

            using var client = await listener.AcceptTcpClientAsync(stopping.Token);
            await using var stream = client.GetStream();

            foreach (var exchange in exchanges)
            {

                var expected = Convert.FromHexString(exchange!["request"]!["frame"]!.GetValue<string>());
                var actual   = new byte[expected.Length];

                var read = 0;
                while (read < actual.Length)
                {
                    var n = await stream.ReadAsync(actual.AsMemory(read, actual.Length - read), stopping.Token);
                    if (n == 0) return;                    // the client hung up; Served says how far
                    read += n;
                }

                if (!actual.AsSpan().SequenceEqual(expected))
                {
                    Complaint = $"exchange {Served}: got {Convert.ToHexString(actual).ToLowerInvariant()}, "
                              + $"the recording has {Convert.ToHexString(expected).ToLowerInvariant()}";
                    return;
                }

                var response = exchange["response"];
                if (response is null) return;

                var frame = Convert.FromHexString(response["frame"]!.GetValue<string>());

                // In pieces, on purpose — see the type comment.
                for (var at = 0; at < frame.Length; at += Fragment)
                {
                    await stream.WriteAsync(frame.AsMemory(at, Math.Min(Fragment, frame.Length - at)),
                                            stopping.Token);
                    await stream.FlushAsync(stopping.Token);
                }

                Served++;

            }

        }
        catch (OperationCanceledException) { }
        catch (Exception e) { Complaint ??= $"the station stopped: {e.Message}"; }

    }


    public async ValueTask DisposeAsync()
    {
        await stopping.CancelAsync();
        listener.Stop();
        try { await serving; } catch { /* stopping */ }
        stopping.Dispose();
    }


    /// <summary>One recorded session, from the corpus the simulation tests generate.</summary>
    /// <remarks>
    /// The corpus is the ISO15118ConformanceTests repo's — it lives with the C# session tests that
    /// record it, in the parent that carries this app as a submodule. Tests replaying it therefore
    /// run under conformance and are ignored when the app is checked out alone.
    /// </remarks>
    public static JsonNode Load(string name)
    {

        var directory = new DirectoryInfo(TestContext.CurrentContext.TestDirectory);
        while (directory is not null && !File.Exists(Path.Combine(directory.FullName, "EVSimulatorApp.slnx")))
            directory = directory.Parent;

        // vectors/ is in this repository. It was read out of the conformance parent as
        // `../../ISO15118ConformanceTests.Simulation/Vectors` until 2026-08-17, and that path outlived the
        // move because the sweep covered every extension the ports use but not *.cs — kept alive by the
        // Assert.Ignore that used to stand here, which turned a stale path into a quiet skip.
        var path = Path.Combine(directory?.FullName ?? throw new DirectoryNotFoundException("repository root"),
                                "vectors",
                                $"Session.{name}.trace.json");

        Assert.That(File.Exists(path), Is.True,
                    $"the session trace is missing at {path}. vectors/ is committed; "
                  + "`git checkout -- vectors` restores it.");

        return JsonNode.Parse(File.ReadAllText(path))!;

    }

}
