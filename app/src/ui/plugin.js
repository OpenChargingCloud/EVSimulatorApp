// @ts-check

/**
 * The plugin, if this build has one — and nothing if it does not.
 *
 * ## One way in, deliberately
 *
 * `npm run build` compiles `src/vendor/entry.ts` — the one TypeScript file in this directory — into
 * `vendor/ev-simulator.js`, carrying the plugin, the EXI codec and three recorded ISO 15118-2
 * sessions. That bundle is the only source of a session, in a browser *and* in the shell: what it
 * exports is `registerPlugin(...)`, which already prefers the native implementation where there is
 * one and falls back to replaying a recording where there is not.
 *
 * **An earlier version also reached for `globalThis.Capacitor.Plugins.EvSimulator` when the bundle
 * was missing, and that was a mistake worth recording.** The two are not the same shape: the raw
 * plugin delivers `{ event: string }` and takes `start({ config })`, while the bundle exports the
 * *adapted* one, which delivers a parsed `BridgeEvent` and takes `start(config)`. The app read
 * `payload.event`, got `undefined`, and dropped every event of every session — silently, because the
 * listener catches unreadable events by design. The session screen simply stayed empty.
 *
 * Supporting both would have meant a second copy of `adapt` here, in another language, for the two
 * of them to drift apart in. So there is one contract and one adapter, and a build that wants a
 * session builds the bundle. `npm run build` is also what `shell/`'s `sync` runs first.
 *
 * ## No bundle is a state, not a failure
 *
 * Everything this application does *before* a session — scan, parse, warn, refuse, the manual form —
 * needs no plugin at all, so a fresh clone stays runnable with `python3 -m http.server` and no
 * install. It says plainly that it cannot open a session.
 *
 * @module
 */

/** @type {any} */
let resolved;

/**
 * @returns {Promise<any>} the plugin, or null when this build has no bundle.
 */
export async function plugin() {

    if (resolved !== undefined) return resolved;

    try {
        // A missing bundle rejects here, and logs a 404 on the way. That noise is the honest cost of
        // the alternative being unavailable: probing with `fetch` first would need `connect-src`,
        // which index.html forbids outright so that a scanned code cannot make anything fetch.
        const bundle = await import("../../vendor/ev-simulator.js");
        return resolved = bundle.EvSimulator;
    } catch {
        return resolved = null;
    }

}


/**
 * Re-derives a signed message's digest from its own frame — or answers `null` when this build
 * cannot.
 *
 * The same bundle, for the same reason: the computation needs the EXI codec. Everything *around* it
 * — what a match means, what an absent answer means — is `digestCheckFor` in `session.js`, where
 * `npm test` reaches it without a browser.
 *
 * A build with no bundle answers `null` rather than throwing, so the inspector says "not checked"
 * and goes on showing everything else. That is the same posture the rest of this file takes: a
 * missing bundle is a state, not a failure.
 *
 * @param {string} frameHex
 * @returns {Promise<string | null>}
 */
export async function digestDeriver(frameHex) {

    try {
        const bundle = await import("../../vendor/ev-simulator.js");
        return typeof bundle.digestOfFrame === "function" ? await bundle.digestOfFrame(frameHex) : null;
    } catch {
        return null;
    }

}


/**
 * Verifies a signed message against the contract certificate a session presented — or `null` when
 * this build cannot.
 *
 * The second half of the same seam. It needs a codec the app does not carry *twice over*: the -2
 * one to read both messages, and the standalone-xmldsig one to re-encode the octets that were
 * actually signed.
 *
 * @param {string} signedFrameHex
 * @param {string} certificateFrameHex
 * @returns {Promise<boolean | null>}
 */
export async function signatureVerifier(signedFrameHex, certificateFrameHex) {

    try {
        const bundle = await import("../../vendor/ev-simulator.js");
        return typeof bundle.verifySignatureOfFrame === "function"
                   ? await bundle.verifySignatureOfFrame(signedFrameHex, certificateFrameHex)
                   : null;
    } catch {
        return null;
    }

}
