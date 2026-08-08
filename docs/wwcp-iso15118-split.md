# Splitting the repository: codec → WWCP_ISO15118, conformance stays

This repository currently holds two things that have grown together but do not belong together:

1. **an ISO 15118 EXI codec** — a source generator, the schemas it consumes, and the tests that
   pin its output byte for byte;
2. **a conformance and interoperability harness** — SECC/EVCC state machines, TLS profiles,
   SLAC/SDP, and the live runs against Josev, EVerest and EVDriveFlow.

The first is a library other projects want to reference. The second is a test rig, and its value
is precisely that it is *not* the implementation under test. This document records which files go
where, and — more usefully — the three places where the split is not mechanical.

Written after the namespace rewrite (`Vanaheimr.V2G.*` → `cloud.charging.open.protocols.*`), which
is the step that made the seam visible.

## The dependency graph already agrees

Nothing in the codec set references anything in the conformance set. The arrows all point one way:

```
  EVSimulatorApp.Codegen ──┐  Exi.SourceGenerator ─────────┐   (Roslyn; netstandard2.0)
  (Kotlin/TS/Swift back    │           ▲                   │
   ends; linked source) ───┘           │ Analyzer          │
                                       │                   ▼
                        Exi.Prototype ──── BitReader/BitWriter, ExiPrimitives,
                              ▲            AppProtocol codec, V2GTP header
            ┌─────────────────┼──────────────────┬──────────────┐
            │                 │                  │              │
     Exi.Iso15118_2   Exi.Iso15118_20.*    Exi.XmlDsig    Exi.Dispatch
            │                 │                  │              │
            └─────────────────┴──────────────────┴──────────────┘
                              │
        ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌┼╌╌╌╌╌╌╌ the seam ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
                              │
            ┌─────────────────┴──────────────┐
     Vanaheimr.V2G.Simulation        Experiments.Pqc
            │                                │
     Simulation.Cli ── Simulation.Tests ── Pqc.Tests
```

The conformance side additionally references `WWCP_ISO15118_SDP`, `_NetworkInterfaces`, `_SLAC` and
`_PKIBuilder` — that is, it *already* depends on WWCP_ISO15118. After the move it depends on it for
the codec too, and this repository's own `libs/WWCP_ISO15118` submodule keeps working unchanged.
No cycle appears in either direction.

## What moves

| Project | .cs | What it is |
|---|--:|---|
| `WWCP_ISO15118_EXI_SourceGenerator` | 31 | XSD → grammar → codec, plus the C# back end — see "the port back ends" below |
| `WWCP_ISO15118_EXI` | 12 | `BitReader`/`BitWriter`, EXI primitives, the hand-written AppProtocol codec, the V2GTP header |
| `WWCP_ISO15118_2` | 2 | -2 schemas + `PhysicalValue`, `V2GSignature` |
| `WWCP_ISO15118_20.CommonMessages` | 2 | schemas + `RationalNumber`, `V2GSignature` |
| `WWCP_ISO15118_20.{DC,AC}` | 2 each | ditto |
| `WWCP_ISO15118_20.{AC_DER_IEC,AC_DER_SAE}` | 1 each | ditto |
| `WWCP_ISO15118_20.{WPT,ACDP}` | 0 | schema-only; everything is generated |
| `WWCP_ISO15118_XMLDSig` | 0 | schema-only |
| `WWCP_ISO15118_EXI_Dispatch` | 2 | payload type ↔ message set |
| `ChargingSimulation` (demos/) | 6 | the "every line is a real EXI round-trip" console demo |
| `WWCP_ISO15118_EXI_Tests` | 55 | codec tests, minus `Interop/` and the port-emitter tests |
| `WWCP_ISO15118_EXI_Tests/Vectors/` | 16 files | the byte-level oracle |
| `tools/cbv2g-ref/`, `tools/exificient-ref/` | — | the reference encoders that *produce* those vectors |

Roughly 130 source files. The generated code is not among them: it exists only in `obj/`, produced
at build time from the XSDs, so "the generated code moves" means the schemas and the generator move.

