# Counterparties — whose EV and EVSE we can test against

Everything in this repository is held to corpora **we** generate. That is the right shape for a
port — four back ends cannot drift if they are compared as text — and it has one blind spot it can
never see past: *both sides agreeing on the same mistake.* `CONCEPT.md` §1.3 puts a number on it —
the ~15 real conformance fixes were found by live interop and by nothing else, and they live in the
state machines rather than in the codec.

So the counterparty list is not a nice-to-have. It is the only oracle outside our own head.

**What we already use:** [cbV2G](https://github.com/EVerest/libcbv2g) for byte-exact EXI vectors,
EXIficient as a cross-check, and [Josev](https://github.com/EcoG-io/iso15118) for live sessions in
both directions including Plug & Charge. The three below extend that from "one live peer" to four.

*Verified against the repositories on 2026-08-01. Anything marked “confirm on first contact” was not
checkable from a README.*

| | plays | parts | EXI lineage | language | licence |
|---|---|---|---|---|---|
| [tux-evse/iso15118-simulator-rs](https://github.com/tux-evse/iso15118-simulator-rs) | **EV and EVSE** | -2 (−20/DIN announced) | cbexigen — *ours* | Rust | Apache-2.0 |
| [EDF-Lab/eVDriveFlow](https://github.com/EDF-Lab/eVDriveFlow) | **EV and EVSE** | **-20 Ed. 1**, DC BPT | **OpenEXI — independent** | Python | MIT |
| [EVerest](https://github.com/EVerest/everest-core) | **EV and EVSE** | DIN 70121, -2, -20 | cbV2G — *ours* (car side: Josev) | C++ / C / Python | Apache-2.0 |

All four now have a harness, and none has been run against yet — Josev remains the only counterparty
with recorded sessions (`libs/Vanaheimr.V2G.Exi/docs/interop-runs/`). The harnesses share one
vocabulary of environment variables and one recorder; what differs is the bring-up and what each run
can prove.

---

## tux-evse/iso15118-simulator-rs

**Harness: [`libs/Vanaheimr.V2G.Exi/tools/interop-tux-evse/`](../libs/Vanaheimr.V2G.Exi/tools/interop-tux-evse/README.md)**
— built 2026-08-01, not yet run against them.

A scenario runner with a **web UI** (`devtools`, injector on `:1234`, responder on `:1235`), exposing
ISO messages as JSON RPCs over **WebSocket** through the AGL Application Framework Binder. It can act
as the EV (injector) or as the charger (responder), and both at once. EXI is delegated to a sibling
crate, [`iso15118-encoders-rs`](https://github.com/tux-evse/iso15118-encoders-rs).

**Correction to this entry's original premise: it is not an independent EXI oracle.** Their encoders
crate says it "relies on cbexigen iso15118-encoder library for low level EXI binary encoding" —
cbexigen is the generator behind libcbv2g, which is where our own byte-exact vector corpus comes
from. So the same caveat this file already records for EVerest's `EvseV2G` applies here: a
disagreement is **not** an EXI disagreement by construction, it is a state-machine, framing or timing
one. The counterparties whose bytes *are* independent of ours are Josev (EXIficient) and eVDriveFlow
(OpenEXI).

**Why it is still the most interesting of the three.** Scenarios are JSON, and its `pcap-iso15118`
tool generates them **from packet captures**. That is the same idea as our
`Vectors/Session.*.trace.json` — a recorded session replayed frame by frame — arrived at
independently. Two recorders that agree on what a session was are worth much more than one, and a
capture of *our* EVCC against *their* EVSE can be replayed by both sides afterwards.

And because their side is a **replayer rather than a state machine**, a reverse run puts a *real
car's* messages in front of our station — the shipped scenario is an Audi against an ABB charger —
rather than our own idea of a car's. That is the half a self-consistent implementation is worst at.

Also: it already answers a WebSocket, so `tools/EVSimulatorApp.WsBridge` is not needed to put a
browser in front of it — its transport and ours are different answers to the same question, and
comparing them is cheap.

**How to read a reverse run's verdict.** Their injector compares each response against an `expect`
block lifted from the capture, and that block holds *the captured charger's* values —
`"id": "DE*PNX*E12345*1"` is an EVSE ID, not a protocol constant. Against our station those
comparisons fail on a session that is otherwise perfect, so their pass/fail is not ours;
`scenario-expectations.py` in the harness lists which expectations are station-specific before the
run. Their `strong` compaction mode is the one to use against a foreign station.

**The verdict that *is* ours is the flow.** A scenario file is a declared sequence of ISO 15118
messages taken from a real capture, so the harness reads it as one and compares it against what
crossed the wire (`V2G_INTEROP_SCENARIO=<file>` → `flow.md`). Order, phases, response codes — the
layer a corpus of single messages cannot see, and the layer §1.3's conformance fixes live in.
Consecutive repeats are collapsed on both sides first: a session polls where a compacted scenario
names the message once, and an uncollapsed diff would bury the real difference under the poll loop.
Their verb vocabulary is a hand-kept table rather than a snake_case conversion — `payment_selection`
is `PaymentServiceSelection`, `param_discovery` is `ChargeParameterDiscovery` — because a converter
would have invented three messages that do not exist and then reported them missing for ever.

**What it cannot tell us yet:** -20 and DIN are announced rather than shipped, so it exercises the
-2 half of our stack only.

## EDF-Lab/eVDriveFlow

**Harness: [`libs/Vanaheimr.V2G.Exi/tools/interop-evdriveflow/`](../libs/Vanaheimr.V2G.Exi/tools/interop-evdriveflow/README.md)**
— built 2026-08-01, not yet run against them.

ISO 15118-**20** Edition 1, DC bidirectional power transfer, **dynamic** control mode, **TLS 1.3 with
mutual authentication** (disableable for testing, via `SECURITY_PROTOCOL` in `shared/global_values.py`).
Both sides, each with a GUI: the SECC one sets departure time and SoC targets, the EVCC one adjusts
power during the session. Python + conda, and a **JDK** — because their EXI is **OpenEXI**.

**That JDK is a fact worth its own line: OpenEXI is a third independent EXI lineage**, after our
cbV2G/cbexigen corpus and Josev's EXIficient. So unlike tux-evse and EVerest, a byte disagreement here
is a real finding — this counterparty is an oracle at both layers, the codec and the flow.

**Why it matters to us specifically.** This is the only counterparty here that goes straight at the
combination we have the least outside evidence for: -20 + BPT + dynamic control + mutual TLS 1.3.
`docs/pki-model.md` pins -20 to TLS 1.3 with a mutual handshake, and our own tests are the only thing
that has ever checked that we do it right. A second implementation that *requires* it is a real
oracle.

Its dynamic control mode also drives the schedule-renegotiation paths, which our recorded sessions
touch only where we chose to record them.

**Answered since:** headless runs are supported on both sides — `secc/start_evse.py` and
`evcc/start_ev.py`, alongside the two GUIs — so repeatable runs need no window.

**Confirm on first contact:** whether it does Plug & Charge / contract certificates at all (the
documentation does not say); which suite and curve its TLS 1.3 actually negotiates; and what
`virtual_mode = true` in both `.ini` files does to a session against a foreign peer — their
documentation describes it as simulating the communication card, which makes it the first setting to
question when nothing connects.

## EVerest

**Harness: [`libs/Vanaheimr.V2G.Exi/tools/interop-everest/`](../libs/Vanaheimr.V2G.Exi/tools/interop-everest/README.md)**
— built 2026-08-01, not yet run against them.

The Linux Foundation Energy stack, and the widest surface of the four:

- `modules/EVSE/EvseV2G` — the charger side for **DIN 70121 and ISO 15118-2** (C, cbV2G underneath).
- `modules/EVSE/Evse15118D20` — the **ISO 15118-20 charger**. This answers the open question below:
  [`libiso15118`](https://github.com/EVerest/libiso15118) was archived on 2026-02-26 and folded in
  here; the SIL configurations that use it are `config/config-sil-dc-d20.yaml` and
  `config-sil-ac-d20.yaml`.
- `modules/EVSE/IsoMux` — multiplexes -2 and -20 behind one endpoint, which is the closest thing to a
  real charger's behaviour.
- `modules/EV/PyEvJosev` — the **car** side, the Josev-derived Python stack. This is the same
  implementation family our existing interop runs used, now packaged as an EVerest module.

**Why it matters.** It is the implementation most likely to be on the other end of a real charger, so
"works against EVerest" is closer to a market claim than to a test result. And because `EvseV2G` sits
on cbV2G — the same encoder our vector corpus is generated from — a disagreement there is *not* an
EXI disagreement by construction: it is a state-machine or a timing one, which is exactly the class
our corpora cannot see.

**Only one half is new, and it is worth being precise.** Their *charger* is new; their *car* is Josev
in a different wrapper. So the forward direction is where the findings are, and a green reverse run
is much less news than it looks — check `docs/interop-runs/2026-07-2*` before calling anything from it
new. The flow report compares both directions against one of our recorded sessions, and for this
counterparty it is the **station → EV** half that carries the news.

**Answered since:** `PyEvJosev`'s `device` is documented as "any local interface that has an ipv6
link-local and a MAC addr", and it finds a station by SDP on it — so it is not bound to EVerest's own
`EvseV2G`, and a run against our SECC is possible in principle.

**Confirm on first contact:** whether a configuration containing only the EV-side modules can be
assembled and started on its own. If not, the fallback is to run a full SIL config with `EvseV2G`'s
`device` pointed at an interface our station is not on. Also note that every `supported_*` key of
`PyEvJosev` defaults to **false** — a car that announces no protocol negotiates nothing, and the
symptom is an empty handshake rather than an error.

**Worth coming back for:** `config/config-sil-mcs.yaml`. Our roadmap records MCS (service ids 8/9) as
implemented but *"untested against a live counterpart"* — this is the first live counterpart in sight
for it.

---

## What to do with a run

Every session against a counterparty should come back as a **trace**, not as a memory. Since
2026-08-01 the interop fixtures do this themselves: set `V2G_INTEROP_RECORD=<dir>` and every run
leaves the raw octets of both directions, a frame log, and — when the session was well-formed enough
to be one — a `SessionTrace` in the format all four back ends replay
(`Vanaheimr.V2G.Simulation.Tests/Interop/InteropRecording.cs`). So an interop run is not a one-off:
it becomes a corpus entry that all four back ends are held to from then on, and the conformance fix
it produced cannot silently regress.

The bytes are written *before* the trace is attempted, and the trace's refusal is written down rather
than thrown. The run that fails is the interesting one, and it is exactly the run whose recording a
strict corpus builder will not accept — a recorder that kept only what it could parse would discard
the evidence of every disagreement it was built to find.

Both sides are worth recording. Our EVCC against their EVSE tests what we *send*; their EVCC against
our SECC tests what we *accept*, which is the half a self-consistent implementation is worst at.

`tools/EVSimulatorApp.WsBridge` also lets the WebView inspector watch any of them: the bridge does not
know or care whose station is on the far end of the TCP socket.
