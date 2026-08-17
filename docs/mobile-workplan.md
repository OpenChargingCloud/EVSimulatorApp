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

**None of it is ported, and the drift comes in two kinds — one now visible, one still not.**

The **codec** drift is visible, since 2026-08-16: five Kotlin modules are red, because a deliberate
2026-08-08 change to what the C# codec emits never reached the ports. That is stage 2a, and it was
found within two minutes of the gate becoming runnable.

The **session** drift is not, and cannot be. The state machines are held to recorded sessions in
`ISO15118ConformanceTests.Simulation/Vectors/`, and all twelve traces are frozen at 2026-08-06/07 —
the oracle is a snapshot from *before* the drift, so those twenty commits are invisible **by
construction**. A green session gate means "agrees with the C# EVCC of 2026-08-07", not "current".
Only new recordings can change that, which is stage 2b.

What the gates said on 2026-08-16, on Windows — before stage 1, and after:

| Gate | Before | After |
|---|---|---|
| `app/` (WebView UI) | 54 green | **54 green** |
| `typescript/` | 34 green | **34 green** |
| `capacitor/` | would not run — `@capacitor/core` not installed | **11 green** (the script installs) |
| `kotlin/` | would not **configure** — Gradle 7.0.2 on the PATH against KGP 2.1.0, no wrapper checked in | runs, and is **red in five modules** — see stage 2a |
| `swift/` | no toolchain here, so never run at all | CI runs it on macOS: **8 of 222 tests fail**, the same drift as Kotlin |

Before stage 1 **neither repository had CI**. Kotlin and Swift had run by hand or not at all since
2026-08-07, which is why nine days of drift went unseen — and why the first full run found it in
under two minutes.

Of the matrix's 22 `EV→` scenario rows, roughly **13 would run on a phone today**: `-2` AC/DC EIM,
`-2` Plug & Charge with signed `AuthorizationReq` and `MeteringReceiptReq`, `-2` renegotiation
(`[V2G2-841]`, reactive and proactive), `-20` AC/DC in Scheduled *and* Dynamic, `-20` Plug & Charge,
BPT (services 5/6), MCS and MCS_BPT (8/9, drivable as the DC message set), and the multi-protocol
SAP offer (the `*-sapboth` traces).

---

## 1 · Repair the instrument — **done 2026-08-16**

Nothing below could be trusted while two of the four gates could not be started.

- **A Gradle wrapper is checked in** for `kotlin/` — the same 8.14.3 one `capacitor/android` and
  `shell/android` already carried, byte for byte, rather than a fresh download. Nothing had pinned a
  version, so the build took whatever `gradle` was on the PATH; on 7.0.2 that was a plugin-isolation
  error naming neither the Gradle nor the Kotlin version.
- **[`tools/port-gates.sh`](../tools/port-gates.sh) is the one definition of the gates**, called by a
  person and by CI so the two cannot disagree. It checks for the parent repository's corpus up front,
  prints every skip as SKIPPED with its reason, and reads exit codes rather than summary lines.
- **CI runs them in both repositories** — in EVSimulatorApp on every push, laying out parent + this
  commit in two checkouts because the corpus lives above; and in the conformance repository when the
  submodule pointer or the corpus moves. Linux for Kotlin and Node, macOS for Swift. `dotnet test`
  is deliberately not there: it needs the ISO schemas, and fetching those is a person accepting a
  licence.

Two things had to be fixed to get there, and both were the instrument rather than the ports:

- **A time bomb in the certificate corpus.** The "genuine, current" CRL was issued with a real CRL's
  seven-day life, so seven days after each regeneration it read as expired — and an expired list
  answers `Unknown`, which is exactly what the corpus's *other* two cases are built to require. Issued
  2026-07-31, stale since 2026-08-07, and invisible to the C# gate because that side only ever
  *writes* this material. The current lists now get a decade, and `TheCorpusHasNotAgedOut` fails in
  the offline suite — with a year of headroom on the lists and ninety days on the certificates — so
  it can never come back quietly.
- **`kotlin/gradlew` would have been committed non-executable.** `core.filemode` is false on Windows,
  so a Linux runner would have got "Permission denied" on the very first CI run. The three wrapper
  copies are now one blob with the right mode, and `.gitattributes` pins `gradlew` and `*.bat` so
  which line endings a file has stops depending on who committed it.

And one that only the first real CI run could have shown, because no Swift toolchain exists on the
machine this was written on:

