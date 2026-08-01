// @ts-check

import { el, put, clear, field, button, choice, textInput, monospace } from "./dom.js";
import { sheetFor, configFor, groupHex } from "../sheet.js";
import { rowsFor, detailFor, statusOf } from "../session.js";

/**
 * The three screens, drawn from the view models next door.
 *
 * Nothing here decides anything. Which warnings exist, whether the button is live, what a gap in the
 * stream means — all of that is in `sheet.js` and `session.js`, where `npm test` can reach it. This
 * file turns those answers into elements and is the part a laptop cannot check.
 *
 * @module
 */

/**
 * @typedef {import("../sheet.js").SessionChoices} SessionChoices
 * @typedef {import("../sheet.js").SessionConfig} SessionConfig
 * @typedef {import("../session.js").BridgeEvent} BridgeEvent
 */


/**
 * Where a code comes from: a camera, or a person typing.
 *
 * @param {Element} root
 * @param {{onCode: (text: string) => void, onManual: () => void, scanner: null | (() => Promise<string>)}} handlers
 */
export function startScreen(root, handlers) {

    clear(root);

    const status = el("p", "hint");

    put(root,
        el("h1", "", "EV Simulator"),
        el("p", "lede", "Scan the code on the station's display, or enter its address by hand."),
        status);

    if (handlers.scanner !== null) {
        put(root, button("Scan a code", () => {
            status.textContent = "Point the camera at the code…";
            /** @type {() => Promise<string>} */ (handlers.scanner)()
                .then(handlers.onCode)
                .catch(problem => { status.textContent = String(problem); });
        }, { primary: true }));
    } else {
        // Named rather than hidden: a button that is not there is a question the user is left to
        // answer alone.
        status.textContent = "This build has no camera scanner, so the code has to be typed or "
                           + "pasted. See the README.";
    }

    const typed   = el("div", "typed");
    const scanned = textInput("Scanned text", "",
                              { placeholder: "https://open.charging.cloud/evsim/pair#v=1&…" });

    put(root, typed);
    put(typed, scanned.row, button("Read it", () => handlers.onCode(scanned.read())));

    put(root, button("Enter an address instead", handlers.onManual));

    return root;
}


/**
 * The confirmation sheet: what the code says, what is wrong with it, and the decision.
 *
 * @param {Element} root
 * @param {string} scanned
 * @param {{onConnect: (config: SessionConfig) => void, onCancel: () => void}} handlers
 */
export function sheetScreen(root, scanned, handlers) {

    clear(root);

    const sheet = sheetFor(scanned);

    put(root, el("h1", "", sheet.title));

    if (sheet.problem !== null)
        put(root, el("p", "problem", sheet.problem));

    // Warnings above the facts, blocking ones first. The order is the sheet's argument: a person
    // scrolling to the button should have to pass what is wrong on the way.
    for (const warning of sheet.warnings)
        put(root, put(el("div", warning.blocking ? "warning blocking" : "warning"),
                      el("span", "kind", TITLES[warning.kind] ?? warning.kind),
                      el("span", "why", warning.detail)));

    for (const fact of sheet.facts)
        put(root, field(fact.label, fact.value, fact.wide === true ? "wide" : undefined));

    if (sheet.payload !== null && sheet.choices !== null) {

        const choices = { ...sheet.choices };

        put(root,
            choice("Protocol", [{ value: "iso15118-2",  text: "ISO 15118-2" },
                                { value: "iso15118-20", text: "ISO 15118-20" }],
                   choices.protocol, value => { choices.protocol = value; }),
            choice("Energy transfer", [{ value: "ac", text: "AC" }, { value: "dc", text: "DC" }],
                   choices.mode, value => { choices.mode = value; }),
            choice("Authorization", [{ value: "eim", text: "External payment (EIM)" },
                                     { value: "pnc", text: "Plug & Charge" }],
                   choices.authorization, value => { choices.authorization = value; }));

        put(root, button("Connect",
                         () => handlers.onConnect(configFor(/** @type {any} */ (sheet.payload), choices)),
                         { primary: true, disabled: !sheet.canConnect }));
    }

    if (sheet.refusal !== null)
        put(root, el("p", "problem", sheet.refusal));

    put(root, button("Cancel", handlers.onCancel));

    return root;
}


/**
 * The manual fallback: an address typed by a person who has no code to scan.
 *
 * It produces the same `SessionConfig` a sheet does, so the native side reads one shape and not two —
 * and the target restriction applies to it identically, because it is applied where the socket opens.
 *
 * @param {Element} root
 * @param {{onConnect: (config: SessionConfig) => void, onCancel: () => void}} handlers
 */
