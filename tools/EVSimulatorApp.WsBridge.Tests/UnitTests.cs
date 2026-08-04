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

using System.Text;
using System.Text.Json.Nodes;

using NUnit.Framework;

using cloud.charging.open.protocols.ISO15118.EXI.Dispatch;

namespace EVSimulatorApp.WsBridge.Tests;

/// <summary>The three pieces the bridge is made of, each against the thing that defines it.</summary>
[TestFixture]
public class HandshakeTests
{

    /// <summary>
    /// RFC 6455's own worked example.
    /// </summary>
    /// <remarks>
    /// §1.3 gives a key and the accept value a conformant server must answer with. That is an oracle
    /// from outside this repository, which is the only kind worth having for a wire format — the
    /// alternative is a test that agrees with whatever the implementation happens to compute.
    /// </remarks>
    [Test]
    public void TheAcceptValueIsTheOneRfc6455Prints()
        => Assert.That(WebSocketHandshake.Accept("dGhlIHNhbXBsZSBub25jZQ=="),
                       Is.EqualTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));


    [Test]
    public async Task AProperUpgradeIsAnsweredWith101AndTheRequestTarget()
    {

        var stream = new DuplexBuffer(
            "GET /?host=192.168.4.1&port=15118 HTTP/1.1\r\n" +
            "Host: 127.0.0.1:9000\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: keep-alive, Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Origin: http://localhost:4173\r\n\r\n");

        var request = await WebSocketHandshake.AcceptAsync(stream);

        Assert.Multiple(() => {
            Assert.That(request.Target, Is.EqualTo("/?host=192.168.4.1&port=15118"));
            Assert.That(request.Origin, Is.EqualTo("http://localhost:4173"));
            Assert.That(stream.Written, Does.StartWith("HTTP/1.1 101 Switching Protocols"));
            Assert.That(stream.Written, Does.Contain("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
        });

    }


    /// <summary>
    /// A `Connection` field that lists several tokens still says upgrade.
    /// </summary>
    /// <remarks>
    /// Firefox sends <c>keep-alive, Upgrade</c>. A server comparing the whole field to "Upgrade"
    /// refuses it, works perfectly in Chrome, and is found by whoever opens the other browser.
    /// </remarks>
    [Test]
    public async Task ARefusedRequestSaysWhyInHttpRatherThanClosing()
    {

        var stream = new DuplexBuffer(
            "GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: k\r\nSec-WebSocket-Version: 8\r\n\r\n");

        Assert.ThrowsAsync<HandshakeRefusedException>(() => WebSocketHandshake.AcceptAsync(stream));

        Assert.That(stream.Written, Does.StartWith("HTTP/1.1 426"));
        Assert.That(stream.Written, Does.Contain("version 13"));

        await Task.CompletedTask;

    }


    [Test]
    public void AHeadThatNeverEndsIsRefusedRatherThanBuffered()
    {
        var stream = new DuplexBuffer(new string('x', 16 * 1024));
        Assert.ThrowsAsync<HandshakeRefusedException>(() => WebSocketHandshake.AcceptAsync(stream));
    }

}


[TestFixture]
public class V2GFrameReaderTests
{

    private static byte[] Frame(ushort payloadType, int payloadLength)
    {
        var frame = new byte[V2GTP.HeaderSize + payloadLength];
        V2GTP.WriteHeader(frame, payloadType, (uint) payloadLength);
        for (var i = 0; i < payloadLength; i++) frame[V2GTP.HeaderSize + i] = (byte) i;
        return frame;
    }


    /// <summary>
    /// Frames come out whole however the bytes went in.
    /// </summary>
    /// <remarks>
    /// One byte at a time is the shape a real network produces and an in-memory stream never does.
    /// It is also the shape that breaks any reader which assumes one read per frame.
    /// </remarks>
    [Test]
    public async Task FramesComeOutWholeHoweverTheBytesWentIn()
    {

        var frames = new[] { Frame(0x8001, 5), Frame(0x8002, 0), Frame(0x8004, 300) };
        var all    = frames.SelectMany(f => f).ToArray();

        foreach (var chunk in new[] { 1, 3, 7, all.Length })
        {

            var reader = new V2GFrameReader(new DribblingStream(all, chunk));

            foreach (var expected in frames)
                Assert.That(await reader.ReadFrameAsync(), Is.EqualTo(expected), $"chunk size {chunk}");

            Assert.That(await reader.ReadFrameAsync(), Is.Null, "a clean end is a null, not a throw");

        }

    }


    /// <summary>
    /// A declared length nobody could mean is refused before it is allocated.
    /// </summary>
    /// <remarks>
    /// The header's length is a 32-bit count supplied by the peer, so believing it means letting
    /// whoever is on the other end choose an allocation of up to 4 GiB — from one 8-byte frame that
    /// carries no payload at all.
    /// </remarks>
    [Test]
    public void AnAbsurdDeclaredLengthIsRefusedRatherThanAllocated()
    {

        var header = new byte[V2GTP.HeaderSize];
        V2GTP.WriteHeader(header, 0x8001, 0xFFFFFFFF);

        var reader = new V2GFrameReader(new DribblingStream(header, 8));

        Assert.That(Assert.ThrowsAsync<V2GFramingException>(() => reader.ReadFrameAsync())!.Message,
                    Does.Contain("forwards at most"));

    }


    [Test]
    public void APeerThatClosesInsideAFrameIsNotACleanEnd()
    {

        var truncated = Frame(0x8001, 10)[..12];
        var reader    = new V2GFrameReader(new DribblingStream(truncated, 4));

        Assert.That(Assert.ThrowsAsync<V2GFramingException>(() => reader.ReadFrameAsync())!.Message,
                    Does.Contain("closed inside a frame"));

    }


    [Test]
    public void BytesThatAreNotAV2gtpHeaderAreNamedRatherThanSkipped()
    {

        var reader = new V2GFrameReader(new DribblingStream(Encoding.ASCII.GetBytes("GET / HTTP"), 10));

        Assert.That(Assert.ThrowsAsync<V2GFramingException>(() => reader.ReadFrameAsync())!.Message,
                    Does.Contain("not one"));

    }

}


[TestFixture]
public class BridgeTargetTests
{

    /// <summary>
    /// The bridge and the confirmation sheet permit the same hosts.
    /// </summary>
    /// <remarks>
    /// Held to the shared configuration corpus rather than to a list restated here: both sides call
    /// <c>PairingWarnings.IsPrivateTarget</c>, and this is what would notice if one of them stopped.
    /// </remarks>
    [Test]
    public void TheSameHostsTheConfirmationSheetPermits()
    {

        var directory = new DirectoryInfo(TestContext.CurrentContext.TestDirectory);
        while (directory is not null && !Directory.Exists(Path.Combine(directory.FullName, "libs/Vanaheimr.V2G.Exi")))
            directory = directory.Parent;

        var corpus = JsonNode.Parse(File.ReadAllText(Path.Combine(
                         directory!.FullName,
                         "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.config.json")))!;

        var checkedAccepted = 0;
        var checkedRefused  = 0;

        foreach (var entry in corpus["accepted"]!.AsArray())
        {
            var config = entry!["canonical"]!;
            var host   = config["host"]!.GetValue<string>();
            var port   = config["port"]!.GetValue<int>();

            Assert.That(BridgeTarget.TryParse($"/?host={Uri.EscapeDataString(host)}&port={port}",
                                              [], out _, out var refusal),
                        Is.True, $"{host}: {refusal}");
            checkedAccepted++;
        }

        // Only the host-shaped refusals: the corpus also refuses ports and enumerations, which this
        // parser has its own answers for, and a missing key is not a target question.
        foreach (var entry in corpus["refused"]!.AsArray())
        {
            // `as JsonObject` rather than indexing: the corpus refuses an array and a bare null too,
            // and asking either of those for a property throws rather than answering "no host".
            var host = (entry!["input"] as JsonObject)?["host"] as JsonValue is { } value &&
                       value.TryGetValue<string>(out var text) ? text : null;

            if (host is null or "") continue;
            if (!entry["message"]!.GetValue<string>().Contains("private or link-local")) continue;

            Assert.That(BridgeTarget.TryParse($"/?host={Uri.EscapeDataString(host)}&port=15118",
                                              [], out _, out var refusal),
                        Is.False, $"{host} was allowed through the bridge");
            Assert.That(refusal, Does.Contain("private or link-local"));
            checkedRefused++;
        }

        Assert.That(checkedAccepted, Is.GreaterThanOrEqualTo(8));
        Assert.That(checkedRefused,  Is.GreaterThanOrEqualTo(3));

    }


    /// <summary>An IPv6 target keeps its colons, which is why the bracket form exists.</summary>
    [Test]
    public void AnIPv6AllowArgumentIsNotSplitOnItsOwnColons()
    {
        Assert.Multiple(() => {
            Assert.That(BridgeTarget.Parse("[fe80::1%en0]:15118"),
                        Is.EqualTo(new BridgeTarget("fe80::1%en0", 15118)));
            Assert.That(BridgeTarget.Parse("192.168.4.1:15118"),
                        Is.EqualTo(new BridgeTarget("192.168.4.1", 15118)));
            Assert.Throws<ArgumentException>(() => BridgeTarget.Parse("192.168.4.1"));
            Assert.Throws<ArgumentException>(() => BridgeTarget.Parse("192.168.4.1:70000"));
        });
    }

}


/// <summary>A stream that hands over at most <paramref name="chunk"/> bytes per read.</summary>
file sealed class DribblingStream(byte[] content, int chunk) : Stream
{

    private int at;

    public override int Read(byte[] buffer, int offset, int count)
    {
        var n = Math.Min(Math.Min(chunk, count), content.Length - at);
        Array.Copy(content, at, buffer, offset, n);
        at += n;
        return n;
    }

    public override bool CanRead  => true;
    public override bool CanSeek  => false;
    public override bool CanWrite => false;
    public override long Length   => throw new NotSupportedException();
    public override long Position { get => at; set => throw new NotSupportedException(); }
    public override void Flush() { }
    public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
    public override void SetLength(long v)          => throw new NotSupportedException();
    public override void Write(byte[] b, int o, int c) => throw new NotSupportedException();

}


/// <summary>Reads a fixed request and remembers what was written back.</summary>
file sealed class DuplexBuffer(string request) : Stream
{

    private readonly byte[]       incoming = Encoding.ASCII.GetBytes(request);
    private readonly MemoryStream outgoing = new();
    private int at;

    public string Written => Encoding.ASCII.GetString(outgoing.ToArray());

    public override int Read(byte[] buffer, int offset, int count)
    {
        var n = Math.Min(count, incoming.Length - at);
        Array.Copy(incoming, at, buffer, offset, n);
        at += n;
        return n;
    }

    public override void Write(byte[] buffer, int offset, int count)
        => outgoing.Write(buffer, offset, count);

    public override bool CanRead  => true;
    public override bool CanSeek  => false;
    public override bool CanWrite => true;
    public override long Length   => throw new NotSupportedException();
    public override long Position { get => at; set => throw new NotSupportedException(); }
    public override void Flush() { }
    public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
    public override void SetLength(long v)          => throw new NotSupportedException();

}