- **`swift test` exits 0 while its own suite fails.** Measured on the macOS runner: `Executed 222
  tests, with 13 failures (2 unexpected)` and `Test Suite 'All tests' failed` — and a zero exit
  status. The gate reported PASSED and the job went green over eight genuinely failing tests. That is
  the same lie as an aborted `dotnet test` printing per-assembly "Bestanden!" lines, one layer
  further down, and it landed in a script written specifically to refuse it. `port-gates.sh` now
  reads the suite's own summary as well, and either signal fails the gate.

**What the repaired instrument said, immediately:** the Kotlin gate is **red in five modules** — and
that is stage 2's list, below, no longer a prediction.

## 2 · Close what the gate already found, then move the oracle

Half of this stage arrived the moment the gate could run. The **codec** drift is measured; the
**session** drift still has to be provoked.

### 2a-i · The two grammar switches — **done 2026-08-16**

`EVSimulatorApp.Codegen` now takes `--doc-order` and `--particles`, the three `regenerate.sh` state
them, and all three ports were regenerated. Three decisions worth keeping:

- **They default to what this project decided**, `ExiSorted` / `SchemaConformant`, not to the
  library's cbexigen-compatible default. The tool's only consumers are the ports here, and a default
  that needs a flag to be correct is a default that will be forgotten — which is exactly what
  happened for nine days.
- **An unknown value is refused**, where the source generator's own reader falls back to its default.
  A misspelling in a csproj must not break somebody's build; a misspelling in a regeneration script
  that silently emits the *other* wire format is the failure this whole exercise exists to undo.
- **Every run prints the grammar it used**, so a regeneration log can be read afterwards.

What that fixed: **ACDP went from 6/8 to 8/8** in Kotlin, and the one Swift file matching it changed
too. TypeScript changed **nothing at all**, correctly — it has no ACDP, WPT or DER port.

**And what regenerating showed, which is the finding:** `--doc-order` reaches the ports;
`--particles` does not. Exactly one generated file changed per language, the ACDP codec. Not one WPT
byte moved, though the flag was passed and accepted.

The two switches are not symmetric, and nothing had said so:

| | Decided in | Consumed by |
|---|---|---|
| `--doc-order` | `Grammar/GrammarBuilder.cs` — the **shared** front end | the plan's element order, which every emitter reads |
| `--particles` | carried on the plan, and acted on in `Emit/CodecEmitter.cs` | **the C# emitter alone** |

`grep -rn ParticleGrammar` over both `Emit/` directories finds three hits, all of them in
`CodecEmitter.cs`. So for Kotlin, Swift and TypeScript the flag is accepted and ignored — which is
why WPT sat at 8/16 before the switch and sits at 8/16 after it.

### 2a-ii · The forced-occurrence rule — **done 2026-08-16**

Ported into all three emitters, and the AC DER corpora are byte-exact:

| Corpus | Before | After |
|---|---|---|
| AC_DER_IEC | 16/18 | **18/18** ✅ |
| AC_DER_SAE | 15/16 | **16/16** ✅ |

**The bug was not where the diff was.** Fixing the encode side changed 45 generated files and moved
*nothing*: the encoder had been writing the narrow SE correctly within a minute of the change, and
the decoder still read a 2-bit code where its own encoder had just written a 1-bit one. The reason
was a parameter that was never passed — a lone repeating child carries `minOccurs` on the
**sequence**, not on the child, and `EmitDecodeRepeating` took only the maximum:

```
EmitDecodeRepeating(c, "list", ListBounds(c, sp).Max, "        ");   // Min silently dropped
```

Both bounds now come from the same place the encode side takes them, in all three emitters, and the
forced occurrences are unrolled ahead of the loop rather than looped — each has the single production
`SE(item)`, so its code is one bit and there is nothing to branch on.

TypeScript carries the rule and changes no byte: none of its sets has a `minOccurs≥2` particle, since
all five in ISO 15118 sit in WPT and the two AC DER sets. It is carried anyway — three emitters
implementing the same grammar differently is how the ports drifted in the first place.

### 2a-iii · The WPT particle grammar — **done 2026-08-16**

**WPT is 16/16, and the Kotlin gate is green.** `plan.ParticleGrammar` was carried on the plan and read
by `Emit/CodecEmitter.cs` alone. Where that file branches between
`EmitEncodeOptionalRunWithMidListSchema` and `...WithMidList` — and between the decode pair beside
them — the Kotlin emitter has a single `EmitEncodeMidRunList` / `EmitDecodeMidRunList`, which is the
cbexigen-compatible one.

So the task is to add the schema-conformant variant beside it and branch on `ParticleGrammar`. The C#
original is about 35 lines per direction and *simpler* than the one already there, because it is one
grammar state rather than three.

