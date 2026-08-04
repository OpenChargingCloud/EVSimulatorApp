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

using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;

using EVSimulatorApp.Pairing;

using Vanaheimr.V2G.Simulation.Transport;

namespace EVSimulatorApp.Pi;

/// <summary>
/// What the station runs — and the single place both the listener and the display read it from.
/// </summary>
/// <remarks>
/// <para>
/// This type exists to make one class of bug impossible rather than merely unlikely. A display
/// advertising TLS beside a station listening in plaintext is worse than no display, because it is
/// believed — and while the transport and the pairing code were configured by separate keys, that
/// disagreement was a typo away. Here <see cref="ToTlsOptions"/> and <see cref="Declare"/> are two
/// projections of one value, so the code on screen cannot describe a station that is not running.
/// </para>
/// <para>
/// The protocol/version mapping is <c>docs/pki-model.md</c>'s and is not configurable: <b>-2 is
/// TLS 1.2 and -20 is TLS 1.3</b>. "ISO 15118-2 over TLS 1.3" is technically possible, unsupported
/// by ESDP and a documented interop hazard, so the two are pinned together rather than offered as
/// independent knobs.
/// </para>
/// </remarks>
public sealed record StationProfile
{
    /// <summary>2 or 20.</summary>
    public required int Protocol { get; init; }

    public required bool UseTls { get; init; }

    /// <summary>The SECC leaf. Required for TLS; ignored otherwise.</summary>
    public X509Certificate2? ServerCertificate { get; init; }

    public X509Certificate2Collection? ServerCertificateChain { get; init; }

    /// <summary>
    /// -20 mutual TLS wants a Vehicle certificate from the EV. Off by default because a simulator
    /// whose first session fails on a missing client cert teaches nothing.
    /// </summary>
    public bool RequireClientCertificate { get; init; }

    public static StationProfile Parse(int protocol, string transport, X509Certificate2? certificate)
    {
        if (protocol is not (2 or 20))
            throw new ArgumentOutOfRangeException(nameof(protocol), protocol, "protocol must be 2 or 20");

        var tls = transport switch
        {
            "tls" => true,
            "tcp" => false,
            _     => throw new ArgumentException($"transport must be tls or tcp, was '{transport}'",
                                                 nameof(transport)),
        };

        if (tls && certificate is null)
            throw new ArgumentException("TLS was asked for but no server certificate was configured",
                                        nameof(certificate));

        return new StationProfile { Protocol = protocol, UseTls = tls, ServerCertificate = certificate };
    }

    /// <summary>The transport half, or null for plaintext.</summary>
    public TlsOptions? ToTlsOptions() =>
        UseTls
            ? new TlsOptions
              {
                  // Pinned to the protocol, never configured separately — see the class remarks.
                  EnabledSslProtocols       = Protocol is 20 ? SslProtocols.Tls13 : SslProtocols.Tls12,
                  CipherSuites              = Protocol is 20 ? TlsProfiles.Iso20CipherSuites
                                                             : TlsProfiles.Iso2CipherSuites,
                  ServerCertificate         = ServerCertificate,
                  ServerCertificateChain    = ServerCertificateChain,
                  RequireClientCertificate  = Protocol is 20 && RequireClientCertificate,
              }
            : null;

    /// <summary>
    /// The pairing code this station should display.
    /// </summary>
    /// <remarks>
    /// The profile <b>owns</b> transport, protocol and crypto — the caller supplies only the
    /// descriptive fields. Taking a template and overriding them would leave the wrong values
    /// expressible at the call site, and the compiler was right to refuse it: <c>Transport</c> is
    /// <c>required</c> precisely so nobody can forget where it comes from.
    /// </remarks>
    public PairingPayload Declare(string host, int port, string? evseId = null,
                                  string? rootFingerprint = null, string? meter = null,
                                  bool nonConformant = false, string? nonConformanceReason = null) =>
        new()
        {
            Version   = 1,
            Host      = host,
            Port      = port,
            Transport = UseTls ? PairingTransport.Tls : PairingTransport.Tcp,
            Protocols = [Protocol is 20 ? "iso20" : "iso2"],
            // The curve the -20 profile requires. -2's own suites are P-256, and stating that
            // plainly beats leaving the field empty — which the app reads as "unstated" and warns
            // about exactly as it warns about a weakened one.
            Crypto    = Protocol is 20 ? PairingWarnings.ConformantCurve : "secp256r1",
            EvseId               = evseId,
            RootFingerprint      = rootFingerprint,
            Meter                = meter,
            NonConformant        = nonConformant,
            NonConformanceReason = nonConformanceReason,
        };
}
