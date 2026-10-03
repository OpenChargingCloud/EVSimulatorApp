# What the platform TLS stacks actually offer

Date: **2026-08-17**, with a fifth stack added **2026-08-20**. Status: **MEASURED**. This is the measurement
[`mobile-workplan.md`](../mobile-workplan.md) §4 asks for before any TLS code is written, and it
exists to replace a derivation with a fact:

> Everything below is written *assuming* the platform stores no longer carry
> `TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256`; if the measurement says otherwise, this stage shrinks.

The assumption held, and then the measurement went further than the assumption. **Neither phone
platform can open a conformant session on either protocol through its own TLS stack** — `-2` for want
of a cipher suite, `-20` for want of a signature algorithm. The stage does not shrink. It stops being
about `-2`.

## What was measured, and how

Two instruments, because two different questions.

**What a client offers** — [`tools/tls-clienthello-observer.py`](../../tools/tls-clienthello-observer.py).
Deliberately not a TLS server: a server answers, and its own preferences and its own library's
support filter what you get to see, which is the thing being measured. It listens on a plain socket,
parses the first ClientHello and hangs up. Every client below therefore fails its handshake; that is
the method, not the result. The result is what was on the wire.

**Whether our own station accepts it** — a throwaway `TcpV2GListener` configured from `TlsProfiles`,
so the profile under test is the real one rather than a stand-in:

| Profile | TLS | Suites | Station certificate |
|---|---|---|---|
| `-2` | 1.2 | `TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256`, `TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256` | P-256 |
| `-20` | 1.3 | `TLS_AES_256_GCM_SHA384`, `TLS_CHACHA20_POLY1305_SHA256` | P-521 |
| `-20` *(control)* | 1.3 | same as `-20` | **P-256** — not conformant, and that is the point |

## What each client offers

| Stack | `0xC025` `-2` mand. | `0xC023` `-2` opt. | `-20` suites | secp521r1 group | `ecdsa_secp521r1_sha512` | Ed448 | `trusted_ca_keys` |
|---|---|---|---|---|---|---|---|
| OpenSSL 3.6.3 CLI *(control)* | no | **yes** | yes | yes | yes | yes | no |
| **SunJSSE** — JDK 21.0.12, macOS 26.5.2 | no | **yes** | yes | yes | **yes** | **yes** | no |
| **Conscrypt** — Android 12, API 32 | no | **no** | yes | **no** | **no** | no | no |
| **Network.framework** — macOS 26.5.2 | no | **no** | yes | yes | **no** | no | no |
| **Network.framework** — iOS 26.5 *(Simulator)* | no | **no** | yes | yes | **no** | no | no |
| **BoringSSL** — swift-nio-ssl 2.37.2, macOS *(2026-08-20)* | no | **no** | yes | no | **yes**¹ | no | no |

¹ Not by default — `TLSConfiguration.verifySignatureAlgorithms` puts it on the wire, and then the
`-20` handshake completes. The only measured stack that can. See the addendum.

## What our own station does with them

| Client | `-2` profile | `-20` profile | `-20` control (P-256 cert) |
|---|---|---|---|
| SunJSSE | **completed** — `TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256`, TLS 1.2 | **completed** | — |
| Conscrypt / Android 12 | refused — `Cipher Suite negotiation failure` | refused — `illegal_parameter(47)` | **completed** |
| Network.framework / macOS | refused — `Cipher Suite negotiation failure` | refused — `illegal_parameter(47)` | **completed** |
| BoringSSL / swift-nio-ssl | cannot start — neither suite exists | **completed**, with `ecdsa_secp521r1_sha512` asked for | — |

## The four findings

