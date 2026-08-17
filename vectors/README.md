# The session corpus

Recorded sessions, certificate material and signed meter readings. Every port in this repository —
Swift, Kotlin, TypeScript, the WebView app and the Capacitor plugin — is held to these files, and
so is the C# side in the conformance repository above.

**Generated. Do not hand-edit.** They are written by `ISO15118ConformanceTests.Simulation`, whose
regenerators are `[Explicit]` precisely because these bytes are an oracle for four other languages:
they must change when somebody means them to and never as a side effect of a test run.

| File(s) | Written by |
|---|---|
| `Session.*.trace.json`, `Session.ocpp-transactions.json` | `SessionTraceCorpusTests.RegenerateTheCorpus` |
| `Session.pnc-material.json` | `SessionTraceCorpusTests.RegenerateThePncMaterial` |
| `Certificate.chain.vectors.json` | `CertificateChainCorpusTests` |
| `Meter.signing.vectors.json` | `MeterVectorTests` |

## Why they live here and not with the generator

They used to live with it, in `ISO15118ConformanceTests.Simulation/Vectors/`, and the ports read
them across the repository boundary as `../../ISO15118ConformanceTests.Simulation/Vectors/…`. That
is a submodule reading its own superproject: the conformance repository carries this one as
`libs/EVSimulatorApp`, so the dependency pointed back up at its parent. A checkout of this
repository on its own could not pass its own test suite — fifty-odd failures, all of them
`session trace not found at …` — and CI had to check out the parent first and then this commit
underneath it, purely to put a directory of JSON above the tree under test.

Git submodules are one-way: a parent pins a child, and a child knows nothing of its parent. So the
corpus moved down here, where the things held to it live, and the generator writes into
`libs/EVSimulatorApp/vectors/`. Regenerating now produces a commit here and a submodule bump above —
which is the normal shape of generated data crossing that boundary, and readable in the history.

## Adding a recording is not one edit

The corpus is small enough to look simple and coupled enough not to be. Recording a scenario means:

1. Add it to the `Scenarios` table in `SessionTraceCorpusTests`, then run `RegenerateTheCorpus`.
2. **Restore what churned but did not change.** ECDSA picks a fresh nonce per run, so every signed
   trace differs in its signature bytes and nothing else. Restore those — but restore *all* of a
   session's files or none: `Session.ocpp-transactions.json` was once regenerated while the traces
   beside it were restored, and the two corpora then disagreed about the same session, with the
   station's signed reading no longer the one the car saw.
3. **Regenerate `bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json`.** It is derived
   from this directory and its test walks *every* trace here, so a new recording turns it red at
   once.
4. **Name the scenario in each port that has a state machine** — Swift and Kotlin both, and they
   are separate lists. A recording no test names is invisible; that has happened here twice.
