# The mobile workplan

What the phone side needs, in the order it needs it. Measured 2026-08-16, not estimated — every
number below came from the tree as it stands that day.

> References to `Vectors/`, `SessionTraceCorpusTests.cs`, the interop matrix and the run notes point
> into the **ISO15118ConformanceTests** repository — the parent that carries this app as a submodule —
> not into this one. Same convention as [`roadmap.md`](roadmap.md).

## The invariant this plan is shaped by

**The phone is the car. It is never the station, and that is the design.**

`kotlin/`, `swift/` and `typescript/` carry the vehicle side only — `Evcc2`, `Evcc20Base`/`Ac`/`Dc`,
and no `Secc` of any kind. A phone in somebody's hand simulates a vehicle; the station role has its
own home in this repository and it is C# on a Raspberry Pi (`pairing/EVSimulatorApp.Pi`,
`SeccStation.cs`). So every `←SECC` row of the conformance repository's interop matrix — 19 of its
41 scenario rows, where *their* EV drives *our* station — is **out of scope here by construction**.

That matters for reading this plan: a missing `Secc20Dc` is not an unported gap, and no stage below
will ever add one.

## Where this stands

The ports were last regenerated at `f782e6c` (**2026-08-07**). Since then `WWCP_ISO15118` has taken
**116 commits**, about twenty of them in the EVCC/SECC state machines themselves — the battery model
and the charge-loop end condition, the charge loop's own interval, `EVReady = false` through the DC
isolation sequence, DC renegotiation through `CableCheck`/`PreCharge`, the `-20` message timeouts,
the `SupportedServiceIDs` filter, `[V2G2-460]`, `CertificateParameterSetId`.

**None of it is ported, and no port test is red.** The ports are held to recorded sessions in
`ISO15118ConformanceTests.Simulation/Vectors/`, and all twelve traces are frozen at 2026-08-06/07 —
the oracle is a snapshot from *before* the drift. That is the uncomfortable half: the gate cannot
see what it is missing, so "green" currently means "agrees with the C# EVCC of nine days ago".

What ran on 2026-08-16, on Windows:

| Gate | Result |
|---|---|
| `app/` (WebView UI) | **54 green** |
| `typescript/` | **34 green** |
| `capacitor/` | does not run — `@capacitor/core` not installed (`npm install`) |
| `kotlin/` | **does not configure** — Gradle 7.0.2 on the PATH, `kotlin/build.gradle.kts` pins KGP 2.1.0, and no wrapper is checked in |
| `swift/` | no Swift toolchain on this platform |

And **neither repository has CI** (no `.github/workflows`). Kotlin and Swift have run by hand or not
at all since 2026-08-07.

Of the matrix's 22 `EV→` scenario rows, roughly **13 would run on a phone today**: `-2` AC/DC EIM,
`-2` Plug & Charge with signed `AuthorizationReq` and `MeteringReceiptReq`, `-2` renegotiation
(`[V2G2-841]`, reactive and proactive), `-20` AC/DC in Scheduled *and* Dynamic, `-20` Plug & Charge,
BPT (services 5/6), MCS and MCS_BPT (8/9, drivable as the DC message set), and the multi-protocol
SAP offer (the `*-sapboth` traces).

---

## 1 · Repair the instrument

Nothing below can be trusted while two of the four gates cannot be started.

- **Check in a Gradle wrapper** for `kotlin/`. KGP 2.1.0 needs a Gradle far newer than the 7.0.2 that
  happened to be on this machine, and today nothing in the tree pins it — every developer gets
  whatever `gradle` is on their PATH, and the failure is a plugin-isolation error that names neither
  version. `capacitor/android` includes this build, so it wants the same treatment.
- **Say how `capacitor/` is run.** Its tests need `npm install` first; the README does not say so and
  the failure is an `ERR_MODULE_NOT_FOUND` for `@capacitor/core`.
- **CI for the four port gates** — Kotlin and TypeScript and the app on Linux, Swift on macOS. The
  C# gate has the conformance repository; the ports have nothing, which is why nine days of drift
  went unnoticed.

**Done when:** a fresh clone runs all four gates from documented commands, and a push does it
unasked.

## 2 · Make the drift visible

The gap list has to be measured, not guessed — and the only way to measure it is to move the oracle.

- **Record the scenarios the C# EVCC gained since 2026-08-07.** A scenario is a row in the
  `Scenarios` table of `ISO15118ConformanceTests.Simulation/Traces/SessionTraceCorpusTests.cs`;
  `RegenerateTheCorpus` is `[Explicit]` and writes into the source tree precisely so the Kotlin and
  Swift suites see it. Candidates, one trace each: a charge loop that ends on the battery's own
  target, a `-2` DC session with the isolation sequence, a DC renegotiation through `CableCheck` and
  `PreCharge`, a `-20` session carrying a `SupportedServiceIDs` filter.