## What stays

| Project | .cs | Why it is not the codec |
|---|--:|---|
| `Vanaheimr.V2G.Simulation` | 53 | SECC/EVCC state machines, TLS profiles, SLAC, metering, OCPP — **superseded 2026-08-08, see "The second move" below: all of it except OCPP now follows the codec** |
| `Vanaheimr.V2G.Simulation.Cli` | 5 | the harness binary — **superseded twice**: it became `WWCP_ISO15118_CLI` in the codec repository, and was then split by role into `WWCP_ISO15118_SECC` and `WWCP_ISO15118_EVCC`, each with its own solution and README |
| `Vanaheimr.V2G.Simulation.Tests` | 68 | loopback, traces, and `Interop/` — Josev, EVerest, EVDriveFlow. **Gone**: the bulk had already become `ISO15118ConformanceTests.Simulation`, and the five-file transport remnant followed the code as `WWCP_ISO15118_Session_Tests` |
| `Vanaheimr.V2G.Experiments.Pqc` (+ Tests) | 6 | ML-DSA in a V2G signature; research, not the standard |
| `WWCP_ISO15118_EXI_Tests/Interop/` | 6 | `Josev*` — see the boundary question below |
| `tools/interop-{josev,everest,evdriveflow}/` | — | live harnesses |
| `docs/interop-runs/` | 30+ dirs | evidence of runs; belongs with the rig that produced it |

## The port back ends did not go to WWCP_ISO15118

The generator emits C#, Kotlin, Swift and TypeScript. Only the first is of use to a .NET
consumer, and the sizes say the rest loudly:

| | lines |
|---|--:|
| front end (`Xsd/` + `Grammar/`) | 1 771 |
| `Emit/` shared base | 2 634 |
| **C# back end** | **715** |
| Kotlin / Swift / TypeScript | 8 651 |

So the three port back ends, the `Codegen` driver that runs them, and their nine test files live
in the app instead — `tools/EVSimulatorApp.Codegen` and `.Codegen.Tests`. The app is their only
consumer, and putting them in an ISO 15118 library would have meant 8 651 lines of Kotlin and
Swift templating that no .NET caller will ever reach.

Nothing new was invented to make this work. `Codegen` never referenced the generator as an
assembly — the generator is a netstandard2.0 analyzer with a Roslyn dependency the driver must
not carry — so it always compiled `Xsd/`, `Grammar/` and `Emit/` in as linked source. The app does
the same, across the submodule boundary.

Two consequences worth stating plainly:

- **`CodecEmitter` is the base class all four back ends specialise, and it stays here.** A change
  to it is a change to two repositories. That is the price of the split, and it is not hidden:
  the app's build breaks immediately if the base moves out from under it.
- **The differential tests moved too.** Every Swift and Kotlin test asserts against what the C#
  back end emits for the same schema — that is what caught a TypeScript emitter spelling an
  optional type the way Kotlin does. They still work, because the C# emitter arrives through the
  same linked sources; they simply run in the app's suite now. `EmitterHarness` is linked rather
  than copied so the two sides cannot drift about what "emit this schema" means.

Verified by regenerating rather than by reasoning: all 1 395 checked-in Kotlin files, and the
Swift and TypeScript AppProtocol sets, come out byte-identical from the driver's new home.

## Three things the split is not mechanical about

### 1. V2GTP exists twice

`WWCP_ISO15118_V2GTP` already implements V2GTP as `V2GTP_Frame` / `V2GTP_Header` /
`V2GTP_PayloadType` in `cloud.charging.open.protocols.ISO15118.V2GTP`, with its own exception
hierarchy and its own tests under `tests/V2G.V2GTP.Tests/`. This repository implements the same
eight header bytes again in `WWCP_ISO15118_EXI/V2GTP/V2GTP.cs` plus
`WWCP_ISO15118_EXI_Dispatch`, in a nullable-return style with no exceptions.

