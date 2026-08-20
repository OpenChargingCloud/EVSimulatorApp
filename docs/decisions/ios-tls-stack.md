# Decision: iOS gets a TLS layer of our own, in Swift

Date: **2026-08-20**. Status: **DECIDED**. This is the write-up
[`mobile-workplan.md`](../mobile-workplan.md) §4 demands before any TLS code:

> **iOS is an open decision, not a task, and it now has to carry `-20` as well.** … Either a
> BoringSSL build with the missing algorithms restored, or a Swift TLS layer; both are large. Write
> the decision down before writing code.

## The decision

**A TLS client of our own, in Swift, as a new `V2GTls` target — covering both profiles, `-20`
first.** It is a record layer and a handshake state machine over primitives the package already
depends on. It implements no cryptography: swift-crypto for AEAD/HKDF/HMAC/ECDH/ECDSA, CommonCrypto
for AES-CBC, swift-certificates for X.509, CGoldilocks for Ed448. It has no server side, because the
phone is never the station.

Before writing it down, the runner-up was measured — and the measurement moved the argument, so the
next section comes before the reasoning rather than after it.

## The correction this measurement forced

§4 above rests on a sentence that is **wrong**, and it is ours:

> swift-nio-ssl sits on BoringSSL, which dropped static ECDH too — and neither offers
> `ecdsa_secp521r1_sha512`.

BoringSSL offers it. Not by default — which is presumably how the claim survived — but
`TLSConfiguration.verifySignatureAlgorithms` puts it on the wire, and then our own `-20` station
**completes the handshake against its P-521 certificate**. That is the exact wall
[`experiments/tls-platform-suites.md`](../experiments/tls-platform-suites.md) found Network.framework
and Conscrypt standing at, and swift-nio-ssl walks through it.

So the decision below is made *against* a working `-20` option, not in the absence of one. That
changes what has to be justified.

## The fifth stack, measured

Same instruments as the four before it:
[`tools/tls-clienthello-observer.py`](../../tools/tls-clienthello-observer.py) for what goes on the
wire, and a throwaway `TcpV2GListener` configured from `TlsProfiles` for what our own station makes
of it. swift-nio-ssl **2.37.2** (BoringSSL as vendored there), Swift 6.3.3, macOS 26.

| | Network.framework | Conscrypt | **swift-nio-ssl / BoringSSL** |
|---|---|---|---|
| `0xC025` — `-2` mandatory | no | no | **no** — and see finding 3 |
| `0xC023` — `-2` optional | no | no | **no** |
| `-20` suites offered | yes | yes | yes — but **cannot be narrowed to the two** |
| `ecdsa_secp521r1_sha512` | no | no | **yes, when asked for** |
| secp521r1 as a key-exchange group | yes | no | **no** |
| Ed448 | no | no | **no** — `SignatureAlgorithm.ed448` does not exist |
| `trusted_ca_keys` | no | no | **no** |
| Client chain outside the platform trust store | — | — | **yes** |
| **`-20` against our station** | `illegal_parameter(47)` | `illegal_parameter(47)` | **completed** |
| **`-2` against our station** | suite negotiation failure | suite negotiation failure | cannot start |

### 1 · The `-20` result, isolated to one variable

Same client library, same TLS 1.3, same station, same P-521 station certificate — one field changed:

```
verifySignatureAlgorithms unset                    → RESULT: handshake refused — illegal_parameter(47)
verifySignatureAlgorithms = [ecdsa_secp521r1_sha512,
                             ecdsa_secp256r1_sha256] → RESULT: handshake completed
```

The refusal is byte-identical to the one the two phone stacks give, which is what makes this a
like-for-like comparison rather than an anecdote: the three stacks fail the same way, and exactly one
of them can be configured out of it.

