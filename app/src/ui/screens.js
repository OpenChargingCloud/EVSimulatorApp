// @ts-check

import { el, put, clear, field, button, choice, textInput, monospace } from "./dom.js";
import { sheetFor, configFor, groupHex } from "../sheet.js";
import { rowsFor, detailFor, statusOf, timingsFor, exportsFor,
         digestCheckFor, signerCheckFor, meterCheckFor, energyFor,
         backendCheckFor } from "../session.js";
import { digestDeriver, signatureVerifier } from "./plugin.js";

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
 * @param {any} [backendRecord]  what the station told its operator, when anything has fetched it.
 *                               Nothing does yet: it comes from a CSMS, which is outside the
 *                               session and outside this repository — see `backendCheckFor`.
 */
export function sessionScreen(root, events, handlers, backendRecord = null) {

    clear(root);

    const status = statusOf(events);

    put(root, el("h1", "", status.running ? "Session running"
                                          : status.outcome === "completed" ? "Session completed"
                                                                           : "Session failed"));

    if (status.lost > 0)
        put(root, el("p", "problem",
                     `${status.lost} place(s) in this stream are missing events. What is shown is `
                   + "not the whole session."));

    // Two counts of one session, before the message list rather than buried in it: it is the one
    // question a driver actually has, and the only check here that needs no cryptography at all.
    const energy = energyFor(events);
    if (energy.verdict !== "none") {

        put(root, el("h2", "", "Energy"));

        if (energy.vehicleWh !== null)
            put(root, field("This car counted", `${energy.vehicleWh} Wh`
                                              + ` (${energy.samples} charge-loop sample(s))`));
        if (energy.stationWh !== null)
            put(root, field("The station reported", `${energy.stationWh} Wh`));

        // A station that stopped reporting mid-session leaves the two figures at different instants.
        // Showing the total beside them keeps the comparison honest rather than hiding the gap.
        if (energy.vehicleTotalWh !== null && energy.vehicleTotalWh !== energy.vehicleWh)
            put(root, field("This car counted in all",
                            `${energy.vehicleTotalWh} Wh — the station stopped reporting before the `
                          + "session ended, so the two figures above are from the same earlier moment"));
        if (energy.verdict === "differ")
            put(root, field("Difference", `${energy.deviationWh} Wh`, "problem"));

        put(root, el("p", energy.verdict === "agree"  ? "ok"
                        : energy.verdict === "differ" ? "problem"
                                                      : "note",
                     energy.explanation));

        // The third account, when something has fetched one. Under the two above rather than beside
        // them: it is the same quantity again, from a party the driver never talks to.
        const backend = backendCheckFor(events, backendRecord);
        if (backend.verdict !== "none") {

            if (backend.backendWh !== null)
                put(root, field("The backend was told", `${backend.backendWh} Wh`
                                                      + (backend.signedValues > 0
                                                             ? ` (${backend.matched} of `
                                                             + `${backend.signedValues} signed reading(s) `
                                                             + "match what this car saw)"
                                                             : "")));

            put(root, el("p", backend.verdict === "consistent"           ? "ok"
                            : backend.verdict === "reported-differently" ? "problem"
                            : backend.verdict === "energy-differs"       ? "problem"
                                                                        : "note",
                         backend.explanation));
        }
    }

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
                renderDetail(open, /** @type {BridgeEvent} */ (row.event), events);
            });
            put(list, node, open);
        } else {
            put(list, node);
        }
    }

    if (!status.running) renderTimings(root, events);

    if (!status.running) renderExports(root, events);

    put(root, status.running ? button("Stop", handlers.onStop, { primary: true })
                             : button("Back", handlers.onBack));

    return root;
}


/**
 * The session as files, offered once it is over.
 *
 * A download is built here rather than in the view model because it is the one part that touches the
 * platform: an object URL and a click. What the files *contain*, and what is wrong with them, is
 * `exportsFor`'s answer and is testable without a browser.
 *
 * @param {Element} root
 * @param {BridgeEvent[]} events
 */
function renderExports(root, events) {

    const started = events.find(e => e.kind === "sessionStarted");
    const name    = String(started?.name ?? "session");
    const bundle  = exportsFor(events, name);

    put(root, el("h2", "", "Export"));

    for (const caveat of bundle.caveats)
        put(root, el("p", "problem", caveat));

    for (const file of bundle.files)
        put(root, button(file.name, () => download(file)));

    return root;
}

/** @param {{name: string, mime: string, text: string}} file */
function download(file) {
    const url  = URL.createObjectURL(new Blob([file.text], { type: file.mime }));
    const link = el("a", "", "");
    link.setAttribute("href", url);
    link.setAttribute("download", file.name);
    link.dispatchEvent(new MouseEvent("click"));
    URL.revokeObjectURL(url);
}