The rename surfaced this rather than causing it, and it surfaced it *hard*: mapping the framing
namespace onto `cloud.charging.open.protocols.ISO15118.V2GTP` does not even compile, because the
class is called `V2GTP` and the namespace would shadow it for everything under
`cloud.charging.open.protocols.ISO15118`. It is parked at
`cloud.charging.open.protocols.ISO15118.EXI.Dispatch` for now — a name that says what the project
is, and deliberately does not squat on the namespace the two implementations will have to merge
into.

Merging them is a decision about wire behaviour (exceptions vs nullable returns on a malformed
frame), so it is not a rename. It should happen *before* the move, not after — afterwards there are
two V2GTPs in one repository.

> **Closed 2026-08-08 — and most of it was already closed before this was read again.**
>
> There are not two implementations. `V2GTP_Header.WriteTo` is the only code in either repository that
> packs the eight bytes; `V2GTP_Header.TryParseRaw` is the only code that unpacks them. The static class
> is a **span-shaped facade** over that record struct — `WriteHeader` calls `Standard(…).WriteTo(dest)`,
> `TryReadHeader` calls `TryParseRaw(…)` plus the version-complement check, `HeaderSize` *is*
> `V2GTP_Header.Size`, and every payload id is a cast of a `V2GTP_PayloadType` member. Swept and
> confirmed: the only other code writing big-endian fields into spans is SDP's response builder and
> SLAC's management-message entry, which are different protocols.
>
> **The wire-behaviour question resolved itself rather than needing a decision.** Both styles survive
> because `V2GTP_Header` offers both: `Parse` throws, `TryParse`/`TryParseRaw` return `false`. SDP wants
> the object model and the exceptions; a reader pulling frames off a socket into a rented buffer wants
> neither an allocation nor an exception per malformed frame. One implementation, two call shapes.
>
> What actually remained was a **name**: the facade was called `V2GTP` while a sibling namespace is also
> called `V2GTP`, so callers inside `…ISO15118` needed an alias and callers outside did not — one type
> with two spellings depending on where the caller sat. The class is now `V2GTPCodec` (file
> `WWCP_ISO15118_V2GTP/V2GTPCodec.cs`), and the seven aliases that existed to work around the clash are
> gone: five added during today's rename, and two older ones inside `V2GTPDispatcherTests` and
> `V2GTPFrameTests` that had already discovered the same trap — their comment even recorded the part I
> had to rediscover, that an alias declared *inside* the namespace declaration beats the enclosing
> namespace while a file-level one does not.
>
> Nothing on the wire changed; 1 212 offline tests green before and after.

### 2. Josev is both an oracle and a counterparty

The user rule is "interoperability tests stay". That is unambiguous for
`Vanaheimr.V2G.Simulation.Tests/Interop/` and the `tools/interop-*` scripts: those drive a live
peer over a socket.

It is less obvious for `WWCP_ISO15118_EXI_Tests/Interop/` (6 files) and
`JosevCuratedVectorTests.cs`. Those tests never open a socket — they decode bytes Josev *once*
produced, checked into `Vectors/Iso15118_20.DC.josev.vectors.json`. Functionally they are codec
tests whose oracle happens to be a third-party encoder, which is exactly what `CLAUDE.md` demands
("only based on a concrete byte diff against a reference encoder").

Recommendation: **the vectors move with the codec, the live harnesses stay.** A codec that arrives
in WWCP_ISO15118 without its byte-level oracle cannot be changed safely there. The six `Josev*.cs`
files under `Exi.Tests/Interop/` are vector-driven and should move with them; the naming is what
makes them look otherwise. Flagging it rather than deciding it, because the instruction was explicit.

### 3. `RationalNumber` will exist twice, and `V2GSignature` five times

`WWCP_ISO15118_20/CommonTypes/Complex/RationalNumber.cs` and the generated
`cloud.charging.open.protocols.ISO15118_20.{AC,DC,CommonMessages}.RationalNumber` are different
types with the same name. They do not collide — different namespaces, and the generated codec lives
one level down in `.Generated` — so this compiles. It is still two spellings of one concept in one
repository, and someone will pick the wrong one.

