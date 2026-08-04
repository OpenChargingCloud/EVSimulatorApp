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

namespace EVSimulatorApp.Pairing;

/// <summary>
/// Turns a parsed payload into the list of things a confirmation sheet must show in red.
/// </summary>
/// <remarks>
/// Separate from parsing on purpose. Parsing asks "what does this code say?"; this asks "what is
/// wrong with what it says?" — and only the second changes as the threat model does. Keeping them
/// apart also means a code can be logged exactly as scanned while the judgement about it evolves.
/// </remarks>
public static class PairingWarnings
{
    /// <summary>The curve the -20 conformant profile requires (<c>docs/pki-model.md</c>).</summary>
    public const string ConformantCurve = "secp521r1";

    public static IReadOnlyList<PairingWarning> For(PairingPayload payload)
    {
        var warnings = new List<PairingWarning>();

        if (payload.Version != 1)
            warnings.Add(new(PairingWarningKind.UnsupportedVersion,
                             $"payload version {payload.Version}; this build reads version 1"));

        if (payload.Transport is PairingTransport.Tcp)
            warnings.Add(new(PairingWarningKind.PlaintextTransport,
                             "the counterpart offers plaintext TCP — the session will not be encrypted"));

        // "Unstated" is reported as well as "weakened". A code that says nothing about its curve is
        // not thereby conformant, and silence is the easiest thing for a hostile code to offer.
        if (payload.Crypto is null)
            warnings.Add(new(PairingWarningKind.WeakenedCrypto,
                             $"no crypto profile stated; the -20 conformant profile is {ConformantCurve}"));
        else if (!payload.Crypto.Equals(ConformantCurve, StringComparison.OrdinalIgnoreCase))
            warnings.Add(new(PairingWarningKind.WeakenedCrypto,
                             $"crypto profile is {payload.Crypto}, not the conformant {ConformantCurve}"));

        if (payload.NonConformant)
            warnings.Add(new(PairingWarningKind.DeclaredNonConformance,
                             payload.NonConformanceReason is { Length: > 0 } why
                                 ? why
                                 : "the counterpart declares itself non-conformant but gives no reason"));

        if (!IsPrivateTarget(payload.Host))
            warnings.Add(new(PairingWarningKind.PublicTarget,
                             $"{payload.Host} is not a private or link-local address"));

        if (payload.RootFingerprint is null)
            warnings.Add(new(PairingWarningKind.NoTrustAnchor,
                             "no root fingerprint; the certificate chain cannot be checked against this code"));

        if (payload.Totp is null)
            warnings.Add(new(PairingWarningKind.NoProximityProof,
                             "static code — it proves nothing about being present now"));

        if (payload.WifiPsk is not null)
            warnings.Add(new(PairingWarningKind.CarriesWifiCredentials,
                             $"the code carries the password for network {payload.WifiSsid ?? "(unnamed)"}"));

        if (payload.Extra.Count > 0)
            warnings.Add(new(PairingWarningKind.UnknownParameters,
                             "unread parameters: " + string.Join(", ", payload.Extra.Keys.Order())));

        return warnings;
    }

    /// <summary>
    /// Whether a host is somewhere the intended counterpart could plausibly be: a private range, a
    /// link-local address, loopback, or an mDNS name.
    /// </summary>
    /// <remarks>
    /// A hostname that is not <c>.local</c> is treated as public rather than resolved. Resolving it
    /// would mean making a DNS query on behalf of a code we have not yet decided to trust, which
    /// hands an attacker a callback before the user has approved anything.
    /// </remarks>
    public static bool IsPrivateTarget(string host)
    {
        var bare = host.Split('%')[0];   // strip an IPv6 zone: fe80::1%wlan0

        if (!IPAddress.TryParse(bare, out var ip))
            return host.EndsWith(".local", StringComparison.OrdinalIgnoreCase);

        if (IPAddress.IsLoopback(ip))
            return true;

        if (ip.AddressFamily is AddressFamily.InterNetworkV6)
            return ip.IsIPv6LinkLocal || ip.IsIPv6UniqueLocal;

        var b = ip.GetAddressBytes();
        return b[0] switch
        {
            10  => true,                          // 10.0.0.0/8
            172 => b[1] >= 16 && b[1] <= 31,      // 172.16.0.0/12
            192 => b[1] == 168,                   // 192.168.0.0/16
            169 => b[1] == 254,                   // 169.254.0.0/16, link-local
            _   => false,
        };
    }
}
