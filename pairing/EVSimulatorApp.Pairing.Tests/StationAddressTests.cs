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

using System.Net;
using System.Net.NetworkInformation;

using EVSimulatorApp.Pi;

using NUnit.Framework;

namespace EVSimulatorApp.Pairing.Tests;

/// <summary>
/// Which address the display advertises. The rule runs against a list of interface facts rather
/// than the real machine's NICs on purpose: driven off <c>NetworkInterface</c> it could only ever
/// be exercised against whatever the build agent happens to have, and the cases that matter — no
/// Wi-Fi, DHCP failed, IPv6 only — are exactly the ones no machine offers on demand.
/// </summary>
[TestFixture]
public class StationAddressTests
{

    private static StationAddress.Nic Nic(string name,
                                          NetworkInterfaceType type,
                                          OperationalStatus status,
                                          params string[] addresses)

        => new (name, type, status, addresses.Select(IPAddress.Parse).ToList());

    private static StationAddress.Nic Wifi(string name, params string[] addresses)
        => Nic(name, NetworkInterfaceType.Wireless80211, OperationalStatus.Up, addresses);

    private static StationAddress.Nic Ethernet(string name, params string[] addresses)
        => Nic(name, NetworkInterfaceType.Ethernet, OperationalStatus.Up, addresses);


    [Test]
    public void PrefersWirelessEvenWhenAnEthernetComesFirst()
    {
        var chosen = StationAddress.Choose([Ethernet("Ethernet", "10.0.0.5"),
                                            Wifi("Wi-Fi", "192.168.178.20")]);

        Assert.That(chosen, Is.Not.Null);
        Assert.That(chosen!.Value.Address.ToString(), Is.EqualTo("192.168.178.20"));
    }

    [Test]
    public void TakesTheFirstWirelessWhenThereAreSeveral()
    {
        var chosen = StationAddress.Choose([Wifi("Wi-Fi", "192.168.178.20"),
                                            Wifi("Wi-Fi 2", "192.168.5.9")]);

        Assert.That(chosen!.Value.Nic.Name, Is.EqualTo("Wi-Fi"));
    }

    /// <summary>The Pi in AP mode: the rule and the old constant agree, which is the point.</summary>
    [Test]
    public void OnTheAccessPointItselfTheRuleYieldsTheApAddress()
    {
        var chosen = StationAddress.Choose([Wifi("wlan0", "192.168.4.1")]);

        Assert.That(chosen!.Value.Address.ToString(), Is.EqualTo(StationAddress.ApModeFallback));
    }

    [Test]
    public void FallsBackToANonWirelessInterface()
    {
        // A Pi on a cable, or a laptop with Wi-Fi off: an address that works beats no address.
        var chosen = StationAddress.Choose([Ethernet("Ethernet", "10.0.0.5")]);

        Assert.That(chosen!.Value.Address.ToString(), Is.EqualTo("10.0.0.5"));
    }

    [Test]
    public void IgnoresInterfacesThatAreDown()
    {
        var chosen = StationAddress.Choose([Nic("Wi-Fi", NetworkInterfaceType.Wireless80211,
                                                OperationalStatus.Down, "192.168.178.20"),
                                            Ethernet("Ethernet", "10.0.0.5")]);

        Assert.That(chosen!.Value.Address.ToString(), Is.EqualTo("10.0.0.5"),
                    "a down Wi-Fi must not outrank a working cable");
    }

    [Test]
    public void IgnoresLoopback()
    {
        var chosen = StationAddress.Choose([Nic("Loopback", NetworkInterfaceType.Loopback,
                                                OperationalStatus.Up, "127.0.0.1")]);

        Assert.That(chosen, Is.Null);
    }

    /// <summary>
    /// 169.254.x is what an interface carries when DHCP failed. It is up, it has an IPv4, and it is
    /// unreachable — the exact shape of a dead end on screen.
    /// </summary>
    [Test]
    public void IgnoresAnApipaAddressFromFailedDhcp()
    {
        var chosen = StationAddress.Choose([Wifi("Wi-Fi", "169.254.13.7"),
                                            Ethernet("Ethernet", "10.0.0.5")]);

        Assert.That(chosen!.Value.Address.ToString(), Is.EqualTo("10.0.0.5"));
    }

    [Test]
    public void IgnoresIPv6OnlyInterfaces()
    {
        // The pairing URI carries a host the phone dials; the -20 stack's own link-local IPv6 is a
        // different concern and belongs nowhere near this screen.
        var chosen = StationAddress.Choose([Wifi("Wi-Fi", "fe80::1", "2001:db8::1")]);

        Assert.That(chosen, Is.Null);
    }

    [Test]
    public void PicksTheRoutableAddressOfAnInterfaceThatHasSeveral()
    {
        var chosen = StationAddress.Choose([Wifi("Wi-Fi", "fe80::1", "169.254.1.2", "192.168.178.20")]);

        Assert.That(chosen!.Value.Address.ToString(), Is.EqualTo("192.168.178.20"));
    }

    [Test]
    public void NothingUsableIsNull()
    {
        // Program.cs then falls back to the AP-mode address and says so in the log.
        Assert.That(StationAddress.Choose([]), Is.Null);
    }

}
