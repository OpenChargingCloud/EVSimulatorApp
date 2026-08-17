# What the platform TLS stacks actually offer

Date: **2026-08-17**. Status: **MEASURED**, three stacks of four. This is the measurement
[`mobile-workplan.md`](../mobile-workplan.md) §4 asks for before any TLS code is written, and it
exists to replace a derivation with a fact:

> Everything below is written *assuming* the platform stores no longer carry
> `TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256`; if the measurement says otherwise, this stage shrinks.

The assumption holds. The stage does not shrink — and the measurement found something the plan did
not anticipate, which makes iOS worse and Android's answer more interesting than expected.

## What was measured, and how

Two instruments, because two different questions.

**What a client offers** — [`tools/tls-clienthello-observer.py`](../../tools/tls-clienthello-observer.py).
Deliberately not a TLS server: a server answers, and its own preferences and its own library's
support filter what you get to see, which is the thing being measured. It listens on a plain socket,
parses the first ClientHello and hangs up. Every client below therefore fails its handshake; that is
expected and is not the result. The result is the cipher-suite list on the wire.

**Whether our own station accepts it** — a throwaway `TcpV2GListener` pinned to
`TlsProfiles.Iso2CipherSuites` (`{TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256}`) over TLS 1.2 — the real profile, not a stand-in.

## The suites at issue

| | IANA | Where |
|---|---|---|
| `TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256` | `0xC025` | ISO 15118-2, **mandatory**. Static ECDH. |
| `TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256` | `0xC023` | ISO 15118-2, optional. Ephemeral. |
| `TLS_AES_256_GCM_SHA384` | `0x1302` | ISO 15118-20 |
| `TLS_CHACHA20_POLY1305_SHA256` | `0x1303` | ISO 15118-20 |

## Results

| Stack | Version | `0xC025` | `0xC023` | -20 pair | secp521r1 | `trusted_ca_keys` |
|---|---|---|---|---|---|---|
| OpenSSL 3.6.3 CLI *(control)* | macOS 26.5.2 | no | **offered** | yes | yes | no |
| SunJSSE | JDK 21.0.12, macOS | **not implemented** | **offered by default** | yes | yes | no |
| Network.framework | macOS 26.5.2 | **not in the API** | **not offered, cannot be added** | yes | yes | no |
| Network.framework | iOS 26.5 *(Simulator)* | **not in the API** | **not offered, cannot be added** | yes | yes | no |
| Conscrypt | Android | **unmeasured** — see below | | | | |

Against our own `-2` station, pinned to the two prescribed suites:

| Client | Outcome |
|---|---|
| Network.framework, asking for `0xC023` | **refused** — `SslException: Cipher Suite negotiation failure` |
| SunJSSE | **completed** — `TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256`, TLS 1.2 |

## The three findings

**1. The mandatory `-2` suite is gone everywhere it was looked for.** Static ECDH is not implemented
by SunJSSE and is not even *nameable* on Apple: `tls_ciphersuite_t` has 23 members and **not one
of them is a static `ECDH_` suite** — every ECDH entry is ECDH**E**. So ISO 15118-2's mandatory suite
cannot be offered by any measured platform stack, and a conformant `-2` handshake from a phone is
impossible through them. That was the plan's assumption and it is now a measurement.

**2. Both stacks accept a pin they do not honour, and neither says so.** This is the finding the plan
did not anticipate, and it is worse than absence.

*JSSE* reports `0xC025` as unsupported, then accepts it in `setEnabledCipherSuites` **without
throwing**, reports it back from `getEnabledCipherSuites`, and never puts it on the wire:

```
factory says supported: false
socket says supported:  false
after set, enabled =    [TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256]
```

*Network.framework* is worse still, because its silent failure is **permissive**. Appending
`0xC023` and pinning TLS 1.2 produced a ClientHello with **17 suites and not one of them the
requested suite** — the full default TLS-1.2 set. The control settles what that means: appending
`ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`, which the stack does implement, produced a ClientHello with
**exactly one suite**. So the mechanism works, and an unimplemented-but-nameable suite is discarded
without a diagnostic, leaving the client offering seventeen suites where it asked for one.

A transport that pinned the ISO suites and checked for an error would report success on both
platforms while negotiating something else entirely. That is the failure mode this project spends
most of its effort refusing, and it is sitting in the two APIs the ports would have used.

**3. Neither platform sends `trusted_ca_keys`, and neither offers a way to add it.** `[V2G2-651]`
obliges every `-2` EV to name the V2G roots it holds in the ClientHello. No measured stack sends the
extension, and neither API has a hook to add an arbitrary one — the same wall
[`ours-iso2-trusted-ca-keys.md`](../../../ISO15118ConformanceTests/docs/matrix/ours-iso2-trusted-ca-keys.md)
hit with `SslStream`, which is what pushed the C# side onto BouncyCastle. The extension is not a
detail on top of the suite problem; it is a second, independent reason the platform stacks cannot
carry a conformant `-2` session.

## What this changes in the plan

The plan expected the asymmetry to be *static ECDH is gone, ephemeral is fine*. It is sharper than
that:

- **iOS is definitively out on `-2`, on both suites.** Not "the mandatory one is missing" but "no `-2`
  suite is reachable through Network.framework at all". The plan's "iOS is an open decision, not a
  task" stands, and the decision is now forced rather than weighed: `-2` over Network.framework is not
  a configuration problem.
- **The JVM is fine on the optional suite** — a full TLS 1.2 handshake with our own station, on
  `0xC023`. Whether *Android* is fine is the open question, and it is now the highest-value hour left
  in this stage: if Conscrypt matches SunJSSE, Android can speak `-2` to any station that accepts the
  optional suite, and only the mandatory suite and `trusted_ca_keys` need BouncyCastle.
- **`-20` looks reachable on both platforms.** Both offer `TLS_AES_256_GCM_SHA384` and
  `TLS_CHACHA20_POLY1305_SHA256`, both offer secp521r1, and Network.framework's append **does**
  restrict the offer to exactly the `-20` pair when asked. Mutual authentication and a private trust
  root are not measured here and are the next thing to measure, not to assume.

## Honest limits

- **Android is unmeasured.** The AVD (`Pixel_3a_API_32_arm64-v8a`) is configured but its system image
  directory is empty, so the emulator will not boot. The probe for it is built and takes seconds to
  run: `javac --release 11`, `d8`, `adb push`, `dalvikvm -cp probe.dex JsseProbe 10.0.2.2 <port>` —
  which runs on ART with Conscrypt as the default provider. It needs an `sdkmanager` download first.
- **iOS was measured on the Simulator, not a device.** The binary is a real
  `arm64-apple-ios-simulator` Mach-O run under `simctl spawn`, against the same Network.framework the
  device carries, and its results are identical to macOS's. Cipher-suite support is a property of the
  library rather than of the hardware, so this is a strong proxy — but the workplan asked for a
  device, and this is not one.
- **SunJSSE is not Android.** It is the JDK's provider, measured here because it is what `kotlin/`
  builds against on a desktop. Android replaces it with Conscrypt, and nothing here says what
  Conscrypt does.
- **Nothing was measured about mutual TLS**, client-certificate chains rooted outside the platform
  trust store, or `-20`'s full handshake requirement. Those are the next measurements.

## Re-running it

```bash
python3 tools/tls-clienthello-observer.py my-client 44330 &
# …then point the client at 127.0.0.1:44330
```

Worth re-running rather than citing: platform TLS stacks change under you, and a claim about one is
only worth what its last re-run says.
