using System.Text;

namespace EVSimulatorApp.Pairing;

/// <summary>
/// The pairing URI: an HTTPS universal link carrying its data in the <b>fragment</b>.
/// </summary>
/// <remarks>
/// <para>
/// <c>https://open.charging.cloud/evsim/pair#v=1&amp;host=…&amp;port=…</c>
/// </para>
/// <para>
/// The fragment is the whole point and not a style choice: <b>fragments are never sent to a
/// server.</b> A code scanned with the stock camera app opens a harmless landing page that can
/// explain what the app is, while the endpoint, crypto profile and fingerprints stay on the device.
/// Putting the same data in the query string would hand every scan to whoever runs the host — which
/// is why <see cref="Parse"/> reads the fragment and nothing else, and why a query-form code parses
/// as empty rather than working by accident.
/// </para>
/// <para>
/// The parameter names are deliberately a <em>superset</em> of the OCPP v2.1 / AFIR
/// <c>ProcessURLTemplate</c> vocabulary (§4.5), so a conformant AFIR code partially works here
/// instead of being a parallel invention.
/// </para>
/// </remarks>
public static class PairingUri
{
    public const string DefaultBase = "https://open.charging.cloud/evsim/pair";

    /// <summary>The custom scheme accepted as an alias for in-app scanning.</summary>
    public const string AltScheme = "v2gsim";

    // Parameter names. Those marked AFIR come from the OCPP v2.1 template vocabulary.
    private const string KeyVersion = "v";           // AFIR: {version}
    private const string KeyTotp    = "totp";        // AFIR: {totp}
    private const string KeyEvseId  = "evseId";      // AFIR
    private const string KeyTariff  = "tariffId";    // AFIR
    private const string KeyCurrency = "currency";   // AFIR
    private const string KeyLanguage = "uiLanguage"; // AFIR
    private const string KeyHost    = "host";
    private const string KeyPort    = "port";
    private const string KeyTp      = "tp";
    private const string KeyProto   = "proto";
    private const string KeyCrypto  = "crypto";
    private const string KeyNc      = "nc";
    private const string KeyNcWhy   = "ncwhy";
    private const string KeyRoot    = "root";
    private const string KeyMeter   = "meter";
    private const string KeyWifi    = "wifi";

    private static readonly string[] Known =
    [
        KeyVersion, KeyTotp, KeyEvseId, KeyTariff, KeyCurrency, KeyLanguage,
        KeyHost, KeyPort, KeyTp, KeyProto, KeyCrypto, KeyNc, KeyNcWhy, KeyRoot, KeyMeter, KeyWifi,
    ];

    /// <summary>
    /// Parses a scanned string. Returns null if it is not a pairing code at all; throws
    /// <see cref="PairingFormatException"/> if it is one but malformed.
    /// </summary>
    /// <remarks>
    /// The distinction matters at the scanner: "that was some other QR code" is a shrug, while "that
    /// was a pairing code and it is broken" is worth telling the user about.
    /// </remarks>
    public static PairingPayload? Parse(string text)
    {
        if (!Uri.TryCreate(text.Trim(), UriKind.Absolute, out var uri))
            return null;

        var isPairing = uri.Scheme.Equals(AltScheme, StringComparison.OrdinalIgnoreCase)
                     || (uri.Scheme is "https" && uri.AbsolutePath.EndsWith("/pair", StringComparison.Ordinal));
        if (!isPairing)
            return null;

        // Deliberately the fragment only — see the class remarks.
        var fragment = uri.Fragment.TrimStart('#');
        if (fragment.Length == 0)
            throw new PairingFormatException("the pairing code carries no fragment; "
                                           + "data in the query string is not read, because a query is sent to the server");

        var fields = ParseFields(fragment);

        var version = Require(fields, KeyVersion);
        if (!int.TryParse(version, out var versionNumber))
            throw new PairingFormatException($"version '{version}' is not a number");

        var host = Require(fields, KeyHost);
        var port = Require(fields, KeyPort);
        if (!int.TryParse(port, out var portNumber) || portNumber is < 1 or > 65535)
            throw new PairingFormatException($"port '{port}' is not a port number");

        var transport = fields.GetValueOrDefault(KeyTp) switch
        {
            null or "tls" => PairingTransport.Tls,
            "tcp"         => PairingTransport.Tcp,
            var other     => throw new PairingFormatException($"unknown transport '{other}'"),
        };

        var (ssid, psk) = SplitWifi(fields.GetValueOrDefault(KeyWifi));

        return new PairingPayload
        {
            Version              = versionNumber,
            Host                 = host,
            Port                 = portNumber,
            Transport            = transport,
            Protocols            = fields.GetValueOrDefault(KeyProto)?.Split(',',
                                        StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries) ?? [],
            Crypto               = fields.GetValueOrDefault(KeyCrypto),
            NonConformant        = fields.GetValueOrDefault(KeyNc) is "1" or "true",
            NonConformanceReason = fields.GetValueOrDefault(KeyNcWhy),
            RootFingerprint      = fields.GetValueOrDefault(KeyRoot)?.ToLowerInvariant(),
            Meter                = fields.GetValueOrDefault(KeyMeter),
            Totp                 = fields.GetValueOrDefault(KeyTotp),
            EvseId               = fields.GetValueOrDefault(KeyEvseId),
            TariffId             = fields.GetValueOrDefault(KeyTariff),
            Currency             = fields.GetValueOrDefault(KeyCurrency),
            UiLanguage           = fields.GetValueOrDefault(KeyLanguage),
            WifiSsid             = ssid,
            WifiPsk              = psk,
            Extra                = fields.Where(kv => !Known.Contains(kv.Key))
                                         .ToDictionary(kv => kv.Key, kv => kv.Value),
        };
    }

