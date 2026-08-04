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

namespace EVSimulatorApp.Pairing;

/// <summary>
/// A scanned pairing code, parsed. What the QR stands in for is plug-in + SLAC + SDP together
/// (<c>docs/CONCEPT.md</c> §4.5), so it carries an endpoint <em>and</em> the transport and crypto
/// profile SDP's security byte would otherwise announce.
/// </summary>
/// <remarks>
/// <para>
/// Everything here is <b>untrusted input</b>. A pairing code is an image on a display that anyone
/// can tape over, and malicious stickers at public chargers are an established attack. So this type
/// deliberately <em>classifies</em> rather than decides: it reports what the code asks for and what
/// is wrong with it (<see cref="Warnings"/>), and the decision to connect belongs to a human looking
/// at a confirmation sheet.
/// </para>
/// <para>
/// Unknown parameters are kept in <see cref="Extra"/> rather than dropped or rejected. A newer Pi
/// must be able to talk to an older app, and — more to the point — an app that silently discards
/// what it does not understand cannot warn that the code contained something it could not read.
/// Nothing interprets <see cref="Extra"/>, which is exactly why carrying it is safe.
/// </para>
/// </remarks>
public sealed record PairingPayload
{
    /// <summary>Payload version. Only <c>1</c> is understood.</summary>
    public required int Version { get; init; }

    // ── The endpoint ────────────────────────────────────────────────────────────────────────────

    /// <summary>SECC host: IPv6 (including link-local with a zone), IPv4, or an mDNS name.</summary>
    public required string Host { get; init; }

    public required int Port { get; init; }

    /// <summary>Transport the SECC offers — the job SDP's security byte does in a real deployment.</summary>
    public required PairingTransport Transport { get; init; }

    /// <summary>
    /// Protocols offered, e.g. <c>iso2</c>, <c>iso20</c>. A <em>hint</em> only: SAP still runs for
    /// real, and a code claiming -20 does not make the session -20.
    /// </summary>
    public IReadOnlyList<string> Protocols { get; init; } = [];

    // ── The crypto profile, and its honesty ─────────────────────────────────────────────────────

    /// <summary>
    /// Curve the SECC wants for the -20 profile, e.g. <c>secp521r1</c> (conformant) or
    /// <c>secp256r1</c> (relaxed). Null means "unstated", which is not the same as conformant.
    /// </summary>
    public string? Crypto { get; init; }

    /// <summary>The peer's own declaration that it is not conformant.</summary>
    public bool NonConformant { get; init; }

    /// <summary>
    /// The peer's reason for <see cref="NonConformant"/>, <b>displayed verbatim</b> and never
    /// interpreted. The point is to turn a quiet deviation into an attributed one: relaxed mode is
    /// relaxed because the counterpart asked for it, on the record.
    /// </summary>
    public string? NonConformanceReason { get; init; }

    // ── Trust bootstrap ─────────────────────────────────────────────────────────────────────────

    /// <summary>
    /// SHA-256 fingerprint of the V2G Root CA, lower-case hex. Lets a freshly installed app validate
    /// the Pi's chain with no manual trust configuration — scan, pin, connect.
    /// </summary>
    public string? RootFingerprint { get; init; }

    /// <summary>Meter public key or id, enabling Chargy verification from first contact (§4.3).</summary>
    public string? Meter { get; init; }

    // ── Proximity proof ─────────────────────────────────────────────────────────────────────────

    /// <summary>
    /// The rotating TOTP (§4.6). This is what makes the code a <em>proximity proof</em> rather than
    /// a photograph: it is valid for its slot only, so a stolen image is worthless within ~30 s.
    /// </summary>
    public string? Totp { get; init; }

    // ── Session intent, from the OCPP v2.1 / AFIR vocabulary ────────────────────────────────────

    public string? EvseId { get; init; }
    public string? TariffId { get; init; }
    public string? Currency { get; init; }
    public string? UiLanguage { get; init; }

    /// <summary>SSID and PSK for the Pi's own access point, if it runs one.</summary>
    public string? WifiSsid { get; init; }
    public string? WifiPsk { get; init; }

    /// <summary>Parameters this build does not understand, carried and never interpreted.</summary>
    public IReadOnlyDictionary<string, string> Extra { get; init; } =
        new Dictionary<string, string>();

    /// <summary>
    /// Everything a confirmation sheet must show in red, in the order it should be shown.
    /// </summary>
    /// <remarks>
    /// Computed rather than stored, so it cannot fall out of step with the fields it describes —
    /// and returned as data rather than acted on, because "should I connect to this?" is a question
    /// for the person holding the phone.
    /// </remarks>
    public IReadOnlyList<PairingWarning> Warnings => PairingWarnings.For(this);