The five `V2GSignature.cs` copies (one per -20 message set) are deliberate: the csproj comments
record that CommonTypes is duplicated per message-set assembly to mirror cbexigen/cbV2G, because the
grammars are per-set and self-contained. That reasoning survives the move. Worth a note in the
target repo's README so it does not get "cleaned up".

## Suggested layout in WWCP_ISO15118

Matching the repository's existing `WWCP_ISO15118_<area>` convention:

```
WWCP_ISO15118_EXI/                      ← Exi.Prototype
WWCP_ISO15118_EXI_SourceGenerator/
WWCP_ISO15118_EXI_Dispatch/
WWCP_ISO15118_EXI_Tests/
WWCP_ISO15118_2_EXI/                    ← Exi.Iso15118_2 (schemas)
WWCP_ISO15118_20_EXI_CommonMessages/    ← and DC, AC, AC_DER_IEC, AC_DER_SAE, WPT, ACDP
WWCP_ISO15118_20_EXI_XMLDSig/
tools/cbv2g-ref/, tools/exificient-ref/
```

Directory and assembly names are **not** part of the namespace rewrite that has already happened —
they are part of the move itself, and several tests locate schema sets by walking to a directory of
that exact name (`EmitterHarness.RealSchemaSet("WWCP_ISO15118_2")`). Rename the
directories and those strings in the same commit, or the tests fail in a way that looks like a codec
regression.

## Namespace map, as applied

| was | is |
|---|---|
| `Vanaheimr.V2G.Exi` | `cloud.charging.open.protocols.ISO15118.EXI` |
| `WWCP_ISO15118_EXI_SourceGenerator[.Emit\|.Grammar\|.Xsd]` | `cloud.charging.open.protocols.ISO15118.EXI.SourceGenerator[…]` |
| `EVSimulatorApp.Codegen` | `cloud.charging.open.protocols.ISO15118.EXI.Codegen` |
| `ChargingSimulation` (demos/) | `cloud.charging.open.protocols.ISO15118.EXI.Simulation` |
| `WWCP_ISO15118_EXI_Tests[.Infrastructure\|.Interop]` | `cloud.charging.open.protocols.ISO15118.EXI.Tests[…]` |
| `Vanaheimr.V2G.AppProtocol` | `cloud.charging.open.protocols.ISO15118.AppProtocol` |
| `Vanaheimr.V2G.Tp` | `cloud.charging.open.protocols.ISO15118.EXI.Dispatch` — see §1 |
| `Vanaheimr.V2G.Iso15118_2` | `cloud.charging.open.protocols.ISO15118_2` |
| `Vanaheimr.V2G.Iso15118_20.*` | `cloud.charging.open.protocols.ISO15118_20.*` |
| `Vanaheimr.V2G.XmlDsig` | `cloud.charging.open.protocols.ISO15118_20.XMLDSig` |
| `Vanaheimr.V2G.Simulation.*` | *unchanged* — this is the conformance project |
| `Vanaheimr.V2G.Experiments.*` | *unchanged* |

`AppProtocol` sits under `ISO15118` rather than `ISO15118_2`, because SupportedAppProtocol is what
*chooses* between -2 and -20; it cannot belong to either.

---

# The second move: the state machines follow the codec