    /// <summary>Renders a payload back to a URI — the Pi's display page's job.</summary>
    /// <remarks>
    /// Round-tripping is asserted by the tests rather than assumed: the Pi and the app must agree on
    /// this format exactly, and they are the two halves that never run in the same process.
    /// </remarks>
    public static string Format(PairingPayload payload, string baseUri = DefaultBase)
    {
        var f = new List<(string Key, string? Value)>
        {
            (KeyVersion,  payload.Version.ToString()),
            (KeyHost,     payload.Host),
            (KeyPort,     payload.Port.ToString()),
            (KeyTp,       payload.Transport is PairingTransport.Tcp ? "tcp" : "tls"),
            (KeyProto,    payload.Protocols.Count > 0 ? string.Join(",", payload.Protocols) : null),
            (KeyCrypto,   payload.Crypto),
            (KeyNc,       payload.NonConformant ? "1" : null),
            (KeyNcWhy,    payload.NonConformanceReason),
            (KeyRoot,     payload.RootFingerprint),
            (KeyMeter,    payload.Meter),
            (KeyTotp,     payload.Totp),
            (KeyEvseId,   payload.EvseId),
            (KeyTariff,   payload.TariffId),
            (KeyCurrency, payload.Currency),
            (KeyLanguage, payload.UiLanguage),
            (KeyWifi,     payload.WifiSsid is null ? null
                                                   : payload.WifiPsk is null
                                                       ? Escape(payload.WifiSsid)
                                                       : $"{Escape(payload.WifiSsid)}:{Escape(payload.WifiPsk)}"),
        };

        f.AddRange(payload.Extra.OrderBy(kv => kv.Key, StringComparer.Ordinal)
                                .Select(kv => (kv.Key, (string?) kv.Value)));

        var body = string.Join("&", f.Where(x => x.Value is not null)
                                     .Select(x => $"{x.Key}={Uri.EscapeDataString(x.Value!)}"));
        return $"{baseUri}#{body}";
    }

    private static Dictionary<string, string> ParseFields(string fragment)
    {
        var fields = new Dictionary<string, string>(StringComparer.Ordinal);

        foreach (var pair in fragment.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var at = pair.IndexOf('=');
            if (at < 0)
                throw new PairingFormatException($"'{pair}' is not a key=value pair");

            var key = pair[..at];
            // First occurrence wins. A repeated key is how a hostile code smuggles a second value
            // past a reader that shows the first one, so it is refused rather than resolved.
            if (!fields.TryAdd(key, Uri.UnescapeDataString(pair[(at + 1)..])))
                throw new PairingFormatException($"parameter '{key}' appears more than once");
        }

        return fields;
    }

    private static string Require(Dictionary<string, string> fields, string key) =>
        fields.TryGetValue(key, out var value) && value.Length > 0
            ? value
            : throw new PairingFormatException($"required parameter '{key}' is missing");

    /// <summary>`ssid:psk`, with `\:` for a colon inside either half.</summary>
    private static (string? Ssid, string? Psk) SplitWifi(string? wifi)
    {
        if (wifi is null) return (null, null);

        var sb = new StringBuilder();
        for (var i = 0; i < wifi.Length; i++)
        {
            if (wifi[i] == '\\' && i + 1 < wifi.Length) { sb.Append(wifi[++i]); continue; }
            if (wifi[i] == ':') return (sb.ToString(), Unescape(wifi[(i + 1)..]));
            sb.Append(wifi[i]);
        }
        return (sb.ToString(), null);
    }

    private static string Escape(string s) => s.Replace("\\", "\\\\").Replace(":", "\\:");

    private static string Unescape(string s)
    {
        var sb = new StringBuilder();
        for (var i = 0; i < s.Length; i++)
        {
            if (s[i] == '\\' && i + 1 < s.Length) { sb.Append(s[++i]); continue; }
            sb.Append(s[i]);
        }
        return sb.ToString();
    }
}

public sealed class PairingFormatException(string message) : Exception(message);
