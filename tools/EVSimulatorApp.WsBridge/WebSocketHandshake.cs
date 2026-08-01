using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace EVSimulatorApp.WsBridge;

/// <summary>
/// The RFC 6455 opening handshake, server side.
/// </summary>
/// <remarks>
/// <para>
/// Hand-written rather than <c>HttpListener.AcceptWebSocketAsync</c>, which is Windows-only in
/// practice — and this tool's whole point is to run next to a station, on whatever is there.
/// Everything after the handshake is <c>WebSocket.CreateFromStream</c>: the framing, masking and
/// close protocol are the framework's, and only the upgrade is here.
/// </para>
/// <para>
/// The request is read a byte at a time. Nothing follows the header before the response is sent, so
/// buffering would be safe *today*, and the day a client pipelined one frame after the request the
/// bridge would silently eat it. The header is capped so a peer that never sends the blank line
/// cannot make this allocate for ever.
/// </para>
/// </remarks>
public static class WebSocketHandshake
{

    /// <summary>RFC 6455 §1.3: the magic appended to the key before hashing. Not a secret.</summary>
    private const string Guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /// <summary>How much request head this will read before giving up.</summary>
    private const int MaximumHeaderBytes = 8 * 1024;


    /// <param name="Target">The request target, e.g. <c>/?host=192.168.4.1&amp;port=15118</c>.</param>
    /// <param name="Headers">Field names lower-cased; a repeated field keeps the first value.</param>
    public sealed record Request(String Target, IReadOnlyDictionary<String, String> Headers)
    {
        public String? Origin => Headers.GetValueOrDefault("origin");
    }


    /// <summary>
    /// Reads the request, answers <c>101</c>, and hands back what the client asked for.
    /// </summary>
    /// <exception cref="HandshakeRefusedException">
    /// when it is not a WebSocket upgrade this bridge will serve. The caller has already written the
    /// HTTP error by then — a refusal a browser cannot see is a refusal nobody can act on.
    /// </exception>
    public static async Task<Request> AcceptAsync(Stream stream, CancellationToken cancel = default)
    {

        var head = await ReadHeadAsync(stream, cancel);
        var lines = head.Split("\r\n", StringSplitOptions.RemoveEmptyEntries);

        if (lines.Length == 0)
            throw await RefuseAsync(stream, 400, "empty request", cancel);

        var requestLine = lines[0].Split(' ');

        if (requestLine.Length < 3 || !requestLine[0].Equals("GET", StringComparison.Ordinal))
            throw await RefuseAsync(stream, 405, "a WebSocket upgrade is a GET", cancel);

        var headers = new Dictionary<String, String>(StringComparer.Ordinal);

        foreach (var line in lines.Skip(1))
        {
            var colon = line.IndexOf(':');
            if (colon < 0) continue;

            var name = line[..colon].Trim().ToLowerInvariant();
            // First value wins, and a repeat is not merged: RFC 6455's fields are single-valued, and
            // merging them is how a request means two things at once to two readers.
            if (!headers.ContainsKey(name))
                headers[name] = line[(colon + 1)..].Trim();
        }

        if (!Contains(headers, "upgrade", "websocket"))
            throw await RefuseAsync(stream, 400, "not an upgrade to websocket", cancel);

        if (!Contains(headers, "connection", "upgrade"))
            throw await RefuseAsync(stream, 400, "the Connection field does not say upgrade", cancel);

        if (headers.GetValueOrDefault("sec-websocket-version") != "13")
            throw await RefuseAsync(stream, 426, "this bridge speaks WebSocket version 13", cancel);

        var key = headers.GetValueOrDefault("sec-websocket-key");

        if (String.IsNullOrEmpty(key))
            throw await RefuseAsync(stream, 400, "no Sec-WebSocket-Key", cancel);

        var response = "HTTP/1.1 101 Switching Protocols\r\n"
                     + "Upgrade: websocket\r\n"
                     + "Connection: Upgrade\r\n"
                     + $"Sec-WebSocket-Accept: {Accept(key)}\r\n\r\n";

        await stream.WriteAsync(Encoding.ASCII.GetBytes(response), cancel);
        await stream.FlushAsync(cancel);

        return new Request(requestLine[1], headers);

    }


    /// <summary>
    /// The <c>Sec-WebSocket-Accept</c> value for a key: base64(SHA-1(key + GUID)).
    /// </summary>
    /// <remarks>
    /// SHA-1 here is not a security choice and not ours: RFC 6455 §4.2.2 specifies it, and its
    /// purpose is to prove the server understood the upgrade rather than to withstand anything.
    /// </remarks>
    public static String Accept(String key)
        => Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key + Guid)));


    private static bool Contains(Dictionary<String, String> headers, String name, String token)
        => headers.GetValueOrDefault(name)?
                  .Split(',')
                  .Any(t => t.Trim().Equals(token, StringComparison.OrdinalIgnoreCase)) == true;


    private static async Task<HandshakeRefusedException> RefuseAsync(
        Stream stream, int status, String why, CancellationToken cancel)
    {

        var body = Encoding.UTF8.GetBytes(why + "\n");

        var head = $"HTTP/1.1 {status} {why}\r\n"
                 + "Content-Type: text/plain; charset=utf-8\r\n"
                 + $"Content-Length: {body.Length}\r\n"
                 + "Connection: close\r\n\r\n";

        try
        {
            await stream.WriteAsync(Encoding.ASCII.GetBytes(head), cancel);
            await stream.WriteAsync(body, cancel);
            await stream.FlushAsync(cancel);
        }
        catch (IOException) { /* the client gave up first; the refusal still stands */ }
        catch (SocketException) { }

        return new HandshakeRefusedException(why);

    }


    private static async Task<String> ReadHeadAsync(Stream stream, CancellationToken cancel)
    {

        var head = new List<byte>(512);
        var one  = new byte[1];

        while (true)
        {

            if (head.Count >= MaximumHeaderBytes)
                throw new HandshakeRefusedException(
                    $"the request head passed {MaximumHeaderBytes} bytes without ending");

            var n = await stream.ReadAsync(one, cancel);
            if (n == 0)
                throw new HandshakeRefusedException("the client closed before finishing its request");

            head.Add(one[0]);

            if (head.Count >= 4 &&
                head[^4] == (byte) '\r' && head[^3] == (byte) '\n' &&
                head[^2] == (byte) '\r' && head[^1] == (byte) '\n')
                return Encoding.UTF8.GetString(CollectionsMarshalAsSpan(head)[..^4]);

        }

    }

    private static Span<byte> CollectionsMarshalAsSpan(List<byte> list)
        => System.Runtime.InteropServices.CollectionsMarshal.AsSpan(list);

}


/// <summary>A connection that will not become a WebSocket, and why.</summary>
public sealed class HandshakeRefusedException(String message) : Exception(message);
