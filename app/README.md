# `app/` — the WebView application

Scan, confirmation sheet, manual fallback, session inspector (`docs/CONCEPT.md` B1). This is what
Capacitor's `webDir` points at, and what a browser can open on its own.

## Run

```bash
cd app && npm test
```

```bash
python3 -m http.server 4173 --directory app
```

```bash
cd app && npm install && npm run build
```

**No install step and no build step are needed to run it.** Opened in a plain browser a fresh clone
still scans, parses, warns and refuses — which is most of what it does — and says plainly that it
cannot open a session.

`npm run build` is what adds one. esbuild compiles `src/vendor/entry.ts` into
`vendor/ev-simulator.js` — the plugin, the EXI codec and three recorded ISO 15118-2 sessions — and a
plain browser then gets a **real event stream**: Capacitor routes the three commands to the web
implementation, which replays a recording. The bundle is a build output and is not checked in; its
absence is a state the application handles.

## Why plain `.js` and not `.ts`

Node strips types; a browser does not. A WebView loads `.js`, so TypeScript here would mean a build
step, and a build step is the thing this repository has kept out of every back end so far. So the
sources are ES modules with JSDoc types and `// @ts-check` — checked by `tsc --noEmit` wherever a
toolchain happens to exist, and run unaltered by both Node and the WebView.

(`typescript/` is a different artifact — the EXI codec and the JSON-LD pass — and it *does* need a
stripping step before a WebView can load it. Nothing here imports it: an event already arrives as
JSON-LD, so the inspector has no decoding to do.)

**One file here is TypeScript, and it is the seam.** `src/vendor/entry.ts` is the only source that
imports the plugin, and through it the codec. esbuild crosses that boundary once, and every other
source in `app/` stays plain ES modules that Node runs directly for the tests and a browser runs
directly without a build.

**There is one contract, deliberately.** `src/ui/plugin.js` returns the *adapted* plugin from the
bundle and nothing else — not `globalThis.Capacitor.Plugins.EvSimulator`, which is the raw one and a
different shape: it delivers `{ event: string }` and takes `start({ config })` where the adapted one
delivers a parsed `BridgeEvent` and takes `start(config)`. Supporting both would have meant a second
copy of `adapt` here, in another language, for the two to drift apart in. An earlier version did
exactly that, read `payload.event` off an already-parsed event, and dropped every event of every
session — silently, because unreadable events are dropped by design. The screen simply stayed
empty.

## Where the decisions are

| | |
|---|---|
| `src/pairing.js` | The scanned code: parser and warnings. A **fourth port**, held to `Vectors/Pairing.payload.vectors.json` — 22 cases, refusal texts included. |
| `src/sheet.js` | What the confirmation sheet shows and decides, as data. |
| `src/session.js` | What a stream of bridge events looks like, as data. |
| `src/ui/` | The part that touches the document. Decides nothing. |

The split is the same one the Capacitor adapter uses: everything a laptop can check lives above the
layer it cannot, so `npm test` covers whether the Connect button is live, what is shown first, and
what would be handed to the plugin — and leaves only the pixels untested.

**The app parses the code itself** rather than asking the native side, because `EvSimulatorPlugin`
has exactly three commands by an explicit decision. A fourth command for "parse this" would be the
easiest thing in the world to add and the hardest to take back.

## Everything on these screens is untrusted

A pairing code is an image anyone can tape over a display; an event comes from a peer on a network.
So the rule is not "escape carefully", it is **the dangerous API is not used at all**: `ui/dom.js`
builds elements and sets `textContent`, and `dom.test.js` reads every source file and fails if
`innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval` or `new Function` appears in
any of them. A rule enforced by absence cannot be satisfied by being careful.

`index.html` locks the same door from the other side: `default-src 'none'`, `script-src 'self'` with
no `unsafe-inline`, and `connect-src 'none'` — a code names a station, and the one thing it must not
be able to do is make the phone fetch something. Both locks are asserted by tests, because a policy
can be dropped by a copy-and-paste and a screen can be wrong under a perfect policy.

## What the sheet is for

The blocking warnings come first, above the facts, so that somebody scrolling to the button has to
pass what is wrong on the way. A blocking one is the only element on any screen with a heavy left
edge — weight and position rather than colour, because colour alone is not a message and a good
number of people cannot see this one.

Two of the nine warning kinds stop a session outright (`unsupportedVersion`, `publicTarget`); the
rest are shown and allowed. Which is which is not decided here — it is a corpus value four back ends
agree on.

## The camera

`BarcodeDetector` is used where the platform has it: Android's WebView and Chrome. **WKWebView does
not**, so on iOS the start screen says so and the code has to be typed or pasted. The alternative is
a native scanner plugin, which is a package, a permission and a decision somebody should make
deliberately; it is not made here.

The typed path is the same path — `sheetFor` does not know or care where the string came from — which
is why the whole of this application's judgement is testable without a camera.

## Still to do

A *live* session in the browser, which is not a bundling problem: it needs the EVCC state machines in
TypeScript (three languages have them, not four) and a transport a browser can open
(`tools/EVSimulatorApp.WsBridge` is that half). Until then the browser replays, and the phone runs.