Decided 2026-08-08, and written *before* the work so the reasoning is not re-argued from memory
afterwards. It has since been executed — see [Done, 2026-08-08](#done-2026-08-08--and-two-things-the-plan-got-wrong)
at the end, which records what the plan below got wrong. The present tense here is the tense of the
decision, not a statement about today.

**`Vanaheimr.V2G.Simulation` moves into WWCP_ISO15118, minus `Ocpp/`.** The three repositories then say
what they are: WWCP_ISO15118 is the ISO 15118 implementation, EVSimulatorApp is the apps and the ports,
ISO15118ConformanceTests is the evidence.

## Why the "what stays" table above no longer holds

That table was right when it was written and its premise has since walked away. It kept the state
machines here on the grounds that they are *the rig*, and a rig's value is that it is not the
implementation under test. But the rig left: `docs/interop-runs/` and `tools/interop-*` are in the
conformance repository now, and so are the tests.

| | then | now |
|---|--:|--:|
| `Vanaheimr.V2G.Simulation.Tests` | 68 `.cs` | **5** |
| `ISO15118ConformanceTests.Simulation` | — | **83** |

So what is left in `simulation/` is not a rig. It is an ISO 15118 implementation with a five-file
remnant of its old test suite attached, sitting in a repository whose stated job is apps and ports. And
WWCP_ISO15118 already carries every other layer of the standard — SDP, SLAC, V2GTP, the codec, PKI,
XMLDSig. The one thing missing is the application-layer state machine, which is most of what the
standard is *about*.

## What moves

7 401 of 7 605 lines.

| | lines | |
|---|--:|---|
| `StateMachines/` | 4 635 | `Iso2/`, `Iso20/` — the reason for the move |
| `Transport/` | 1 374 | incl. `BouncyCastle/`; see below |
| `Metering/` | 409 | see below |
| `Discovery/` | 222 | SDP client |
| `Slac/` | 185 | ISO 15118-3 pairing |
| `Sap/` | 182 | SupportedAppProtocol handshake |
| `Session/` | 137 | session context, `ResumableSession`, `SessionAborted` |
| `Timing/` | 133 | the sequence and ongoing guards |
| `Framing/` | 124 | V2GTP stream |

`Vanaheimr.V2G.Simulation.Cli` goes with them, next to the existing `demos/`. It is the reference
driver, not an app.

### `Transport/BouncyCastle/` moves whole, and the AOT cost is smaller than it looks

The managed TLS backend exists because the `-20` profile — the Table 6/7/8 suites, secp521r1 and Ed448
— is precisely what Windows Schannel will not do. It has to be in the library, because `[V2G20-2677]`
permits nothing but full-handshake TLS for every `-20` session: a library that can conduct a `-20`
session but not open one is not an implementation.

An earlier draft of this section proposed isolating it as `WWCP_ISO15118_TLS` to protect the AOT
guarantee. **Measured rather than assumed, that carve-out buys almost nothing:**

- Eleven WWCP_ISO15118 projects declare `IsAotCompatible=true`, so the guarantee is real.
- **Five of them already reference BouncyCastle** while declaring it —
  `WWCP_ISO15118_20.CommonMessages` has both in the same csproj. BouncyCastle's *primitives* are
  trim-clean; only `Org.BouncyCastle.Tls` is not, which is what the note in
  `Vanaheimr.V2G.Simulation.csproj` actually says.
- **Dependencies flow downward.** Nothing in WWCP_ISO15118 would reference the new project — state
  machines depend on the codec, never the reverse. The eleven stay clean whatever the newcomer carries.
  The AOT loss stays confined to exactly where it already is.

The only consumer a split would serve is one wanting the state machines without TLS, and for `-20` that
consumer is forbidden to exist. `-2` over plain TCP is the thin remainder; if it ever matters, split
then. Not before.

### `Metering/` moves whole

The signed meter reading is protocol, not a device simulation: `MeterInfo`/`SigMeterReading` are message
fields, the payload layout is pinned so a `-2` reading cannot be presented as a `-20` one, and the
conformance repository already tests it as protocol material — `MeterVectorTests` and
`Secc2SignedMeterTests`. Something that has its own conformance vectors belongs in the library that
those vectors are about. The `SigningMeter` device travels with it rather than being split out; the
seam between "layout" and "device" is not worth a project boundary.

## What stays, and why exactly one thing does

**`Ocpp/` — 204 lines, one file.** Not because it is unimportant, but because it is a different
protocol, and because what lives here is a *stub*: enough OCPP to satisfy interop counterparties, not
OCPP. EVerest expects a slice of it to validate eMAIDs, and that is still open work on the interop side,
so this will grow — but it grows as test scaffolding. **In real operation a real OCPP project hangs on
this seam.** An ISO 15118 library that shipped a transaction recorder would have to explain itself to
every reader, and would be the wrong thing to extend when the real one arrives.

**The seam is not a seam yet.** Today it is:

```csharp
public Func<string, Ocpp.OcppTransactionRecorder>? Backend { get; init; }
```

— a delegate whose *return type* is the concrete class, so the library would still need it. Making the
split real means an interface over what the state machines actually call (`Sample(wattHours,
unixSeconds, signatureHex, publicKeyHex)`) with `OcppTransactionRecorder` as one implementation. That is
small and it is the only piece of this move that is not mechanical.

## `demos/ChargingSimulation` is the vestigial ancestor

WWCP_ISO15118 already contains `demos/ChargingSimulation/` — `Evcc.cs`, `Secc.cs`, `Wire.cs` and a
second `SessionAborted.cs`, 444 lines. It is the same idea, younger, and the duplication exists
*today*. The move converges them rather than creating an overlap: the demo should end up rebuilt on the
real state machines, or deleted. Do not leave two.

## How

- **`git subtree split --prefix=simulation/Vanaheimr.V2G.Simulation`**, not a copy-and-delete. The blame
  here is load-bearing — a great many comments read "a live Josev run caught this", "our loopback SECC
  did not" — and a plain copy throws away which run each of them came from.
- **Rename last**, as one mechanical commit: `Vanaheimr.V2G.Simulation.*` →
  `cloud.charging.open.protocols.ISO15118.*`, matching what the first move already did. Note the warning
  above about tests locating schema sets by directory name — the same class of trap applies to anything
  that resolves paths by project name.
- **Not while something is mid-fix.** This was held until the `-20` resume defects were merged (app #12),
  because a move and a behavioural change in one diff are unreviewable.

### Done, 2026-08-08 — and two things the plan got wrong

Both halves are executed: the move (`WWCP_ISO15118` #7, app #14, conformance #19) and the rename
immediately after. 130 source files, three `.csproj` renamed to match their directories, 1 212 offline
tests green throughout.

**The rename was not mechanical, for the reason §1 above predicted — and the obvious fix does not work.**
Once the session code sits under `cloud.charging.open.protocols.ISO15118`, a bare `V2GTP` binds to the
sibling *namespace* `…ISO15118.V2GTP` rather than to the header-codec *class* in `…ISO15118.EXI.Dispatch`.
Adding `using V2GTP = …EXI.Dispatch.V2GTP;` **does not help**: resolving a single identifier checks the
members of each enclosing namespace *before* the compilation unit's using-aliases, so the namespace still
wins. An alias under a name that is not also a namespace member does work, and five files carried
`using V2GTPCodec = …` for a day.

*Closed the same week, and §1's premise turned out to be false.* There were never two V2GTP
implementations to merge: `V2GTP_Header.WriteTo` is the only code that packs the eight bytes, and the
static class was already a span-shaped facade over it. What remained was one type with two spellings
depending on where the caller sat. Renaming the class itself to **`V2GTPCodec`** removed the collision
at its source, and the five aliases with it — so the aliases described above no longer exist, and
neither does the name clash. §1 below is kept as written because the resolution rule it uncovered is
worth having; its "two implementations" framing was wrong.

**The sweep has to include shell scripts.** Repointing `*.cs`, `*.csproj` and `*.slnx` left **27 live
interop harnesses** under `tools/` pointing at a CLI path that no longer exists — nothing a build or a
test run would ever have caught, because they are only executed against a live counterparty. They are
fixed. `docs/interop-runs/` deliberately is **not**: those scripts and logs are the record of what was
run at the time, and rewriting them to match today's layout would falsify it.

Unrelated rot found while sweeping: 17 scripts under `tools/interop-josev/` hardcoded
`REPO=/mnt/c/.../Vanaheimr.V2G.Exi`, a repository that has not existed under that path since the
submodule inversion — already broken before this move. **Fixed the same day** (conformance #22): each
now resolves the repository root two levels up from its own `BASH_SOURCE`, which is true wherever the
tree is checked out and on whichever side of the WSL boundary it runs.