export function manualScreen(root, handlers) {

    clear(root);

    /** @type {Omit<SessionConfig, "host" | "port">} */
    const chosen = {
        transport: "tls", protocol: "iso15118-2", mode: "ac", authorization: "eim",
    };

    const host = textInput("Host", "", { placeholder: "192.168.4.1 or evsim-pi.local" });
    const port = textInput("Port", "15118", { inputMode: "numeric" });

    const problem = el("p", "problem");

    put(root,
        el("h1", "", "Enter an address"),
        el("p", "lede", "Only a private or link-local address, and only one you were given. "
                      + "Without a code there is no fingerprint to pin, so a TLS session cannot be "
                      + "checked against anything."),
        host.row,
        port.row,
        choice("Transport", [{ value: "tls", text: "TLS" }, { value: "tcp", text: "plain TCP" }],
               chosen.transport, value => { chosen.transport = value; }),
        choice("Protocol", [{ value: "iso15118-2",  text: "ISO 15118-2" },
                            { value: "iso15118-20", text: "ISO 15118-20" }],
               chosen.protocol, value => { chosen.protocol = value; }),
        choice("Energy transfer", [{ value: "ac", text: "AC" }, { value: "dc", text: "DC" }],
               chosen.mode, value => { chosen.mode = value; }),
        choice("Authorization", [{ value: "eim", text: "External payment (EIM)" },
                                 { value: "pnc", text: "Plug & Charge" }],
               chosen.authorization, value => { chosen.authorization = value; }),
        problem,
        button("Connect", () => {
            problem.textContent = "";
            // Read at the moment it is used. The fields are the state; nothing mirrors them.
            handlers.onConnect({ ...chosen, host: host.read().trim(), port: Number(port.read()) });
        }, { primary: true }),
        button("Cancel", handlers.onCancel));

    return root;
}


/**
 * The session: every event as it arrives, and any one of them opened.
 *
 * @param {Element} root
 * @param {BridgeEvent[]} events
 * @param {{onStop: () => void, onBack: () => void}} handlers
 */
export function sessionScreen(root, events, handlers) {

    clear(root);

    const status = statusOf(events);

    put(root, el("h1", "", status.running ? "Session running"
                                          : status.outcome === "completed" ? "Session completed"
                                                                           : "Session failed"));

    if (status.lost > 0)
        put(root, el("p", "problem",
                     `${status.lost} place(s) in this stream are missing events. What is shown is `
                   + "not the whole session."));

    const list = el("div", "events");
    put(root, list);

    for (const row of rowsFor(events)) {

        const node = put(el("div", `row ${row.tone}`),
                         el("span", "seq", String(row.seq)),
                         el("span", "at", row.at),
                         el("span", "title", row.title),
                         el("span", "subtitle", row.subtitle));

        if (row.event !== null) {
            const open = el("div", "opened");
            node.addEventListener("click", () => {
                if (open.childElementCount > 0) { clear(open); return; }
                renderDetail(open, /** @type {BridgeEvent} */ (row.event));
            });
            put(list, node, open);
        } else {
            put(list, node);
        }
    }

    put(root, status.running ? button("Stop", handlers.onStop, { primary: true })
                             : button("Back", handlers.onBack));

    return root;
}


/**
 * @param {Element} root
 * @param {BridgeEvent} event
 */
function renderDetail(root, event) {

    const detail = detailFor(event);

    for (const fact of detail.facts) put(root, field(fact.label, fact.value));

    // Both halves, always. B1 asks the stream to carry every message as JSON-LD *and* as the raw
    // frame; a screen that showed only the readable one would be telling the user the bytes are
    // right there without ever showing them.
    if (detail.json !== null) put(root, el("h2", "", "JSON-LD"), monospace(detail.json));
    if (detail.hex  !== null) put(root, el("h2", "", "V2GTP frame"), monospace(detail.hex));

    return root;
}


/**
 * What each warning is called on screen.
 *
 * The kinds themselves are a corpus value four back ends agree on; these are only their labels, and
 * a kind with no label falls back to the kind — a build that met a warning it had no words for would
 * otherwise show nothing at all, which is the one outcome a warning must never have.
 *
 * @type {Record<string, string>}
 */
const TITLES = {
    unsupportedVersion:     "This build cannot read that code",
    plaintextTransport:     "Not encrypted",
    weakenedCrypto:         "Crypto profile",
    declaredNonConformance: "The station says it is non-conformant",
    publicTarget:           "That address is not local",
    noTrustAnchor:          "Nothing to check the certificate against",
    noProximityProof:       "This code does not prove you are here",
    carriesWifiCredentials: "The code contains a Wi-Fi password",
    unknownParameters:      "The code says things this build does not read",
};

export { groupHex };
