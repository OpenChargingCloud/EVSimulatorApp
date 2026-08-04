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

using EVSimulatorApp.Pairing;

namespace EVSimulatorApp.Pi;

/// <summary>
/// The Tier-1 gate: an address may open a V2G connection only after presenting a valid, unspent
/// pairing code (<c>docs/CONCEPT.md</c> §4.6).
/// </summary>
/// <remarks>
/// <para>
/// The code is presented over the pairing HTTP endpoint, not inside the V2G session. That is the
/// structural point of Tier 1: <b>SLAC is not part of the V2G message set either</b>, it runs first
/// and gates what follows. Doing the same here means zero schema deviation, identical handling for
/// -2 and -20 — and it is the only option that works for -2 at all, whose <c>evccIDType</c> is six
/// bytes of hexBinary with no room for anything.
/// </para>
/// <para>
/// An admission is <b>address-scoped and short-lived</b>. It is not a session credential: it says
/// "this address proved presence a moment ago", which is what lets the TCP accept decide without the
/// V2G layer knowing anything about pairing.
/// </para>
/// </remarks>
public sealed class PairingAdmission(PairingTotpVerifier totp, TimeProvider? clock = null,
                                     TimeSpan? window = null)
{
    private readonly TimeProvider clock = clock ?? TimeProvider.System;
    private readonly TimeSpan window = window ?? TimeSpan.FromMinutes(2);

    private readonly Lock gate = new();
    private readonly Dictionary<string, DateTimeOffset> admitted = new(StringComparer.Ordinal);

    /// <summary>Checks a presented code and, if it is good, admits the address that presented it.</summary>
    public PairingTotpResult Present(string code, IPAddress from)
    {
        var result = totp.Verify(code);
        if (result is not PairingTotpResult.Accepted)
            return result;

        var now = clock.GetUtcNow();
        lock (gate)
        {
            Expire(now);
            admitted[Key(from)] = now + window;
        }
        return result;
    }

    /// <summary>Whether this peer may open a V2G connection — the listener's gate.</summary>
    public bool IsAdmitted(IPEndPoint peer)
    {
        var now = clock.GetUtcNow();
        lock (gate)
        {
            Expire(now);
            return admitted.ContainsKey(Key(peer.Address));
        }
    }

    /// <summary>How many addresses are currently admitted. Diagnostic, and how a test sees the bound.</summary>
    public int Count
    {
        get { lock (gate) { Expire(clock.GetUtcNow()); return admitted.Count; } }
    }

    private void Expire(DateTimeOffset now)
    {
        foreach (var (key, _) in admitted.Where(kv => kv.Value <= now).ToList())
            admitted.Remove(key);
    }

    /// <summary>
    /// An IPv6 scope id is stripped: <c>fe80::2%en0</c> and <c>fe80::2%5</c> are the same peer seen
    /// through different interfaces, and link-local is what this mostly deals with.
    /// </summary>
    /// <remarks>
    /// <see cref="IPAddress.ScopeId"/> <b>throws</b> on an IPv4 address rather than returning zero,
    /// so the family has to be checked first. Reading it unguarded cost a live run: the gate threw
    /// inside the accept loop, so an unadmitted connection was never closed, and <see cref="Present"/>
    /// threw <em>after</em> the code had already been spent — a request that failed with a 500 still
    /// burned the vehicle's one-shot credential.
    /// </remarks>
    private static string Key(IPAddress address)
    {
        var a = address.IsIPv4MappedToIPv6 ? address.MapToIPv4() : address;
        return a.AddressFamily is AddressFamily.InterNetworkV6 && a.ScopeId is not 0
                   ? new IPAddress(a.GetAddressBytes()).ToString()
                   : a.ToString();
    }
}
