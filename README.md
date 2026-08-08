# ISO/IEC 15118 EV Simulator App

An app that **simulates an electric vehicle** speaking ISO 15118-2 and -20 — the SupportedAppProtocol
handshake, the -2/-20 charging sessions, Plug & Charge, signed metering, the -20 TLS/PKI — against a
real SECC counterpart (a Raspberry Pi charging station). The physical layer a real car uses to get
onto the wire — plug-in, SLAC, PLC — is replaced by a **QR scan over WLAN**: one code stands in for
plug-in + SLAC + SDP and carries the endpoint, transport and crypto profile the session needs.

It is a teaching, debugging and security-research tool: it shows the exact EXI bytes on the wire, the
canonical fragment a signature is computed over, the certificate chain being validated — the parts of
ISO 15118 nobody can normally see. See [`docs/CONCEPT.md`](docs/CONCEPT.md) for the full feasibility
study and design, and [`docs/roadmap.md`](docs/roadmap.md) for status.

> **Not the ISO 15118 stack.** The stack itself — the EXI codec, SLAC, SDP, V2GTP, the V2G PKI, *and*
> since 2026-08-08 the EVCC/SECC session state machines and the CLI that runs them — lives in the
> `WWCP_ISO15118` submodule and is documented there
> ([`libs/WWCP_ISO15118/README.md`](libs/WWCP_ISO15118/README.md)). This repository is everything built
> *on top of* it to make an EV simulator: the app, the shells, the pairing, and the language ports.

## Getting started

```bash
git submodule update --init --recursive        # WWCP_ISO15118, Hermod, Styx, DynamicQRCodes
bash libs/WWCP_ISO15118/tools/download-schemas.sh   # the ISO schemas are ISO's, not shipped here
dotnet build EVSimulatorApp.slnx                # the C# side: bridge, pairing, Pi, codegen
```

The ISO schemas are gitignored — running `download-schemas.sh` fetches them and is you accepting ISO's
licence; the source generators need them present under `libs/WWCP_ISO15118/**/Schemas/`.

The WebView app is its own npm package:

```bash
cd app && npm install && npm test && npm run build
```

## What is in here

**The EV simulator**

| Path | What it is |
|---|---|
| [`app/`](app/) | The WebView UI — scan, confirmation sheet, session inspector (its own README + npm package) |
| `shell/`, `capacitor/` | The native iOS/Android Capacitor shells the WebView runs inside |
| `pairing/` | QR pairing (the "virtual plug") and `EVSimulatorApp.Pi`, the Raspberry-Pi SECC counterpart |
| `bridge/`, `tools/EVSimulatorApp.WsBridge` | The bridge that carries frames and events between the WebView and the session/Pi |

**The ISO 15118 stack it drives**

| Path | What it is |
|---|---|
| `libs/WWCP_ISO15118` | **Submodule.** The whole ISO 15118 stack: the EXI codec, SLAC, SDP, V2GTP, the V2G PKI builder (incl. its "Evil" cert factory), the -2/-20 EVCC and SECC state machines with their SLAC/SDP/TLS/SAP front stages, and the CLI that drives either role |
| `libs/Hermod`, `libs/Styx` | **Submodules.** Supporting libraries; `WWCP_ISO15118_SLAC` reaches for them as siblings under `libs/` |
| `libs/DynamicQRCodes` | **Submodule.** The AFIR / OCPP v2.1 dynamic-QR (TOTP) mechanism the pairing code is built on |
| `simulation/EVSimulatorApp.Ocpp` | A stub of a *different* protocol, reached through the stack's `ISessionBackend` seam — where a station reports what it delivered. In real operation a real OCPP project hangs here |
| `experiments/` | Post-quantum-crypto experiment (ML-KEM / ML-DSA) — wire-non-conformant, flagged as such |

**The native codec ports** — so the codec runs on the device with no .NET runtime

| Path | What it is |
|---|---|
| `kotlin/`, `swift/`, `typescript/` | The codec, XMLDSig, V2GTP and dispatch, generated into each language |
| `tools/EVSimulatorApp.Codegen` | The generator that emits them by retargeting the C# source generator — `bash {kotlin,swift,typescript}/regenerate.sh` |

Each port is held byte-for-byte to the same vector corpus the C# codec is, and to the checked-in JSON-LD
documents; regenerating without an emitter change must leave every file identical.

## Conformance & interoperability tests

They are **not** here — they wrap this repository. The **ISO15118ConformanceTests** repository
carries EVSimulatorApp as a submodule and holds the interop suite that
runs this stack against independent stacks (Josev, EVerest, EVDriveFlow, TuxEVSE). That is the harness
that proves the simulator behaves the way the standard and the field expect.

## License

The C# throughout — this repository's own projects and the `WWCP_ISO15118` codec submodule
([`libs/WWCP_ISO15118/LICENSE`](libs/WWCP_ISO15118/LICENSE)) — is AGPL-3.0, as are the generated
Kotlin/Swift/TypeScript ports, whose header the code generator emits. Check the header of the
file you are looking at.
