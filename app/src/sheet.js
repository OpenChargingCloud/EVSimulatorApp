// @ts-check

import { parsePairingCode, warningsFor, PairingFormatError } from "./pairing.js";

/**
 * What the confirmation sheet shows, and what it decides.
 *
 * ## Why this is a view model rather than markup
 *
 * Everything on this screen comes from a QR code, which is an image anyone can tape over a display.
 * A renderer that built HTML out of it would be one forgotten escape away from running whatever the
 * code said, so the decisions live here as data and `ui/dom.js` writes them with `textContent` and
 * nothing else. The rule is enforced by absence rather than by discipline —
 * `dom.test.js` asserts that the DOM layer contains no `innerHTML` at all.
 *
 * It also means the sheet is testable: what a user is shown, and whether the button is live, are
 * pure functions of a scanned string.
 *
 * @module
 */

/**
 * @typedef {import("./pairing.js").PairingPayload} PairingPayload
 * @typedef {import("./pairing.js").PairingWarning} PairingWarning
 */

/**
 * @typedef {object} SessionChoices
 * @property {"iso15118-2" | "iso15118-20"} protocol
 * @property {"ac" | "dc"} mode
 * @property {"eim" | "pnc"} authorization
 */

/**
 * @typedef {object} SessionConfig
 * @property {string} host
 * @property {number} port
 * @property {"tls" | "tcp"} transport
 * @property {"iso15118-2" | "iso15118-20"} protocol
 * @property {"ac" | "dc"} mode
 * @property {"eim" | "pnc"} authorization
 * @property {string} [totp]
 * @property {string} [rootFingerprint]
 */

/**
 * @typedef {object} SheetView
 * @property {"code" | "notPairing" | "malformed"} outcome
 * @property {string} title
 * @property {string | null} problem   what to tell the user when there is no code to show
 * @property {{label: string, value: string, wide?: boolean}[]} facts
 * @property {PairingWarning[]} warnings  blocking ones first, then the rest, each in corpus order
 * @property {boolean} canConnect
 * @property {string | null} refusal   why not, when `canConnect` is false
 * @property {PairingPayload | null} payload
 * @property {SessionChoices | null} choices  the defaults this code suggests
 */


/**
 * Reads a scanned string into everything the sheet needs.
 *
 * Never throws: a sheet that crashed on a hostile code would be the same failure as one that trusted
 * it. The three outcomes are the three things worth telling a person — this is a code, this was some
 * other QR code, or this claimed to be a code and is broken.
 *
 * @param {string} text
 * @returns {SheetView}
 */
export function sheetFor(text) {

    /** @type {PairingPayload | null} */
    let payload;

    try {
        payload = parsePairingCode(text);
    } catch (problem) {
        return {
            outcome:    "malformed",
            title:      "That is a pairing code, and it is broken",
            problem:    problem instanceof PairingFormatError ? problem.message : String(problem),
            facts:      [],
            warnings:   [],
            canConnect: false,
            refusal:    "A code this build cannot read is a code it will not act on.",
            payload:    null,
            choices:    null,
        };
    }

    if (payload === null) {
        return {
            outcome:    "notPairing",
            title:      "That is not a pairing code",
            problem:    "Nothing was read from it. Try the station's own display, or enter the "
                      + "address by hand.",
            facts:      [],
            warnings:   [],
            canConnect: false,
            refusal:    null,
            payload:    null,
            choices:    null,
        };
    }

    const warnings = warningsFor(payload);
    const blocking = warnings.filter(w => w.blocking);

    return {
        outcome:    "code",
        title:      payload.evseId ?? `${payload.host}:${payload.port}`,
        problem:    null,
        facts:      factsFor(payload),
        // Blocking first: the sheet's job is to make the reason a session cannot start the first
        // thing read, not the eighth.
        warnings:   [...blocking, ...warnings.filter(w => !w.blocking)],
        canConnect: blocking.length === 0,
        refusal:    blocking.length === 0
                        ? null
                        : blocking.map(w => w.detail).join("; "),
        payload,
        choices:    defaultChoices(payload),
    };
}


/**
 * What the code says, in the order a person reads it: where, how, and then who says so.
 *
 * @param {PairingPayload} payload
 * @returns {{label: string, value: string, wide?: boolean}[]}
 */
