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

No install step and no build step. Opened in a plain browser the application still scans, parses,
warns and refuses — which is most of what it does — and says plainly that it cannot open a session,
because the plugin only exists inside the shell.

## Why plain `.js` and not `.ts`

Node strips types; a browser does not. A WebView loads `.js`, so TypeScript here would mean a build
step, and a build step is the thing this repository has kept out of every back end so far. So the
sources are ES modules with JSDoc types and `// @ts-check` — checked by `tsc --noEmit` wherever a
toolchain happens to exist, and run unaltered by both Node and the WebView.

(`typescript/` is a different artifact — the EXI codec and the JSON-LD pass — and it *does* need a
stripping step before a WebView can load it. Nothing here imports it: an event already arrives as
JSON-LD, so the inspector has no decoding to do.)

**That is also why this application cannot use the plugin's web implementation.**
`capacitor/src/web.ts` replays a recorded session in a browser, and it is real — the same traces the
four back ends are held to, producing the events `Vectors/Bridge.events.json` pins. But it imports
the TypeScript codec, so reaching it from here needs a bundler, which is the one thing this directory
does not have. The trade is deliberate and it is the whole trade: no build step here, no live event
stream here. A host that already bundles — Chargy, or a dev server in front of `shell/` — gets both.

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

A live event stream in the plain-browser path, which means either a bundler here or a stripped copy
of the codec. Everything else this application does already works without one.
