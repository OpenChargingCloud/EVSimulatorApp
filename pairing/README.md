# Pairing — the QR payload and the Tier-1 proximity check

The bootstrap half of Track B0 (`docs/CONCEPT.md` §4.5, §4.6). This is the format the **Pi and the
app must agree on exactly**, and they are the two halves that never run in the same process — so
it lives here, in the parent repository, rather than in `Vanaheimr.V2G.Exi`. That library is a
general ISO 15118 stack; pairing is this app's own concept, and the TOTP implementation it reuses
(`libs/DynamicQRCodes`) is a parent submodule.

```bash
dotnet test          # from the repository root
```

## The payload

An HTTPS universal link with the data in the **fragment**:

```
https://open.charging.cloud/evsim/pair#v=1&host=fe80::1%25wlan0&port=15118&tp=tls&crypto=secp521r1&root=<sha256>&totp=<12 chars>
```

`v2gsim://pair#…` is accepted as an alias for in-app scanning; the HTTPS form is what goes on a
display, because a code scanned with the stock camera app then opens a harmless landing page.

**The fragment is the security property, not a style choice.** Fragments are never sent to a
server, so the endpoint, crypto profile and fingerprints stay on the device. Parameters in the
*query* are deliberately not read — `ParametersInTheQueryAreNotRead` pins that, because a format
that works either way would hand every scan to whoever runs the host.

| Field | Meaning |
|---|---|
| `v` | payload version; **required**. Only `1` is understood, and an unknown version *blocks* |
| `host`, `port` | SECC endpoint; **required**. IPv6 (with zone), IPv4, or an mDNS name |
| `tp` | `tls` (default) or `tcp`. Absent means TLS — the default has to be the safe one |
| `proto` | offered protocols, e.g. `iso2,iso20`. A hint; SAP still runs for real |
| `crypto` | curve for the -20 profile, e.g. `secp521r1`. **Absent is not conformant** — see below |
| `nc`, `ncwhy` | non-conformance flag and the peer's own reason, displayed verbatim |
| `root` | SHA-256 fingerprint of the V2G Root CA — trust bootstrap with no pre-seeded store |
| `meter` | meter public key or id, enabling Chargy verification from first contact |
| `totp` | the rotating proximity proof (§4.6) |
| `wifi` | `ssid:psk` for the Pi's own AP, `\:` escaping a colon |
| `evseId`, `tariffId`, `currency`, `uiLanguage` | reused verbatim from the OCPP v2.1 / AFIR vocabulary |

Anything else is **carried and never interpreted**. A newer Pi must be able to talk to an older
app, and an app that silently discards what it cannot read also cannot warn that it was there.
Nothing reads `Extra`, which is exactly what makes carrying it safe.

## The code is untrusted input

The whole feature's job is to configure security parameters from a photograph of a display, and
malicious stickers at public chargers are an established attack. So `PairingPayload` **classifies
rather than decides**: `Warnings` reports what is wrong, and connecting is a human's tap on a
confirmation sheet.

Choices worth knowing about, each with a test:

- **Silence is not conformance.** A code that omits `crypto` warns exactly as one that weakens it.
  Saying nothing is the easiest thing for a hostile code to do.
- **A repeated parameter is refused**, not resolved. "First wins" and "last wins" are both
  defensible, and an attacker only needs the sheet and the connector to disagree about which.
- **Hostnames are judged, never resolved.** Resolving would mean a DNS query on behalf of a code
  the user has not approved — a callback to the attacker before anyone agreed to anything. So
  `localhost` counts as public: the decision is made on the text.
- **Non-private targets block.** The intended counterpart is a Pi on your own network, and real
  chargers are out of scope (§8 #5).
- **The peer's non-conformance reason is carried word for word** and must be rendered as untrusted
  text. That string is the mechanism by which a relaxed session is relaxed *because the counterpart
  asked for it, on the record*.

## The Tier-1 check

`PairingTotpVerifier` is the SECC side: it displays the current code and gates the V2G TCP accept
on the one the EV presents.

It runs **before the session**, which is structural rather than expedient — SLAC is not part of the
V2G message set either. It runs first, outside the session, and gates everything after it. A TOTP
check on the accept sits in exactly that slot, works identically for -2 and -20, and needs no
schema deviation; that last point matters because -2 has **no** in-band room for one (`evccIDType`
is 6 bytes of hexBinary).

Two rules carry the security:

- **±1 slot of skew is tolerated**, because the phone's clock is not trustworthy. The EV sends what
  it *read*, never what it thinks the time is.
- **Each code is accepted once.** Without that, the ±1 tolerance is a three-slot replay window —
  anyone who observes a code can present it again while it is still current. The one-shot cache is
  the difference between "was seen recently" and "is here now".

A replay is reported distinctly from a wrong guess. Both refuse, but only one is evidence that
somebody observed a real code.

The shared secret is provisioned **out of band** — a one-time static setup code, or the test-PKI
bootstrap. It is never in the rotating code; only the derived TOTP is. That is the one genuinely
unsolved part of the dynamic-QR story (§8 #14).

## The display page

`PairingPage.Render(payload, totp, remaining)` is the Pi's screen: the QR, what the code declares,
and a countdown to the next slot. **Pure rendering** — no HTTP server, no timer, no sockets. That
is deliberate rather than partial: the risk this page carries is that *what it shows drifts from
what the station is actually doing*, and that risk is a function of its inputs, not of how it is
served. A display advertising TLS beside a station listening in plaintext is worse than no display,
because it is believed.

The QR is drawn in the browser by **QRCodeSVG**, already vendored under
`libs/DynamicQRCodes/TOTP/JavaScript-Web/QRCodeSVG/` — served locally, never from a CDN. A display
whose code is fetched from the internet is a display someone else can change.

Three things the tests are really for:

- **The shared secret never reaches the page.** Only the derived code does. A screen that leaked the
  secret would turn the proximity proof into a permanent credential for anyone who ever saw it,
  which would silently undo the whole §4.6 mechanism.
- **Configuration text is escaped, and the URI is a JSON literal.** The non-conformance reason is
  written by whoever set the station up; rendering it as markup would be an injection hole on the
  one screen an operator is meant to trust.
- **The reload lands just *after* the slot ends.** Early would show the next code before the station
  honours it; late would show one it has stopped accepting. Both edges fail, in opposite directions.

The station also shows **its own warnings about itself**. The operator standing in front of the Pi
is the person who can fix a weakened profile, so it should not only be the phone that says so.

Serving it is a few lines of `HttpListener` somewhere else, and nothing depends on them.

## Not done here

`docs/CONCEPT.md` §5 B0 also wants the Pi itself: hosting the SECC over WLAN, interface binding,
the display page, AP mode, and a signing meter. Those need hardware to verify and are tracked
there, not here. What this project owns is the format and the check — the two pieces that are pure
logic, that both sides depend on, and that would otherwise be settled by whichever end was written
first.