Note what is *not* claimed. The `-20` profile also wants secp521r1 for **key exchange**
([`pki-model.md`](../pki-model.md): "Curve (PKI + key exchange): secp521r1"), and BoringSSL offers
x25519, secp256r1, secp384r1 and the post-quantum hybrid `0x11ec` — no P-521. The handshake completes
because our station accepts one of those groups; a station that insists on secp521r1 key exchange
would refuse it. Certificate curve and key-exchange curve are separate fields, and only the first one
was fixed here.

### 2 · `-2` is out on BoringSSL too, and on both suites

Neither `-2` suite is implemented. Static ECDH being gone was expected; the *optional* ephemeral
`0xC023` is gone as well — the same surprise Conscrypt and Network.framework produced. That makes
**four of five measured stacks** unable to speak `-2` at all, with BouncyCastle on the JVM the lone
exception (and OpenSSL's CLI for `0xC023` only).

### 3 · The fourth answer to "what happens when you pin a suite it does not have", and the worst one

The earlier report found three behaviours. This is the fourth:

| Stack | Pinning an unimplemented suite |
|---|---|
| SunJSSE | accepts it, returns it from `getEnabledCipherSuites`, never sends it |
| Network.framework | discards it silently and **widens** the offer to seventeen suites |
| Conscrypt | refuses out loud — the behaviour the others should have |
| **swift-nio-ssl** | **segmentation fault** |

```
EXC_BAD_ACCESS (SIGSEGV) — KERN_INVALID_ADDRESS at 0x8
  CNIOBoringSSL_SSL_CIPHER_standard_name
  NIOTLSCipher.standardName.getter
  closure #1 in TLSConfiguration.cipherSuiteValues.setter
```

`NIOTLSCipher` is a `RawRepresentable` over `UInt16`, so any code point can be *named* — unlike
Apple's closed `tls_ciphersuite_t`. Naming one BoringSSL does not implement yields a null
`SSL_CIPHER *` that `standardName` dereferences. Setting the two `-20` suites does not segfault but
trips a Swift precondition in `NIOSSLContext.init` instead: BoringSSL's cipher list governs TLS 1.2
suites, and its three TLS 1.3 suites are fixed. **The `-20` suite pair cannot be pinned on this
stack** — a client there always also offers `TLS_AES_128_GCM_SHA256`.

## Why not the alternatives

### Network.framework — measured out, and not close

Four independent walls, any one of them sufficient: it cannot name a static-ECDH suite, will not put
`0xC023` on the wire, cannot advertise `ecdsa_secp521r1_sha512`, and has no hook for
`trusted_ca_keys`. Recorded in the earlier report; nothing here changes it.

### swift-nio-ssl as it ships — the serious runner-up, and the one to reconsider first

It carries `-20` today. Against that:

1. **It carries no `-2` at all**, and `-2` is what the stations in the field speak. Taking it would
   mean shipping *two* TLS stacks on iOS — swift-nio-ssl for `-20` and something of our own for `-2`
   — which is strictly more code than one stack that does both, plus a large dependency.
2. **The `-20` profile cannot be pinned**, only checked afterwards. The earlier report's finding —
   verify the negotiated suite *after* the handshake — becomes a permanent workaround rather than a
   belt-and-braces check.
3. **No Ed448**, `-20`'s second signature suite. A compile error, not a configuration gap.
4. **No `trusted_ca_keys`**, so `[V2G2-651]` stays unmet — which only bites via (1), but it does bite.
5. **swift-nio and BoringSSL are a large dependency** for a Capacitor plugin whose current package is
   three Apple packages and one vendored C library, and it segfaults on a misconfiguration our own
   profile provokes.
6. **No middlebox-compatibility switch.** `pki-model.md` asks for the dummy `ChangeCipherSpec` to be
   off on both sides; a stack of our own simply does not send it.

**Reconsider this first if the priorities change.** If `-20` alone were the target, or if the work
had to ship in a fortnight, swift-nio-ssl would be the right answer and this decision would be wrong.
That is the reversal condition, and it is cheap to act on: the seam below is the same either way.

### A patched BoringSSL — the option §4 named, and the weakest

Restoring two removed cipher suites to a vendored C TLS library means owning a fork of a codebase
whose maintainers state it has no stable API and is not meant for general use, re-adding a key
exchange that was deliberately deleted, and re-vendoring it into swift-nio-ssl's own vendoring script
on every bump. It buys `-2` and inherits the rest of the list above. The measurement moved this from
"large" to "large *and* not sufficient on its own".

### Vendor another C stack — the option §4 did not name

mbedTLS, wolfSSL and s2n-tls all exist, and the libgoldilocks precedent says vendoring C is
acceptable here when the alternative is writing crypto ourselves. **None of them was measured**, so
what follows is why this is not being pursued rather than a finding about them:

- The question is narrow and answerable — does it implement `0xC025` and `0xC023`, does it advertise
  `ecdsa_secp521r1_sha512`, can it add `trusted_ca_keys`, does it do Ed448 — and any candidate must
  answer all four before it is worth a build. wolfSSL additionally needs a licence review: this
  repository is AGPL-3.0 and wolfSSL is GPLv2-or-commercial.
- Even a full pass leaves the C-fork maintenance question of the previous section.

**If someone wants to reverse this decision cheaply, measuring mbedTLS is where to start** — it is
the one of the three that plausibly answers all four yes. Half a day with the observer settles it.

### Ship iOS without conformant TLS

Free, and permitted: §4's "done when" accepts one platform running the profiles and *"the other
platform has a recorded decision rather than an omission"*. Rejected because the app is a car for
field testing, `-20` mandates TLS, and half the phones are iPhones — an EV simulator that cannot open
a conformant session on half the hardware is a demo rather than an instrument. Recording it here is
the point: this was chosen against, not overlooked.

## What the layer is

**A client. Two profiles. Four suites. No cryptography of our own.**

| | ISO 15118-2 | ISO 15118-20 |
|---|---|---|
| Version | TLS 1.2 only | TLS 1.3 only |
| Suites | `0xC023`, `0xC025` | `0x1302`, `0x1303` |
| Key exchange | ECDHE and static ECDH, secp256r1 | ECDHE secp521r1 |
| Peer authentication | server only | **mutual** |
| Signature algorithms | `ecdsa_secp256r1_sha256` | `ecdsa_secp521r1_sha512`, `ed448` |
| ClientHello extras | `trusted_ca_keys` (`[V2G2-651]`) | none — see below |

`trusted_ca_keys` is TLS-1.2-era and belongs in a `-2` ClientHello; TLS 1.3's equivalent is
`certificate_authorities`, and
[`BcV2GTlsClient.cs`](../../libs/WWCP_ISO15118/WWCP_ISO15118_Session/Transport/BouncyCastle/BcV2GTlsClient.cs)
sends neither on `-20`. Ours matches that until something says otherwise.

Taken from [`pki-model.md`](../pki-model.md) § *TLS profiles per protocol*, which is the same table
[`TlsProfiles.cs`](../../libs/WWCP_ISO15118/WWCP_ISO15118_Session/Transport/TlsProfiles.cs) pins on the
C# side. The two must not drift, and there is now a corpus to stop them (below).

### The primitives are already here

This is the load-bearing fact, and it is what makes "write a TLS client" a smaller sentence than it
sounds. Every cryptographic operation both profiles need is already a dependency of this package,
used by shipped code:

| Needed | From | Already used by |
|---|---|---|
| AES-GCM | swift-crypto | the `-20` key transport in `ContractProvisioning.swift` |
| ChaCha20-Poly1305 | swift-crypto | — (the one primitive nothing here uses yet) |
| HKDF-Expand/Extract, HMAC, SHA-256/384/512 | swift-crypto | the `-20` KDFs in `ContractProvisioning.swift` |
| ECDH + ECDSA on P-256 / P-521 | swift-crypto | `V2GKey`, `XmlDsigInterop` |
| AES-128-CBC | CommonCrypto | `AesCbc` in `ContractProvisioning.swift` |
| X.509 parsing and chain validation | swift-certificates | `V2GCertificates`, held to a corpus |
| Ed448 | CGoldilocks | `V2GEd448`, held to RFC 8032 |

`AesCbc` already carries the argument this decision inherits:

> Writing our own block cipher only here would make Swift the outlier in the risky direction.

The same reasoning bars us from writing ASN.1, curve arithmetic or an AEAD. **What is left to write
is framing, parsing, a key schedule and a state machine** — the same kind of work as `V2GTP`, the EXI
codecs and the EVCC state machines, all of which this project already carries in four languages.

### The seam already exists

`V2GByteStream` is `read(maxLength:)` and `write(_:)`, and TLS consumes one and produces one. So:

- `NetworkV2GTransport` keeps building the TCP `NWConnection` and its `NetworkByteStream`; the `.tls`
  branch stops asking `NWProtocolTLS` for anything and wraps that stream instead.
- `NetworkV2GTransport.accepts(chain:pinnedRoot:)` **moves across unchanged**. It was written with
  "no Security types in it so that it can be read on its own", takes DER as `[[UInt8]]`, and is
  already the whole trust decision — pinned root, no platform anchors, no hostname check.

The existing code that has to change is one branch of one function.

### What it deliberately does not do

- **No server.** The phone is the car — the workplan's invariant, and it removes half of TLS.
- **No session resumption, no PSK, no 0-RTT, no early data, no renegotiation, no compression, no
  middlebox-compatibility mode.** `[V2G2-740]` resume is an application-layer sequence and is already
  ported; it does not ask TLS for anything.
- **No suite, version or curve agility beyond the table above.** Four suites, two versions, and a
  configuration that cannot express anything else. A profile that cannot be widened cannot be widened
  by accident, which is the failure `TlsProfiles.cs` exists to prevent.
- **No general-purpose use.** This is not a TLS library; it must never be presented as one.

## How it will be held to something

A TLS client that only ever talks to our own station proves very little, so three oracles, and the
split is the one this project already settled: *a sequence is on the wire and wants a recording; a
derived key never travels and wants named cases.*

1. **The key schedule and the record layer → a vector corpus.** RFC 8448 publishes complete TLS 1.3
   traces with every derived secret, which is precisely a corpus of things that never reach the wire.
   Caveat, up front: its traces use `TLS_AES_128_GCM_SHA256`, which is **not** one of our two suites,
   so it validates the machinery and not the profile.
2. **The handshake itself → a recorded trace.** BouncyCastle takes an injected `SecureRandom`, so a
   C# client and a C# station seeded deterministically produce a **byte-reproducible handshake** on
   our exact suites — the same oracle shape as the twenty-one recorded sessions, one layer down, and
   it closes the gap RFC 8448 leaves. This is the piece that has to be built before the Swift code.
3. **What we put on the wire → the observer.** Our own stack becomes row five of the same table that
   condemned the other four, checked against the same eight `iso15118` predicates. If it cannot pass
   the test we judged the platforms by, it is not done.

Plus the negative cases, which are the ones that matter for a security component: a chain that does
not reach the pinned root must fail; a station offering a suite outside the profile must fail; a
`-20` session against a P-256 station certificate must fail, precisely because the control run above
makes that combination *work* on every other stack.

## The risks, stated plainly

**"Do not roll your own crypto" is the right default and this decision goes against it.** What is
claimed is narrower — we are not rolling our own crypto, we are rolling our own protocol over other
people's crypto — but that is a mitigation, not an exemption. The rest of the honest list:

- **Lucky13 and its family.** `-2` is CBC-with-HMAC in TLS 1.2, whose safe implementation is a timing
  problem that has bitten every major stack. A first implementation will be a naive one. It must be
  written down as a known deviation, not discovered later.
- **No audit, no fuzzing yet.** The record layer and handshake parser eat untrusted bytes from a
  station, which is the classic vulnerability surface. Fuzzing those two entry points belongs in the
  work, not after it.
- **Two TLS implementations to keep in agreement** — bctls on Android, ours on iOS. Real cost;
  bounded by holding both to oracle 2 above, which is the same answer this project already gives for
  four EXI codecs and three state machines.
- **The threat model is favourable and should not be leaned on.** The peer is a station whose QR code
  a human physically scanned, `SessionConfig.parse` already refuses non-private hosts, and sessions
  are short. That lowers the stakes; it does not make a bug acceptable.

## Staging

Ordered so each stage is worth something on its own and the work can stop between any two.

| | What | Why here |
|---|---|---|
| **A** | TLS 1.3 client, `-20` profile, server auth only, against oracles 1–3 | TLS 1.3 is the simpler protocol *and* the one with published vectors. Starting at the older protocol would mean starting at CBC. |
| **B** | Mutual auth: client `Certificate` + `CertificateVerify`, `certificate_authorities` | Completes `-20`. This is where the Vehicle chain and secp521r1 signing land. |
| **C** | TLS 1.2 client, `-2` profile: static and ephemeral ECDH, CBC-with-HMAC, the PRF, `trusted_ca_keys` | The half nothing off the shelf provides, and the half with the timing hazard. |

Stopping after B leaves iOS exactly where swift-nio-ssl would have left it — which is the honest way
to describe the risk of this decision, and the reason B comes before C rather than after.

**Estimate, labelled as one:** roughly 2,000–2,500 lines of Swift plus tests, from the message
inventory of the two profiles. For scale, the C# side's *integration* with BouncyCastle — glue over
a finished library — is 1,722 lines. Nothing here is measured; it is the number to check the first
stage against.

## Honest limits

- **swift-nio-ssl was measured on macOS, not on iOS.** Same vendored BoringSSL, but the same caveat
  the earlier report carries for its two emulators applies here.
- **The `-20` completion was measured with certificate verification disabled** on the client, so what
  it proves is that the `CertificateVerify` signature over a P-521 key was accepted — the sigalg
  question — and not that a full chain validation would pass.
- **secp521r1 key exchange was not obtained from any stack**, ours included, because ours does not
  exist yet. Our station accepts other groups today; whether a third-party `-20` station does is
  unmeasured and is the next thing to find out.
- **mbedTLS, wolfSSL and s2n-tls are unmeasured**, and the paragraph above says so rather than
  ranking them.

## Re-running the fifth stack

[`tools/tls-nio-probe/`](../../tools/tls-nio-probe) is checked in beside the observer and for the
same reason — the decision rests on finding 1, so finding 1 has to be repeatable. It is a standalone
package that no gate builds, so swift-nio never reaches `swift/Package.swift`. Modes: `names`,
`default`, `c023`, `c025`, `iso2`, `iso20`, `iso20-nosigalg`, `iso20-sigalgs-only`.

Start the observer, then point the probe at it:

```bash
python3 tools/tls-clienthello-observer.py nio-iso20 44370
```

```bash
swift run --package-path tools/tls-nio-probe TlsNioProbe 127.0.0.1 44370 iso20-sigalgs-only
```

For the station half, the `-20` profile of the throwaway `TcpV2GListener` from the earlier report —
`iso20` for the conformant P-521 certificate, `iso20-p256` for the control. `c023` and `c025` are the
two halves of finding 3 and are expected to crash; that is the result.

Worth re-running rather than citing, for the reason the earlier report gives: a claim about a TLS
stack is only worth what its last re-run says. That applies with particular force to finding 1, which
is the whole reason this decision had to argue against swift-nio-ssl instead of dismissing it.
