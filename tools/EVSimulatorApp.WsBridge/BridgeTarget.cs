using System.Diagnostics.CodeAnalysis;
using System.Web;

using EVSimulatorApp.Pairing;

namespace EVSimulatorApp.WsBridge;

/// <summary>
/// Where a client may ask to be forwarded.
/// </summary>
/// <remarks>
/// <para>
/// <b>The point of this type is that a browser does not get to choose freely.</b> A WebSocket server
/// has no same-origin protection: once this is listening, any page the browser happens to load can
/// open a connection to it. If the target came straight out of the query string, the bridge would be
/// an open proxy into whatever network it is standing on — reachable by any tab.
/// </para>
/// <para>
/// So the same rule the confirmation sheet applies to a scanned code applies here, and it is
/// literally the same code: <see cref="PairingWarnings.IsPrivateTarget"/>. A private or link-local
/// address, or a <c>.local</c> name, and nothing else. Where an operator wants it narrower still,
/// <c>--allow</c> pins the exact host and port.
/// </para>
/// <para>
/// None of that makes this safe to run on a machine full of other people's services. It makes it a
/// development tool with one lock rather than none — see the README.
/// </para>
/// </remarks>
public sealed record BridgeTarget(String Host, int Port)
{

    public override String ToString() => $"{Host}:{Port}";


    /// <summary>
    /// Reads the target out of a request target such as <c>/?host=192.168.4.1&amp;port=15118</c>.
    /// </summary>
    /// <param name="allowed">
    /// When non-empty, the only targets that may be reached at all. An empty list means "anything the
    /// private-range rule permits", which is the default and the looser of the two.
    /// </param>
    public static bool TryParse(String requestTarget,
                                IReadOnlyCollection<BridgeTarget> allowed,
                                [NotNullWhen(true)] out BridgeTarget? target,
                                [NotNullWhen(false)] out String? refusal)
    {

        target = null;

        var question = requestTarget.IndexOf('?');
        var query    = HttpUtility.ParseQueryString(question < 0 ? "" : requestTarget[(question + 1)..]);

        var host = query["host"];
        var port = query["port"];

        if (String.IsNullOrEmpty(host))
        {
            refusal = "no 'host' in the request target";
            return false;
        }

        if (!int.TryParse(port, out var portNumber) || portNumber is < 1 or > 65535)
        {
            refusal = $"'{port}' is not a TCP port";
            return false;
        }

        // Before the allow-list, not after: a target that is refused on principle should say so
        // whether or not somebody also listed it.
        if (!PairingWarnings.IsPrivateTarget(host))
        {
            refusal = $"'{host}' is not a private or link-local address. This bridge forwards to a "
                    + "station in the room, not to the internet.";
            return false;
        }

        var candidate = new BridgeTarget(host, portNumber);

        if (allowed.Count > 0 && !allowed.Contains(candidate))
        {
            refusal = $"{candidate} is not among the targets this bridge was started with";
            return false;
        }

        target  = candidate;
        refusal = null;
        return true;

    }


    /// <summary>Reads a <c>--allow</c> argument: <c>host:port</c>, with IPv6 in brackets.</summary>
    public static BridgeTarget Parse(String hostAndPort)
    {

        var text = hostAndPort.Trim();

        // [fe80::1%en0]:15118 — the brackets exist because a bare IPv6 literal is all colons, and
        // "split on the last colon" turns fe80::1 into host "fe80:" port ":1".
        if (text.StartsWith('['))
        {
            var close = text.IndexOf(']');
            if (close < 0 || close + 2 > text.Length || text[close + 1] != ':')
                throw new ArgumentException($"'{hostAndPort}' is not [host]:port.");

            return new BridgeTarget(text[1..close], ParsePort(text[(close + 2)..], hostAndPort));
        }

        var colon = text.LastIndexOf(':');
        if (colon < 0)
            throw new ArgumentException($"'{hostAndPort}' has no port. Use host:port.");

        return new BridgeTarget(text[..colon], ParsePort(text[(colon + 1)..], hostAndPort));

    }

    private static int ParsePort(String text, String whole)
        => int.TryParse(text, out var port) && port is >= 1 and <= 65535
               ? port
               : throw new ArgumentException($"'{whole}' has no usable port.");

}
