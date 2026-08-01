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

| | plays | parts | language | licence |
|---|---|---|---|---|
| [tux-evse/iso15118-simulator-rs](https://github.com/tux-evse/iso15118-simulator-rs) | **EV and EVSE** | -2 (−20/DIN announced) | Rust | Apache-2.0 |
| [EDF-Lab/eVDriveFlow](https://github.com/EDF-Lab/eVDriveFlow) | **EV and EVSE** | **-20 Ed. 1**, DC BPT | Python | MIT |
| [EVerest](https://github.com/EVerest/everest-core) | **EV and EVSE** | DIN 70121, -2, -20 | C++ / C / Python | Apache-2.0 |

---

## tux-evse/iso15118-simulator-rs

A scenario runner with a **web UI** (`devtools`, on `:1234`), exposing ISO messages as JSON RPCs over
**WebSocket** through the AGL Application Framework Binder. It can act as the EV (injector) or as the
charger (responder), and both at once. EXI is delegated to a sibling crate,
[`iso15118-encoders-rs`](https://github.com/tux-evse/iso15118-encoders-rs).

**Why this one is the most interesting of the three.** Scenarios are JSON, and its `pcap-iso15118`
tool generates them **from packet captures**. That is the same idea as our
`Vectors/Session.*.trace.json` — a recorded session replayed frame by frame — arrived at
independently. Two recorders that agree on what a session was are worth much more than one, and a
capture of *our* EVCC against *their* EVSE can be replayed by both sides afterwards.

Also: it already answers a WebSocket, so `tools/EVSimulatorApp.WsBridge` is not needed to put a
browser in front of it — its transport and ours are different answers to the same question, and
comparing them is cheap.

**What it cannot tell us yet:** -20 and DIN are announced rather than shipped, so it exercises the
-2 half of our stack only.

## EDF-Lab/eVDriveFlow

ISO 15118-**20** Edition 1, DC bidirectional power transfer, **dynamic** control mode, **TLS 1.3 with
mutual authentication** (disableable for testing). Both sides, each with a GUI: the SECC one sets
departure time and SoC targets, the EVCC one adjusts power during the session.

**Why it matters to us specifically.** This is the only counterparty here that goes straight at the
combination we have the least outside evidence for: -20 + BPT + dynamic control + mutual TLS 1.3.
`docs/pki-model.md` pins -20 to TLS 1.3 with a mutual handshake, and our own tests are the only thing
that has ever checked that we do it right. A second implementation that *requires* it is a real
oracle.

Its dynamic control mode also drives the schedule-renegotiation paths, which our recorded sessions
touch only where we chose to record them.

**Confirm on first contact:** whether it does Plug & Charge / contract certificates at all — the
README does not say — and whether the EVCC side can be driven headlessly (`start_ev.py` exists, so
probably yes) for repeatable runs.

## EVerest

The Linux Foundation Energy stack, and the widest surface of the three:

- `modules/EVSE/EvseV2G` — the charger side for **DIN 70121 and ISO 15118-2** (C, cbV2G underneath).
- `modules/EV/PyEvJosev` — the **car** side, the Josev-derived Python stack. This is the same
  implementation family our existing interop runs used, now packaged as an EVerest module.
- ISO 15118-**20** came from [`libiso15118`](https://github.com/EVerest/libiso15118) (C++), which was
  **archived on 2026-02-26** and folded into `everest-core`. Check where the -20 SECC lives now
  before planning a run against it.

**Why it matters.** It is the implementation most likely to be on the other end of a real charger, so
"works against EVerest" is closer to a market claim than to a test result. And because `EvseV2G` sits
on cbV2G — the same encoder our vector corpus is generated from — a disagreement there is *not* an
EXI disagreement by construction: it is a state-machine or a timing one, which is exactly the class
our corpora cannot see.

**Confirm on first contact:** whether EVerest's simulation configuration can run `PyEvJosev` against
an external SECC (ours) rather than only against its own `EvseV2G`.

---

## What to do with a run

Every session against a counterparty should come back as a **trace**, not as a memory. The recorder
already exists (`Vanaheimr.V2G.Simulation.Tests/Traces/SessionTrace.cs`), the format is the one four
back ends replay, and `bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json` is generated
from it. So an interop run is not a one-off: it becomes a corpus entry that all four back ends are
held to from then on, and the conformance fix it produced cannot silently regress.

Both sides are worth recording. Our EVCC against their EVSE tests what we *send*; their EVCC against
our SECC tests what we *accept*, which is the half a self-consistent implementation is worst at.

`tools/EVSimulatorApp.WsBridge` also lets the WebView inspector watch any of them: the bridge does not
know or care whose station is on the far end of the TCP socket.
