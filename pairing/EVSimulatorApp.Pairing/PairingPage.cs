using System.Net;
using System.Text;

namespace EVSimulatorApp.Pairing;

/// <summary>
/// Renders the Pi's pairing display: the current code, what it declares, and when it changes.
/// </summary>
/// <remarks>
/// <para>
/// Pure rendering — no HTTP server, no timer, no sockets. That is deliberate rather than partial:
/// the whole risk this page carries is that <b>what it shows drifts from what the station actually
/// does</b>, and that risk is a function of its inputs, not of how it is served. Keeping it a
/// function of (payload, code, remaining) makes the drift testable; hosting it is a few lines
/// somewhere else that nothing depends on.
/// </para>
/// <para>
/// The QR itself is drawn in the browser by <c>QRCodeSVG</c>, vendored under
/// <c>libs/DynamicQRCodes/TOTP/JavaScript-Web/QRCodeSVG/</c>. Served locally and never from a CDN:
/// a display whose code is fetched from the internet is a display someone else can change.
/// </para>
/// </remarks>
public static class PairingPage
{
    /// <summary>Where the vendored QR renderer is expected, relative to the page.</summary>
    public const string QrScriptPath = "qrcode.min.js";

    /// <summary>
    /// Renders the page for one slot.
    /// </summary>
    /// <param name="payload">
    /// The station's live configuration. Everything shown is derived from it, so the page cannot
    /// claim a profile the station is not running — the reason §4.5 asks for the generator to live
    /// on the Pi in the first place.
    /// </param>
    /// <param name="totp">The code for this slot, or null for a static (non-rotating) display.</param>
    /// <param name="remaining">How long this code lasts; drives the page's own refresh.</param>
    public static string Render(PairingPayload payload, string? totp, TimeSpan remaining)
    {
        var shown = totp is null ? payload : payload with { Totp = totp };
        var uri   = PairingUri.Format(shown);

        var html = new StringBuilder();
        html.Append("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>EV Simulator — pairing</title>
            <style>
              body { font-family: system-ui, sans-serif; margin: 2rem; }
              #qr svg { width: min(70vw, 22rem); height: auto; }
              dl { display: grid; grid-template-columns: max-content 1fr; gap: .25rem 1rem; }
              dt { font-weight: 600; }
              .warn { color: #a00; font-weight: 600; }
              .uri { word-break: break-all; font-family: ui-monospace, monospace; font-size: .8rem; }
            </style></head><body>
            <h1>Scan to pair</h1>
            <div id="qr"></div>

            """);

        html.Append("<dl>");
        Row(html, "Endpoint", $"{payload.Host} : {payload.Port}");
        Row(html, "Transport", payload.Transport is PairingTransport.Tcp ? "TCP (plaintext)" : "TLS");
        Row(html, "Crypto", payload.Crypto ?? "(unstated)");
        if (payload.Protocols.Count > 0) Row(html, "Protocols", string.Join(", ", payload.Protocols));
        if (payload.EvseId is not null) Row(html, "EVSE", payload.EvseId);
        if (payload.RootFingerprint is not null) Row(html, "Root CA", payload.RootFingerprint);
        if (payload.Meter is not null) Row(html, "Meter key", payload.Meter);
        html.Append("</dl>\n");

        // The station's own warnings about itself, shown here rather than only on the phone. A Pi
        // that is running a weakened profile should say so on its own display; the operator standing
        // in front of it is the person who can fix it.
        var warnings = payload.Warnings.Where(w => w.Kind is not PairingWarningKind.NoProximityProof
                                                        || totp is null).ToList();
        if (warnings.Count > 0)
        {
            html.Append("<h2>This code declares</h2>\n<ul>\n");
            foreach (var w in warnings)
                html.Append($"  <li class=\"warn\">{Escape(w.Kind.ToString())}: {Escape(w.Detail)}</li>\n");
            html.Append("</ul>\n");
        }

        html.Append($"<p class=\"uri\">{Escape(uri)}</p>\n");

        if (totp is not null)
            html.Append($"<p>This code changes in <span id=\"left\">{(int) remaining.TotalSeconds}</span> s.</p>\n");

        // Not interpolated: the script body has braces of its own, and doubling them to satisfy the
        // interpolator would make this unreadable for no gain.
        html.Append("""
            <script src="@SCRIPT@"></script>
            <script>
              // The URI is written as a JSON string literal, so nothing in it can end the script.
              new QRCode(document.getElementById('qr'), { text: @URI@, useSVG: true });
            </script>
            """.Replace("@SCRIPT@", QrScriptPath)
               .Replace("@URI@", JsonString(uri)));

        if (totp is not null)
            html.Append("""

                <script>
                  // Reload just after this slot ends, so the page never shows a code the station has
                  // already stopped accepting. Reloading early would be the opposite failure: the
                  // next code shown before the station honours it.
                  setTimeout(() => location.reload(), @MS@);
                  // The countdown is why the span has an id — someone deciding whether to scan now
                  // wants to know whether there is time.
                  const left = document.getElementById('left');
                  setInterval(() => { left.textContent = Math.max(0, +left.textContent - 1); }, 1000);
                </script>
                """.Replace("@MS@", ((int) remaining.TotalMilliseconds + 250).ToString()));

        html.Append("\n</body></html>\n");
        return html.ToString();
    }

    private static void Row(StringBuilder html, string name, string value) =>
        html.Append($"<dt>{Escape(name)}</dt><dd>{Escape(value)}</dd>\n");

    /// <summary>
    /// HTML-escapes. Not optional here: several of these strings are <b>not ours</b> — the
    /// non-conformance reason is written by whoever configured the station, and the meter id and
    /// EVSE id come from configuration too. A status page that renders them as markup is an
    /// injection hole on the one screen an operator is meant to trust.
    /// </summary>
    private static string Escape(string s) => WebUtility.HtmlEncode(s);

    /// <summary>A JSON string literal, so the URI cannot break out of the script element.</summary>
    private static string JsonString(string s) =>
        System.Text.Json.JsonSerializer.Serialize(s);
}