/**
 * @param {Element} root
 * @param {BridgeEvent} event
 * @param {BridgeEvent[]} events  the whole stream — the signer check needs the contract certificate,
 *                                which arrived in an earlier message
 */
function renderDetail(root, event, events) {

    const detail = detailFor(event);

    for (const fact of detail.facts) put(root, field(fact.label, fact.value));

    // The signature first when there is one: it is the thing about this message that nobody can
    // normally see, and burying it under a screen of hex would be an odd way to present it.
    if (detail.signature.present) {

        put(root, el("h2", "", "Signature"));

        for (const problem of detail.signature.problems)
            put(root, el("p", "problem", problem));

        // The verdict lands here when it arrives. Re-deriving a digest means decoding a frame and
        // hashing it, which is asynchronous — so the row is drawn now and answered a tick later,
        // rather than the whole detail pane waiting on it.
        const verdict = el("p", "note", "Checking the digest…");
        put(root, verdict);

        digestCheckFor(event, digestDeriver).then(check => {
            verdict.className   = check.verdict === "match"    ? "ok"
                                : check.verdict === "mismatch" ? "problem"
                                                               : "note";
            verdict.textContent = check.explanation;
        });

        // Two questions, two lines, never merged: *what* the signature covers, and *who* made it.
        // A single verdict would have to be the weaker of them and would read as the stronger.
        const signer = el("p", "note", "Checking who signed…");
        put(root, signer);

        signerCheckFor(event, events, signatureVerifier).then(check => {
            signer.className   = check.verdict === "signed-by-contract" ? "ok"
                               : check.verdict === "wrong-signer"       ? "problem"
                                                                       : "note";
            signer.textContent = check.explanation;
        });

        for (const fact of detail.signature.facts)
            put(root, field(fact.label, fact.value, undefined));
    }

    // The station's meter, when it reported one. A third signer and a different claim from the two
    // above: those are about the vehicle's message, this is about the station's number — the one
    // somebody is going to be billed for.
    if (detail.meter.present) {

        put(root, el("h2", "", "Meter reading"));

        const verdict = el("p", "note", "Checking the meter's signature…");
        put(root, verdict);

        meterCheckFor(event, events).then(check => {
            verdict.className   = check.verdict === "signed-by-meter" ? "ok"
                                : check.verdict === "wrong-meter"     ? "problem"
                                                                      : "note";
            verdict.textContent = check.explanation;
        });

        for (const fact of detail.meter.facts)
            put(root, field(fact.label, fact.value, undefined));
    }

    // Both halves, always. B1 asks the stream to carry every message as JSON-LD *and* as the raw
    // frame; a screen that showed only the readable one would be telling the user the bytes are
    // right there without ever showing them.
    if (detail.json !== null) put(root, el("h2", "", "JSON-LD"), monospace(detail.json));

    if (detail.frame !== null) {

        put(root, el("h2", "", "V2GTP frame"));

        for (const problem of detail.frame.problems)
            put(root, el("p", "problem", problem));

        for (const f of detail.frame.header)
            put(root, field(`${f.label}  ${f.bytes}`, f.value, f.problem === null ? undefined : "problem"));

        put(root, el("h3", "", `EXI payload · ${detail.frame.bodyBytes} byte(s)`),
                  monospace(detail.frame.body));
    }

    return root;
}


/**
 * Where the session spent its time, as bars.
 *
 * @param {Element} root
 * @param {BridgeEvent[]} events
 */
function renderTimings(root, events) {

    const timings = timingsFor(events);
    if (timings.phases.length === 0) return root;

    put(root, el("h2", "", "Timing"));
    put(root, el("p", "note",
                 `${timings.totalMillis} ms across ${timings.phases.length} phase(s). Measured when `
               + "each event reached this screen — for a replay that times the replay, not the "
               + "recorded session, which carries no timings of its own."));

    for (const phase of timings.phases) {

        const row = put(el("div", `phase${phase.overBudget ? " problem" : ""}`),
                        el("span", "title", phase.count === 1 ? phase.name
                                                              : `${phase.name} ×${phase.count}`),
                        el("span", "at", `${phase.millis} ms`));

        // The bar is the waterfall: a poll loop that swallowed the session is a long bar, where the
        // list of identical rows it collapses says the same thing in thirty lines.
        const bar  = el("div", "bar");
        const fill = el("div", "fill");
        fill.setAttribute("style", `width: ${(phase.share * 100).toFixed(1)}%`);
        put(bar, fill);
        put(row, bar);

        put(root, row);
    }

    if (timings.anyOverBudget)
        put(root, el("p", "problem",
                     `A phase ran past ${timings.budgetMillis} ms, which is the limit our own EVCCs `
                   + "apply to a station that keeps answering Ongoing."));

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
