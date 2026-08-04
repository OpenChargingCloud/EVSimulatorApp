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

using System.Net;

using EVSimulatorApp.WsBridge;

// The WebSocket-to-TCP bridge (docs/CONCEPT.md B1). See tools/EVSimulatorApp.WsBridge/README.md for
// what it is for and what it deliberately is not.

var listen  = new IPEndPoint(IPAddress.Loopback, 9000);
var allowed = new List<BridgeTarget>();
var tls     = false;
String? pin = null;

try
{
    for (var i = 0; i < args.Length; i++)
    {
        switch (args[i])
        {
            case "--listen": listen = ParseEndPoint(Next(args, ref i)); break;
            case "--allow":  allowed.Add(BridgeTarget.Parse(Next(args, ref i))); break;
            case "--tls":    tls = true; break;
            case "--root":   pin = Next(args, ref i); break;

            case "-h" or "--help":
                Console.WriteLine(Usage);
                return 0;

            default:
                Console.Error.WriteLine($"unknown argument '{args[i]}'\n\n{Usage}");
                return 2;
        }
    }

    if (tls && pin is null)
    {
        Console.Error.WriteLine(
            "--tls without --root.\n\n" +
            "The browser cannot check a certificate chain — that is the reason this bridge\n" +
            "terminates TLS at all. Without a pinned root nothing checks it, and a TLS session\n" +
            "nobody validated is a plaintext session with extra steps.\n");
        return 2;
    }
}
catch (ArgumentException e)
{
    Console.Error.WriteLine($"{e.Message}\n\n{Usage}");
    return 2;
}

if (!IPAddress.IsLoopback(listen.Address))
    Console.WriteLine(
        $"warning: listening on {listen.Address}, not loopback. Any host that can reach this port\n" +
        $"         can reach a station through it. See the README.");

var bridge = new WsToTcpBridge(new WsToTcpBridge.Options(listen, allowed, tls, pin));

bridge.Start();

if (allowed.Count > 0)
    Console.WriteLine("forwarding only to: " + String.Join(", ", allowed));
else
    Console.WriteLine("forwarding to any private or link-local target the client names");

using var stopping = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; stopping.Cancel(); };

await bridge.RunAsync(stopping.Token);

return 0;


static String Next(String[] args, ref int i)
    => ++i < args.Length ? args[i] : throw new ArgumentException($"'{args[i - 1]}' needs a value.");


static IPEndPoint ParseEndPoint(String text)
    => IPEndPoint.TryParse(text, out var endpoint)
           ? endpoint
           : throw new ArgumentException($"'{text}' is not an address:port.");


partial class Program
{
    private const String Usage = """
        ev-ws-bridge — forwards WebSocket connections to a station over TCP, one V2GTP frame per
                       message, so a browser can drive and watch a session.

          --listen <address:port>   where to accept WebSocket connections   (default 127.0.0.1:9000)
          --allow  <host:port>      a station that may be reached; repeatable. Without it, any
                                    private or link-local target the client names is allowed.
          --tls                     speak TLS to the station
          --root   <sha256-hex>     the station root to pin, as a pairing code's `root` carries it.
                                    Required with --tls.

        A client connects to ws://127.0.0.1:9000/?host=192.168.4.1&port=15118 .

        This is a development and inspection tool. What crosses to the station is exactly what an
        EVCC would send; what crosses to the browser is not ISO 15118 on the wire, and is not
        conformance evidence.
        """;
}