*A fifth stack was measured on 2026-08-20; see [the addendum](#addendum-2026-08-20--the-fifth-stack), which corrects nothing below but adds a fourth answer to finding 3 and a stack that finding 2 does not cover.*

**1. `-2` is out on both phone platforms, and not only on the mandatory suite.**

Static ECDH is gone everywhere, as expected: SunJSSE does not implement `0xC025`, and Apple's
`tls_ciphersuite_t` has 23 members with **not one static `ECDH_` among them** — every ECDH entry is
ECDH**E**, so the API cannot even name it.

The part the plan did not expect is that the *optional* ephemeral suite `0xC023` is gone from the
phone platforms too. Conscrypt does not implement it; Network.framework will not put it on the wire.
Only the JDK still has it — and the JDK is not what either port ships on. Both phone stacks are
refused by our own `-2` station with `Cipher Suite negotiation failure`.

**2. `-20` is out as well, and the reason is the certificate rather than the suites.**

Both platforms offer `TLS_AES_256_GCM_SHA384` and `TLS_CHACHA20_POLY1305_SHA256`, so a cipher-suite
table alone says `-20` is reachable. It is not: neither platform advertises
**`ecdsa_secp521r1_sha512`** in `signature_algorithms`, and ISO 15118-20's PKI is secp521r1/SHA-512
throughout — so a station presenting its P-521 certificate is presenting one the client has already
said it cannot verify.

The control isolates it to a single variable. With the `-20` suites and TLS 1.3 but a **P-256**
station certificate, both Network.framework and Conscrypt **complete the handshake**. Change nothing
but the certificate back to P-521 and both answer `illegal_parameter(47)`. The suites were never the
problem.

Ed448, `-20`'s second signature suite, is absent from both as well. The JDK offers both — which is
what makes this a difference between stacks rather than a mistake in the instrument.

**3. Three of the four stacks accept a cipher-suite pin they do not honour. Two of them say nothing.**

*JSSE* reports `0xC025` as unsupported, accepts it in `setEnabledCipherSuites` **without throwing**,
reports it back from `getEnabledCipherSuites`, and never puts it on the wire:

```
factory says supported: false
socket says supported:  false
after set, enabled =    [TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256]
```

*Network.framework* fails silently **and permissively**, which is worse. Appending `0xC023` and
pinning TLS 1.2 produced a ClientHello with **seventeen suites and not one of them the requested
suite** — the full default set. The control settles what that means: appending
`ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`, which the stack does implement, produced a ClientHello with
**exactly one suite**. So the mechanism works, and an unimplemented-but-nameable suite is discarded
without a diagnostic, leaving the client offering seventeen suites where it asked for one.

*Conscrypt* is the only one that refuses out loud, and it is worth quoting because it is the
behaviour the other two should have:

```
REFUSED by setEnabledCipherSuites: TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256
  (cipherSuite TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256 is not supported.)
```

Whatever gets built here must **verify the negotiated suite after the handshake** rather than trust
the configuration before it. On two of three stacks the configuration is not an instruction.

**4. No platform sends `trusted_ca_keys`, and none offers a way to add it.**

`[V2G2-651]` obliges every `-2` EV to name the V2G roots it holds in the ClientHello. No measured
stack sends the extension and no measured API has a hook for an arbitrary one — the same wall
[`ours-iso2-trusted-ca-keys.md`](../../../ISO15118ConformanceTests/docs/matrix/ours-iso2-trusted-ca-keys.md)
hit with `SslStream`, which is what pushed the C# side onto BouncyCastle. Independent of the suite
problem, and it would still be there if the suites came back.

## What this changes in the plan

The plan expected to be choosing between platform stacks and a bundled one, per platform, for `-2`.
That choice is gone: **for both protocols and on both platforms, the answer is a bundled TLS stack.**

- **Android is no longer "the easy half if it fails".** It is now the same problem as iOS — and it is
  also the one with an obvious answer: BouncyCastle's `bctls` is the library the C# side already
  runs, so the Kotlin port would gain a second transport beside `TcpV2GTransport` with the suites
  pinned, `trusted_ca_keys` available, and secp521r1 and Ed448 reachable. The plan already said this;
  what changed is that it is no longer conditional on a measurement.
- ~~**iOS remains the open decision, and the ground under it moved.**~~ **Decided 2026-08-20** —
  [`decisions/ios-tls-stack.md`](../decisions/ios-tls-stack.md): a TLS client of our own, in Swift,
  for both profiles. The decision had to be argued against a *working* `-20` option rather than in
  the absence of one, because measuring the fifth stack first is what the addendum below found.
- **The JDK is not a proxy for Android, and this is the measurement that shows it.** SunJSSE passes
  `-2` against our station and offers both of `-20`'s signature suites; Conscrypt does neither. A
  desktop `kotlin/` test that opened a TLS session would have proved nothing about the phone.

## Addendum 2026-08-20 · the fifth stack

Measured because the iOS decision rejected it, and a rejection resting on an assumption is the
failure this report exists to undo. It did not survive contact:
[`decisions/ios-tls-stack.md`](../decisions/ios-tls-stack.md) has the full write-up.

**BoringSSL can verify a P-521 station certificate.** Not by default, but
`verifySignatureAlgorithms = [.ecdsaSecp521R1Sha512]` puts the signature algorithm on the wire, and
the same station that answers `illegal_parameter(47)` to both phone stacks **completes** the `-20`
handshake. One variable, isolated the same way the P-256 control isolated it before:

```
verifySignatureAlgorithms unset  → RESULT: handshake refused — illegal_parameter(47)
verifySignatureAlgorithms set    → RESULT: handshake completed
```

So finding 2's "neither platform advertises `ecdsa_secp521r1_sha512`" is right about the two phone
platforms and must not be read as a statement about every stack that could ship on them.

**`-2` is out here too**, on both suites, which makes it four of five stacks — BouncyCastle on the
JVM is the only measured implementation that speaks `TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256`.

**And finding 3 gains a fourth answer, worse than the other three.** Pinning a suite BoringSSL does
not implement is a null-pointer dereference:

```
EXC_BAD_ACCESS (SIGSEGV) — KERN_INVALID_ADDRESS at 0x8
  CNIOBoringSSL_SSL_CIPHER_standard_name
  NIOTLSCipher.standardName.getter
  closure #1 in TLSConfiguration.cipherSuiteValues.setter
```

`NIOTLSCipher` is `RawRepresentable` over `UInt16`, so any code point can be named — the opposite of
Apple's closed enum, and here that is the defect rather than the feature. Pinning the two `-20`
suites instead trips a Swift precondition in `NIOSSLContext.init`: BoringSSL's cipher list governs
TLS 1.2 only and its three TLS 1.3 suites are fixed, so **the `-20` pair cannot be pinned on this
stack at all**.

## Honest limits

- **Both phones were emulated.** Android 12 / API 32 on `Pixel_3a_API_32_arm64-v8a`; iOS 26.5 in the
  Simulator, as a real `arm64-apple-ios-simulator` Mach-O run under `simctl spawn`. Cipher suites and
  signature algorithms are properties of the library rather than of the hardware, and the iOS results
  match macOS's suite for suite — but the workplan asked for a device and these are not devices. A
  newer Android release in particular could differ from API 32.
- **Nothing here measures mutual authentication**, client-certificate chains rooted outside the
  platform trust store, or the client-side half of `-20`'s full-handshake requirement. Those are the
  next measurements, and none of them can improve the two findings above.
- **The `-20` control is deliberately non-conformant.** A P-256 station certificate is not an ISO
  15118-20 station certificate; it exists only to isolate one variable, and it did.
- **swift-nio-ssl was measured on macOS**, not on iOS, and its `-20` completion was measured with
  certificate verification disabled — so it establishes that the `CertificateVerify` signature over a
  P-521 key was accepted, not that a chain validation would pass.
- **`illegal_parameter(47)` is the client's word for it.** The control makes the certificate the
  cause beyond reasonable doubt, but neither stack says which parameter it disliked.

## Re-running it

```bash
python3 tools/tls-clienthello-observer.py my-client 44330
```

…then point the client at `127.0.0.1:44330`. For the fifth stack that client is checked in too:

```bash
swift run --package-path tools/tls-nio-probe TlsNioProbe 127.0.0.1 44330 iso20-sigalgs-only
```

Worth re-running rather than citing: platform TLS stacks change under you, and a claim about one is
only worth what its last re-run says.