    // ── Equality ────────────────────────────────────────────────────────────────────────────────

    /// <summary>
    /// Structural equality, spelled out because a <c>record</c> does not give it here.
    /// </summary>
    /// <remarks>
    /// The synthesised <c>Equals</c> compares <see cref="Protocols"/> and <see cref="Extra"/> by
    /// <em>reference</em>, so two payloads parsed from the same string would come back unequal. That
    /// is a trap rather than a nuisance: "is this the same code the user already approved?" is a
    /// question a pairing flow will ask, and a type that looks like it answers it while comparing
    /// object identity answers it wrongly, silently, and only sometimes.
    /// </remarks>
    public bool Equals(PairingPayload? other) =>
        other is not null
     && (Version, Host, Port, Transport, Crypto, NonConformant, NonConformanceReason)
            == (other.Version, other.Host, other.Port, other.Transport, other.Crypto,
                other.NonConformant, other.NonConformanceReason)
     && (RootFingerprint, Meter, Totp, EvseId, TariffId, Currency, UiLanguage, WifiSsid, WifiPsk)
            == (other.RootFingerprint, other.Meter, other.Totp, other.EvseId, other.TariffId,
                other.Currency, other.UiLanguage, other.WifiSsid, other.WifiPsk)
     && Protocols.SequenceEqual(other.Protocols)
     && Extra.Count == other.Extra.Count
     && Extra.All(kv => other.Extra.TryGetValue(kv.Key, out var v) && v == kv.Value);

    public override int GetHashCode()
    {
        var hash = new HashCode();
        hash.Add(Version); hash.Add(Host); hash.Add(Port); hash.Add(Transport);
        hash.Add(Crypto); hash.Add(NonConformant); hash.Add(NonConformanceReason);
        hash.Add(RootFingerprint); hash.Add(Meter); hash.Add(Totp);
        hash.Add(EvseId); hash.Add(TariffId); hash.Add(Currency); hash.Add(UiLanguage);
        hash.Add(WifiSsid); hash.Add(WifiPsk);
        foreach (var p in Protocols) hash.Add(p);
        // Order-independent, since Extra is a dictionary and its enumeration order is not meaningful.
        foreach (var kv in Extra.OrderBy(kv => kv.Key, StringComparer.Ordinal))
        {
            hash.Add(kv.Key);
            hash.Add(kv.Value);
        }
        return hash.ToHashCode();
    }
}

public enum PairingTransport
{
    /// <summary>Plaintext TCP. Always a warning: -2 and -20 both expect TLS in the field.</summary>
    Tcp,
    Tls,
}

/// <summary>One thing wrong with a scanned code, in a form a UI can render and a log can record.</summary>
/// <param name="Kind">What class of problem this is.</param>
/// <param name="Detail">
/// Human-readable specifics. Where it quotes the peer — a non-conformance reason — it is quoted
/// verbatim and must be rendered as untrusted text, never as markup.
/// </param>
public sealed record PairingWarning(PairingWarningKind Kind, string Detail)
{
    /// <summary>Whether this alone should stop the code being offered as connectable at all.</summary>
    public bool IsBlocking => Kind is PairingWarningKind.UnsupportedVersion
                                   or PairingWarningKind.PublicTarget;
}

public enum PairingWarningKind
{
    /// <summary>A version this build cannot read. Blocking: unknown fields may change meaning.</summary>
    UnsupportedVersion,

    /// <summary>Plaintext transport where TLS is expected.</summary>
    PlaintextTransport,

    /// <summary>A crypto profile weaker than the -20 conformant one.</summary>
    WeakenedCrypto,

    /// <summary>The peer declares itself non-conformant. <c>Detail</c> is its own words.</summary>
    DeclaredNonConformance,

    /// <summary>
    /// The target is not a private or link-local address. The intended counterpart is a Pi on your
    /// own network; a code pointing at a public host is suspicious by construction, and real
    /// chargers are out of scope anyway (§8 #5). Blocking without an explicit override.
    /// </summary>
    PublicTarget,

    /// <summary>No trust anchor in the code, so the chain cannot be checked without prior setup.</summary>
    NoTrustAnchor,

    /// <summary>No TOTP, so this is a static code: a photograph of it works forever.</summary>
    NoProximityProof,

    /// <summary>The code carries a WLAN password, which joining will store on the device.</summary>
    CarriesWifiCredentials,

    /// <summary>Parameters this build does not understand. Not an error, but worth saying out loud.</summary>
    UnknownParameters,
}