**Kotlin only.** Swift has no WPT module — refused on principle, because `WPT_LF_TransmitterDataType`
is the construct cbexigen's own encoder cannot represent, so there is no oracle — and TypeScript has
neither WPT nor ACDP nor the DER sets. That also makes the last red module the one language whose
gate runs on Linux, so CI judges it directly.

The three `jsonld-agreement` failures and `v2g-dispatch`'s WPT frame ride on the same rule.

### What is still red, measured 2026-08-16 after 2a-i

| Corpus | Before 2a-i | After |
|---|---|---|
| **ACDP** | 6/8 | **8/8** ✅ |
| AppProtocol · `-2` · `-2` fragments | 17/17 · 39/39 · 4/4 | unchanged ✅ |
| `-20` AC · DC · CommonMessages | 10/10 · 16/16 · 26/26 | unchanged ✅ |
| AC_DER_IEC | 16/18 | 16/18 |
| AC_DER_SAE | 15/16 | 15/16 |
| WPT | 8/16, and `v2g-dispatch`'s WPT frame | 8/16 |
| JSON-LD | 3 of 3 failing | 3 of 3 failing |

Everything still short is a `minOccurs≥2` particle, which is 2a-ii. Swift carries the same, shown by
the first macOS CI run — 8 of 222 tests. TypeScript has no port of these three sets, so it has
nothing to carry.

### Found on the way: a suite in no gate at all

`EVSimulatorApp.Codegen.Tests` is **19 red out of 77**, and was before any of this — verified by
stashing the whole change and re-running. It is in neither gate: not in the conformance repository's
`dotnet test` (four assemblies, none of them this one), and not in `port-gates.sh`. The failures look
like the same forced-occurrence family, so 2a-ii may well close them; that has to be measured, not
assumed. Either way the suite belongs in a gate, which is a stage 1 item that stage 1 missed.

### 2b · The session drift — provoked 2026-08-16

Two scenarios recorded and two Kotlin replays added, and **both fail** — which is the deliverable,
not a setback:

| Recording | Diverges | Why |
|---|---|---|
| `iso2-dc-eim-battery` | exchange 5, `ChargeParameterDiscoveryReq` | the recorded car asks for the energy it still needs; the port asks for a fixed amount. There is no battery in the ports. |
| `iso2-dc-eim-renegotiate` | ~~exchange 12~~ **closed 2026-08-16** | the DC isolation sequence was inline at its one call site, so the return path could not reach it |

**The trap this walked into first:** the ports' trace tests name each scenario by hand, so a new
recording is invisible until somebody adds a test for it. This project has been bitten by exactly
that before — `DECODABLE` named three sessions while the corpus held twelve — and the same shape was
still here. Recording a session is half the work; the other half is that something reads it.

Still to record, from the twenty state-machine commits: `EVReady = false` through the DC isolation
sequence, and a `-20` session carrying a `SupportedServiceIDs` filter. Closing the two failures above
is stage 3.

#### The original note, for the mechanics

The state machines are held to recorded sessions, and all twelve traces are frozen at 2026-08-06/07 —
so the ~20 state-machine commits since then are invisible by construction, not by luck. A scenario is
a row in the `Scenarios` table of
`ISO15118ConformanceTests.Simulation/Traces/SessionTraceCorpusTests.cs`; `RegenerateTheCorpus` is
`[Explicit]` and writes into the source tree precisely so the Kotlin and Swift suites see it.
Candidates, one trace each: a charge loop that ends on the battery's own target, a `-2` DC session
with the isolation sequence, a DC renegotiation through `CableCheck` and `PreCharge`, a `-20` session
carrying a `SupportedServiceIDs` filter.

**Done when:** both lists exist as test failures rather than as prose, and 2a's are gone.

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
| 1 · instrument | everything | **done 2026-08-16** |
| 2a-i · grammar switches | 2a-ii | **done 2026-08-16** |
| 2a-ii · forced-occurrence rule | AC DER agreement | **done 2026-08-16** |
| 2a-iii · WPT particle grammar | — | **done 2026-08-16** |
| 2b · session corpus | 3, and Resume specifically | nothing |
| 3 · state machines | — | 2b |
| 4 · TLS | shipping anything that claims conformance | its own measurement, which needs nothing |
| 5 · discovery + shell | — | 4 for the iOS entitlement timing |

**Stage 2a is closed and the Kotlin gate is green — ten corpora out of ten, byte-exact.** The two
items that need nobody's permission and nothing else first are now **2b**, moving the session oracle,
and **stage 4's measurement** — still the one that could change the shape of the rest.
