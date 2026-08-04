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
    /// <param name="liveEndpoint">
    /// A WebSocket path to subscribe to, e.g. <c>/ws</c>. When given, the page redraws its QR from
    /// pushed messages instead of reloading, and gains a connection indicator and a log window.
    /// When null it is a self-contained page that reloads itself — which is what makes it testable
    /// and printable without a server behind it.
    /// </param>
    public static string Render(PairingPayload payload, string? totp, TimeSpan remaining,
                                string? liveEndpoint = null)
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
              #status { font-weight: 600; }
              #status.idle { color: #666; }
              #status.live { color: #070; }
              #log { background: #111; color: #ddd; font-family: ui-monospace, monospace;
                     font-size: .75rem; height: 16rem; overflow-y: auto; padding: .5rem;
                     white-space: pre-wrap; }
              #log .err { color: #f88; }
              #log .rx  { color: #8cf; }
              #log .tx  { color: #8f8; }
              .uri { word-break: break-all; font-family: ui-monospace, monospace; font-size: .8rem; }
            </style></head><body>
            <h1>Scan to pair</h1>
            <div id="qr"></div>

            """);

        if (liveEndpoint is not null)
            html.Append("""
                <p id="status" class="idle">No vehicle connected</p>

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

        // Carries an id because live mode rewrites it: the QR and this line are two projections of
        // the same string, and a page where only one of them rotates shows a URI that no longer
        // matches the code beside it — readable, copyable, and rejected by the station.
        html.Append($"<p class=\"uri\" id=\"uri\">{Escape(uri)}</p>\n");

        if (totp is not null)
            html.Append($"<p>This code changes in <span id=\"left\">{(int) remaining.TotalSeconds}</span> s.</p>\n");

        if (liveEndpoint is not null)
            html.Append("<h2>Session log</h2>\n<div id=\"log\"></div>\n");

        // Not interpolated: the script body has braces of its own, and doubling them to satisfy the
        // interpolator would make this unreadable for no gain.
        html.Append("""
            <script src="@SCRIPT@"></script>
            <script>
              // The URI is written as a JSON string literal, so nothing in it can end the script.
              //
              // datalog/qrcode-svg takes ONE argument — an options object keyed `msg` — and RETURNS
              // the <svg> for the caller to insert. It is not the davidshimjs/qrcodejs API of
              // `new QRCode(element, { text })`: passing an element there lands as the options
              // object, `msg` comes out undefined, and the library dutifully builds an empty QR that
              // nobody appends. No exception, no console error, no QR. Defaults are already right
              // for a display — dim 256, ecl M, and pad 4, the quiet zone the spec asks for.
              document.getElementById('qr').replaceChildren(QRCode({ msg: @URI@ }));
            </script>
            """.Replace("@SCRIPT@", QrScriptPath)
               .Replace("@URI@", JsonString(uri)));

        if (liveEndpoint is not null)
            html.Append("""

                <script>
                  // Live mode: the station pushes each new slot, so the page never reloads and never
                  // guesses. A reload-driven page and a station whose clock has drifted disagree
                  // silently; here the code on screen is the one the station just minted.
                  const qr      = document.getElementById('qr');
                  const uriText = document.getElementById('uri');
                  const status  = document.getElementById('status');
                  const log     = document.getElementById('log');
                  const left    = document.getElementById('left');

                  // Both projections of the slot's URI are written here, in one place: the QR and the
                  // line under it are the same string, and updating them from two callers is how they
                  // drift. textContent, not innerHTML — the URI is a value, never markup.
                  // One insertion rather than clear-then-draw: replaceChildren swaps the old QR for
                  // the new one, so the display never blanks between slots.
                  const draw = (uri) => { qr.replaceChildren(QRCode({ msg: uri }));
                                          uriText.textContent = uri; };

                  // textContent, never innerHTML: these lines carry peer-controlled text — an EVCC
                  // id, a TLS error — onto the operator's own screen.
                  const line = (at, level, text) => {
                    const el = document.createElement('div');
                    el.className = level;
                    el.textContent = at + '  ' + text;
                    log.appendChild(el);
                    while (log.childElementCount > 500) log.firstElementChild.remove();
                    log.scrollTop = log.scrollHeight;
                  };

                  const open = () => {
                    const ws = new WebSocket(new URL('@WS@', location.href.replace(/^http/, 'ws')));
                    ws.onmessage = (m) => {
                      const e = JSON.parse(m.data);
                      if (e.type === 'pairing') { draw(e.uri); left.textContent = e.seconds; }
                      if (e.type === 'status')  { status.textContent = e.connected
                                                    ? 'Vehicle connected — ' + (e.peer || '')
                                                    : 'No vehicle connected';
                                                  status.className = e.connected ? 'live' : 'idle'; }
                      if (e.type === 'log')     { line(e.at, e.level, e.text); }
                    };
                    // A dropped socket must not leave a stale code on screen looking valid.
                    ws.onclose = () => { status.textContent = 'Display disconnected';
                                         status.className = 'idle';
                                         setTimeout(open, 1000); };
                  };
                  open();

                  setInterval(() => { left.textContent = Math.max(0, +left.textContent - 1); }, 1000);
                </script>
                """.Replace("@WS@", JsonEscapeForUrl(liveEndpoint)));
        else if (totp is not null)
            html.Append("""

                <script>
                  // Standalone mode: reload just after this slot ends, so the page never shows a code
                  // the station has already stopped accepting. Reloading early would be the opposite
                  // failure — the next code shown before the station honours it.
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

    /// <summary>A path, restricted to what a path may contain — it lands inside a script.</summary>
    private static string JsonEscapeForUrl(string path)
    {
        foreach (var c in path)
            if (!char.IsAsciiLetterOrDigit(c) && c is not ('/' or '-' or '_' or '.'))
                throw new ArgumentException($"unsafe character '{c}' in the live endpoint path", nameof(path));
        return path;
    }

    /// <summary>A JSON string literal, so the URI cannot break out of the script element.</summary>
    private static string JsonString(string s) =>
        System.Text.Json.JsonSerializer.Serialize(s);
}