- **Run the ports against the new corpus.** What turns red *is* the gap list — named exchange by
  exchange, byte by byte, in the two places a trace comparison points at.

**Done when:** the list exists as test failures rather than as prose.

## 3 · Carry the state machines forward

The port work proper. Two piles, and the first is bigger than it looks.

- **Whatever stage 2 turned red.**
- **The gaps the ports already name in their own class comments** — deliberately named there rather
  than silently absent:
  - `-2` **tariff-signature verification** (§7.9.2.5). The tuple *choice* is ported;
    `Iso2TariffResult` carries three fields where C#'s carries seven, and nothing takes a verify key.
  - `-20` **price-schedule signature verification** (`AbsolutePriceSchedule`).
  - **Contract provisioning** — `-2` Install/Update and `-20` `CertificateInstallation`.
  - **Resume.** *Pause* is ported on both protocols as a stop mode; rejoining a paused session
    (`[V2G2-740]`) is not, and it needs a trace that pauses and rejoins — so it depends on stage 2.
  - `-20` **`ServiceRenegotiation`** (`[V2G20-1477]`). `-2` renegotiation is ported already.

**Done when:** the ports drive the same scenarios as the C# EVCC, against the same recordings.

## 4 · TLS — the real wall, and a measurement before any code

Neither transport pins cipher suites: `TcpV2GTransport` takes `SSLContext.getInstance("TLS")` and
`NetworkV2GTransport` takes `NWProtocolTLS` defaults. Both accept whatever the platform offers. On
our own side that was not enough, and it is written down —
`docs/matrix/ours-iso2-trusted-ca-keys.md` in the conformance repository:

> `SslStream` cannot add a ClientHello extension on any platform, so the managed BouncyCastle stack …
> grew ISO 15118-2's transport: TLS 1.2 and the two `-2` suites.

`TlsPlatform.cs` says the same for `-20`: even Windows/Schannel cannot pin the suites, use
secp521r1, or present a client chain rooted outside its trust store.

- **Measure first, on a real device.** One `-2` TLS handshake from an Android phone against our own
  SECC, and one from an iPhone: which suite does it land on, and does the station's `-2` profile
  accept it? Half an hour, and it replaces a derivation with a fact. Everything below is written
  *assuming* the platform stores no longer carry `TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256`; if the
  measurement says otherwise, this stage shrinks.
- **Android is the easy half if it fails:** BouncyCastle's `bctls` is the same library the C# side
  already uses, so the port would be a second transport beside `TcpV2GTransport` — suites pinned,
  `trusted_ca_keys` available, secp521r1 and Ed448 reachable.
- **iOS is an open decision, not a task.** Network.framework can only append suites the system
  already implements, and swift-nio-ssl sits on BoringSSL, which dropped static ECDH too. Either a
  BoringSSL build with those suites restored, or a Swift TLS layer — both are large. Write the
  decision down before writing code.

**Done when:** `-2` on the prescribed suites and `-20` over mutual TLS 1.3 run from at least one
platform, and the other platform has a recorded decision rather than an omission.

## 5 · Discovery, and the shell

- **SDP is optional by design** — the QR code stands in for plug-in + SLAC + SDP, which is the whole
  premise in [`CONCEPT.md`](CONCEPT.md). Adding it anyway costs a `WifiManager.MulticastLock` on
  Android and the `com.apple.developer.networking.multicast` entitlement on iOS, which Apple grants
  on request. Worth starting the request early if it is wanted at all; it is the only item here with
  someone else's clock on it.
- **`shell/` has not been touched since 2026-08-01** — Capacitor version, Android SDK level and iOS
  deployment target all want a look before anything ships.

**Done when:** the shells build against current toolchains, and SDP is either working or recorded as
declined.

---

## Deliberately not on this plan

| | Why |
|---|---|
| A `Secc` port | The invariant above. The station is the Pi, in C#. |
| SLAC | Raw HomePlug GreenPHY frames on the pilot line. No app on either OS gets them — a platform fact, not a backlog item. |
| WPT · ACDP state machines | Codec-only in C# too, and for `WPT_LF_TransmitterDataType` there is no reference encoder to check against. Parity, not a gap. |

## Order, and what unblocks what

| Stage | Blocks | Blocked by |
|---|---|---|
| 1 · instrument | everything | nothing — start here |
| 2 · corpus | 3, and Resume specifically | 1 (Kotlin/Swift must be runnable) |
| 3 · state machines | — | 2 |
| 4 · TLS | shipping anything that claims conformance | its own measurement, which needs nothing |
| 5 · discovery + shell | — | 4 for the iOS entitlement timing |

Stage 4's *measurement* is the one item that can be done today, in parallel with stage 1, and it is
the one that could change the shape of the rest.
