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
| `swift/` | no toolchain on this platform | unchanged; skipped loudly, and CI runs it on macOS |

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

**What the repaired instrument said, immediately:** the Kotlin gate is **red in five modules** — and
that is stage 2's list, below, no longer a prediction.

## 2 · Close what the gate already found, then move the oracle

Half of this stage arrived the moment the gate could run. The **codec** drift is measured; the
**session** drift still has to be provoked.

### 2a · The codec drift, measured 2026-08-16

Five Kotlin modules are red, and the cause is one commit: **`914d1da`, 2026-08-08, "Where cbexigen
and the schema disagree, follow the schema"**. It changed what the C# codec *emits* for two
constructs — ACDP's document-element numbering and WPT's mid-run particle grammar — and moved the
vectors to match. The ports never received the change. `6ab05b8` the same day added six more vectors,
for the four `minOccurs>=2` particles nothing had ever populated.

| Red module | Corpus that moved |
|---|---|
| `exi-iso20-acdp` | `Iso15118_20.ACDP.vectors.json` — 6/8 round-trip |
| `exi-iso20-acderiec` · `exi-iso20-acdersae` | the two AC DER corpora |
| `exi-iso20-wpt`, and `v2g-dispatch`'s WPT frame | `Iso15118_20.WPT.vectors.json` |
| `jsonld-agreement` | `JsonLd.documents.json` |

**Regenerating the ports today would not fix it, and that is the actual task.** The two switches are
MSBuild properties — `ExiDocumentElementOrder`, `ExiParticleGrammar` — set in
`libs/WWCP_ISO15118/Directory.Build.props` and passed to the source generator by each codec's csproj.
`tools/EVSimulatorApp.Codegen` sits *above* that directory, so it inherits neither, and its command
line has no flag for either: `--xsd --out --lang --namespace --codec --fragments`. So the work is, in
order: **give the Codegen tool the two switches**, have the three `regenerate.sh` scripts pass them,
regenerate, and watch the five modules go green. Swift and TypeScript carry the same defect and have
no gate on this machine to say so.

### 2b · The session drift, still to be provoked

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
| 2a · codec drift | a green gate, and therefore every claim made from one | nothing — the diagnosis is finished, the task is named |
| 2b · session corpus | 3, and Resume specifically | nothing |
| 3 · state machines | — | 2b |
| 4 · TLS | shipping anything that claims conformance | its own measurement, which needs nothing |
| 5 · discovery + shell | — | 4 for the iOS entitlement timing |

**2a is the one to take next**, and not only because it is next: CI is now red, on a cause that is
dated, understood and sized. The two items that need nobody's permission and no other work first are
2a and stage 4's measurement.