function factsFor(payload) {

    /** @type {{label: string, value: string, wide?: boolean}[]} */
    const facts = [
        { label: "Address",   value: `${payload.host}:${payload.port}` },
        { label: "Transport", value: payload.transport === "tls" ? "TLS" : "plain TCP" },
    ];

    if (payload.protocols.length > 0)
        facts.push({ label: "Offers", value: payload.protocols.join(", ") });

    facts.push({ label: "Crypto profile", value: payload.crypto ?? "not stated" });

    if (payload.evseId)   facts.push({ label: "EVSE",     value: payload.evseId });
    if (payload.tariffId) facts.push({ label: "Tariff",   value: payload.tariffId });
    if (payload.currency) facts.push({ label: "Currency", value: payload.currency });

    // `wide` because a fingerprint's only purpose is to be compared, by eye, with one printed
    // somewhere else — and a value squeezed into a right-hand column wraps mid-pair, which is
    // exactly the form that cannot be compared. Seen doing precisely that before this flag existed.
    facts.push(payload.rootFingerprint === null
        ? { label: "Trust anchor", value: "none — the chain cannot be checked against this code" }
        : { label: "Trust anchor", value: groupHex(payload.rootFingerprint), wide: true });

    facts.push({
        label: "Proximity",
        value: payload.totp === null
                   ? "static code — it proves nothing about being present now"
                   : "rotating code",
    });

    if (payload.wifiSsid)
        facts.push({ label: "Wi-Fi", value: payload.wifiSsid });

    return facts;
}


/**
 * What the code suggests, before the user changes anything.
 *
 * The protocol comes from the code when it names one this build runs; the other two are not in the
 * format at all, so they start at the commonest case and the sheet lets the user say otherwise.
 *
 * @param {PairingPayload} payload
 * @returns {SessionChoices}
 */
export function defaultChoices(payload) {

    /** @type {"iso15118-2" | "iso15118-20"} */
    let protocol = "iso15118-2";

    for (const offered of payload.protocols) {
        const named = PROTOCOL_ALIASES[offered.toLowerCase()];
        if (named !== undefined) { protocol = named; break; }
    }

    return { protocol, mode: "ac", authorization: "eim" };
}

/**
 * The short names a code may use for a protocol.
 *
 * A code that names something else is not refused over it — an unknown protocol name is a fact about
 * the station, not a defect in the code — the sheet simply falls back to what this build runs, and
 * the `Offers` line shows what was actually said.
 *
 * @type {Record<string, "iso15118-2" | "iso15118-20">}
 */
const PROTOCOL_ALIASES = {
    "iso2":         "iso15118-2",
    "iso15118-2":   "iso15118-2",
    "iso20":        "iso15118-20",
    "iso15118-20":  "iso15118-20",
};


/**
 * The configuration a confirmed sheet hands to the plugin.
 *
 * Exactly the eight properties `SessionConfig.parse` reads, and no others — it refuses unknown ones,
 * deliberately, so a sheet that added a ninth would fail on a phone rather than here.
 *
 * @param {PairingPayload} payload
 * @param {SessionChoices} choices
 * @returns {SessionConfig}
 */
export function configFor(payload, choices) {

    /** @type {SessionConfig} */
    const config = {
        host:          payload.host,
        port:          payload.port,
        transport:     payload.transport,
        protocol:      choices.protocol,
        mode:          choices.mode,
        authorization: choices.authorization,
    };

    // Omitted rather than sent as null, matching what every back end writes: an absent optional and
    // an explicit null mean the same thing to the parser, and only one of them is what a writer does.
    if (payload.totp !== null)            config.totp            = payload.totp;
    if (payload.rootFingerprint !== null) config.rootFingerprint = payload.rootFingerprint;

    return config;
}


/**
 * A fingerprint in pairs, so a person can compare it with one printed somewhere else.
 *
 * An unbroken 64-character string is the form nobody can check by eye, which defeats the only
 * purpose the field has.
 *
 * **The space every eight bytes is not decoration.** A colon is not a line-break opportunity, so a
 * colon-joined string wraps wherever the box runs out — mid-pair, which is worse than not grouping at
 * all. Seen doing exactly that: `…C5:5A:D` / `0:15:A3…`. A space gives the line somewhere to break.
 *
 * @param {string} hex
 */
export function groupHex(hex) {

    const bytes = (hex.match(/.{1,2}/g) ?? []).map(b => b.toUpperCase());

    /** @type {string[]} */
    const groups = [];

    for (let at = 0; at < bytes.length; at += 8)
        groups.push(bytes.slice(at, at + 8).join(":"));

    return groups.join(" ");
}
