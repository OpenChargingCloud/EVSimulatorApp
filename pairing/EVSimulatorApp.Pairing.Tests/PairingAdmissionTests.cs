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

using EVSimulatorApp.Pairing;
using EVSimulatorApp.Pi;
using NUnit.Framework;

namespace EVSimulatorApp.Pairing.Tests;

/// <summary>
/// The Tier-1 gate: who may open a V2G connection.
/// </summary>
[TestFixture]
public class PairingAdmissionTests
{
    private const string Secret = "a-shared-secret-of-sufficient-length";
    private static readonly TimeSpan Slot = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan Window = TimeSpan.FromMinutes(2);

    private sealed class FakeClock(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset now = now;
        public override DateTimeOffset GetUtcNow() => now;
        public void Advance(TimeSpan by) => now += by;
    }

    private static (PairingAdmission Gate, PairingTotpVerifier Totp, FakeClock Clock) Gate()
    {
        var clock = new FakeClock(new DateTimeOffset(2026, 7, 31, 12, 0, 0, TimeSpan.Zero));
        var totp = new PairingTotpVerifier(Secret, Slot, clock);
        return (new PairingAdmission(totp, clock, Window), totp, clock);
    }

    private static IPEndPoint Peer(string address, int port = 40000) => new(IPAddress.Parse(address), port);

    [Test]
    public void NobodyIsAdmittedUntilACodeIsPresented()
    {
        var (gate, _, _) = Gate();

        Assert.That(gate.IsAdmitted(Peer("192.168.4.2")), Is.False);
    }

    [Test]
    public void PresentingAValidCodeAdmitsThatAddress()
    {
        var (gate, totp, _) = Gate();

        Assert.Multiple(() =>
        {
            Assert.That(gate.Present(totp.Current().Totp, IPAddress.Parse("192.168.4.2")),
                        Is.EqualTo(PairingTotpResult.Accepted));
            Assert.That(gate.IsAdmitted(Peer("192.168.4.2")), Is.True);
        });
    }

    /// <summary>
    /// Presenting from one address does not admit another. Otherwise anyone on the network could
    /// ride in behind a vehicle that legitimately scanned.
    /// </summary>
    [Test]
    public void AdmissionIsScopedToTheAddressThatPresented()
    {
        var (gate, totp, _) = Gate();
        gate.Present(totp.Current().Totp, IPAddress.Parse("192.168.4.2"));

        Assert.That(gate.IsAdmitted(Peer("192.168.4.3")), Is.False);
    }

    [Test]
    public void AReplayedCodeAdmitsNobody()
    {
        var (gate, totp, _) = Gate();
        var code = totp.Current().Totp;
        gate.Present(code, IPAddress.Parse("192.168.4.2"));

        Assert.Multiple(() =>
        {
            Assert.That(gate.Present(code, IPAddress.Parse("192.168.4.9")),
                        Is.EqualTo(PairingTotpResult.Replayed));
            Assert.That(gate.IsAdmitted(Peer("192.168.4.9")), Is.False);
        });
    }

    [Test]
    public void AWrongCodeAdmitsNobody()
    {
        var (gate, _, _) = Gate();

        Assert.Multiple(() =>
        {
            Assert.That(gate.Present("zzzzzzzzzzzz", IPAddress.Parse("192.168.4.2")),
                        Is.EqualTo(PairingTotpResult.Unknown));
            Assert.That(gate.IsAdmitted(Peer("192.168.4.2")), Is.False);
        });
    }

    /// <summary>
    /// An admission is short-lived: it says "this address proved presence a moment ago", not "this
    /// address is trusted". Otherwise one scan would open the port for as long as the Pi runs.
    /// </summary>
    [Test]
    public void AdmissionExpires()
    {
        var (gate, totp, clock) = Gate();
        gate.Present(totp.Current().Totp, IPAddress.Parse("192.168.4.2"));

        clock.Advance(Window + TimeSpan.FromSeconds(1));

        Assert.Multiple(() =>
        {
            Assert.That(gate.IsAdmitted(Peer("192.168.4.2")), Is.False);
            Assert.That(gate.Count, Is.Zero, "expired entries are forgotten, not merely ignored");
        });
    }

    /// <summary>
    /// IPv4 is the case that actually broke in a live run: <c>IPAddress.ScopeId</c> throws rather
    /// than returning zero, so an unguarded read took down the accept loop — leaving unadmitted
    /// connections open — and threw out of <c>Present</c> after the code was already spent.
    /// </summary>
    [TestCase("127.0.0.1")]
    [TestCase("192.168.4.2")]
    [TestCase("::1")]
    [TestCase("fe80::2")]
    public void EveryAddressFamilyIsHandled(string address)
    {
        var (gate, totp, _) = Gate();

        Assert.DoesNotThrow(() =>
        {
            gate.Present(totp.Current().Totp, IPAddress.Parse(address));
            gate.IsAdmitted(Peer(address));
        });
        Assert.That(gate.IsAdmitted(Peer(address)), Is.True, address);
    }

    /// <summary>
    /// A link-local address seen through different interfaces is the same peer. The V2G endpoint is
    /// usually link-local, so this is the normal case rather than an edge one.
    /// </summary>
    [Test]
    public void AnIpv6ScopeIdDoesNotSplitOnePeerInTwo()
    {
        var (gate, totp, _) = Gate();
        gate.Present(totp.Current().Totp, IPAddress.Parse("fe80::2%1"));

        Assert.That(gate.IsAdmitted(new IPEndPoint(IPAddress.Parse("fe80::2%7"), 40000)), Is.True);
    }

    /// <summary>An IPv4-mapped address is the same peer as the plain one.</summary>
    [Test]
    public void AnIpv4MappedAddressIsTheSamePeer()
    {
        var (gate, totp, _) = Gate();
        gate.Present(totp.Current().Totp, IPAddress.Parse("192.168.4.2"));

        Assert.That(gate.IsAdmitted(new IPEndPoint(IPAddress.Parse("::ffff:192.168.4.2"), 40000)), Is.True);
    }
}
