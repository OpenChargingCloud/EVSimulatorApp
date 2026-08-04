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
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace EVSimulatorApp.Pi;

/// <summary>
/// Which address the display tells the phone to dial.
///
/// This is the one field on the screen that a person acts on, so a wrong value is worse than a
/// missing one: the QR scans, the app tries, and nothing happens. It used to be the constant
/// <see cref="ApModeFallback"/>, which is right on the Pi and wrong on every laptop the host is
/// developed on.
///
/// The rule is "the first wireless interface's routable IPv4", and it is not a laptop concession:
/// on the Pi in AP mode that interface *is* 192.168.4.1, so one rule covers both machines instead
/// of a constant that happens to be true in one place.
/// </summary>
public static class StationAddress
{

    /// <summary>
    /// The address the Pi's WLAN carries once hostapd is up. Only used when no interface offers a
    /// routable IPv4 at all — on the Pi that is the state *before* AP mode has come up, and this is
    /// then the address it is about to have.
    /// </summary>
    public const string ApModeFallback = "192.168.4.1";

    /// <summary>
    /// The facts a choice depends on. A record rather than <see cref="NetworkInterface"/>, which
    /// cannot be constructed in a test — the rule below is the part worth pinning, and it would
    /// otherwise only ever be exercised against whatever NICs the build machine happens to have.
    /// </summary>
    public sealed record Nic(String                 Name,
                             NetworkInterfaceType   Type,
                             OperationalStatus      Status,
                             IReadOnlyList<IPAddress> Addresses);

    /// <summary>
    /// The first wireless interface that is up and carries a routable IPv4, or — if none does —
    /// the first other interface that does. Null when nothing qualifies.
    /// </summary>
    /// <remarks>
    /// Enumeration order is the operating system's, so "first" means what the OS lists first, the
    /// same order <c>ipconfig</c> / <c>ip addr</c> show.
    /// </remarks>
    public static (Nic Nic, IPAddress Address)? Choose(IEnumerable<Nic> nics)
    {

        var usable = nics.Where (nic => nic.Status == OperationalStatus.Up &&
                                        nic.Type   != NetworkInterfaceType.Loopback).
                          Select(nic => (Nic: nic, Address: nic.Addresses.FirstOrDefault(IsRoutableIPv4))).
                          Where (candidate => candidate.Address is not null).
                          ToList();

        var chosen = usable.FirstOrDefault(candidate => candidate.Nic.Type == NetworkInterfaceType.Wireless80211);

        if (chosen.Address is null && usable.Count > 0)
            chosen = usable[0];

        return chosen.Address is null
                   ? null
                   : (chosen.Nic, chosen.Address);

    }

    /// <summary>
    /// What this machine's interfaces resolve to, plus a phrase naming the reason — the operator
    /// should be able to see from the log why the screen says what it says.
    /// </summary>
    public static (String Host, String Because) Detect()
    {

        var chosen = Choose(NetworkInterface.GetAllNetworkInterfaces().Select(nic =>
                         new Nic(nic.Name,
                                 nic.NetworkInterfaceType,
                                 nic.OperationalStatus,
                                 nic.GetIPProperties().UnicastAddresses.
                                     Select(unicast => unicast.Address).ToList())));

        return chosen is null

                   ? (ApModeFallback,
                      "no interface has a routable IPv4 — this is the AP-mode address, set station:host to override")

                   : (chosen.Value.Address.ToString(),
                      $"{chosen.Value.Nic.Name} ({chosen.Value.Nic.Type})");

    }

    /// <summary>
    /// IPv4 that another device on the network could actually reach: not loopback, and not an
    /// APIPA/link-local 169.254.0.0/16 address, which is what an interface carries when DHCP has
    /// failed — precisely the case where a displayed address would be a dead end.
    /// </summary>
    private static Boolean IsRoutableIPv4(IPAddress address)

        => address.AddressFamily == AddressFamily.InterNetwork &&
           !IPAddress.IsLoopback(address) &&
           address.GetAddressBytes() is not [169, 254, ..];

}
