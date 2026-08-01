// @ts-check

/**
 * The scanned pairing code, read by the side that shows it to a person.
 *
 * A fourth port of C#'s `PairingUri` / `PairingWarnings`, next to the Kotlin and Swift ones, held to
 * the same corpus — `Vectors/Pairing.payload.vectors.json`, 22 cases, most of them refusals.
 *
 * ## Why the app parses this rather than asking the native side
 *
 * The confirmation sheet is the one screen whose whole job is to let a person decide, so what it
 * shows has to be derivable from the scanned string alone — and `EvSimulatorPlugin` has exactly
 * three commands, by an explicit decision recorded next to it. A fourth command for "parse this
 * code" would be the easiest thing in the world to add and the hardest to take back.
 *
 * @module
 */

export const DEFAULT_BASE = "https://open.charging.cloud/evsim/pair";

/** The custom scheme accepted as an alias for in-app scanning. */
export const ALT_SCHEME = "v2gsim";

/**
 * Parameter names. Those marked AFIR come from the OCPP v2.1 `ProcessURLTemplate` vocabulary (§4.5),
 * of which this is deliberately a superset — a conformant AFIR code partially works here instead of
 * being a parallel invention.
 */
const KEY = {
    version:  "v",           // AFIR: {version}
    totp:     "totp",        // AFIR: {totp}
    evseId:   "evseId",      // AFIR
    tariffId: "tariffId",    // AFIR
    currency: "currency",    // AFIR
    language: "uiLanguage",  // AFIR
    host:     "host",
    port:     "port",
    tp:       "tp",
    proto:    "proto",
    crypto:   "crypto",
    nc:       "nc",
    ncWhy:    "ncwhy",
    root:     "root",
    meter:    "meter",
    wifi:     "wifi",
};

const KNOWN = Object.values(KEY);

/** The curve the -20 conformant profile requires (`docs/pki-model.md`). */
export const CONFORMANT_CURVE = "secp521r1";


/**
 * A pairing code that is one, and is broken.
 *
 * Distinct from "that was some other QR code", which is a shrug. The difference matters at the
 * scanner: one is worth telling the user about and the other is not.
 */
export class PairingFormatError extends Error {
    /** @param {string} message */
    constructor(message) {
        super(message);
        this.name = "PairingFormatError";
    }
}


/**
 * @typedef {object} PairingPayload
 * @property {number} version
 * @property {string} host
 * @property {number} port
 * @property {"tls" | "tcp"} transport
 * @property {string[]} protocols
 * @property {string | null} crypto
 * @property {boolean} nonConformant
 * @property {string | null} nonConformanceReason
 * @property {string | null} rootFingerprint
 * @property {string | null} meter
 * @property {string | null} totp
 * @property {string | null} evseId
 * @property {string | null} tariffId
 * @property {string | null} currency
 * @property {string | null} uiLanguage
 * @property {string | null} wifiSsid
 * @property {string | null} wifiPsk
 * @property {Record<string, string>} extra
 */


/**
 * Parses a scanned string.
 *
 * @param {string} text
 * @returns {PairingPayload | null} null if it is not a pairing code at all.
 * @throws {PairingFormatError} if it is one and malformed.
 */
export function parsePairingCode(text) {

    const trimmed = text.trim();

    /** @type {URL} */
    let url;
    try {
        url = new URL(trimmed);
    } catch {
        return null;                       // not a URI: some other QR code
    }

    const scheme = url.protocol.replace(/:$/, "").toLowerCase();

    const isPairing = scheme === ALT_SCHEME
                   || (scheme === "https" && url.pathname.endsWith("/pair"));

    if (!isPairing) return null;

    // The fragment is taken from the raw text rather than from `url.hash`, which normalises and
    // re-encodes: this parser has to agree with three others character for character, and the one
    // way to guarantee that is to read the same bytes they read.
    //
    // The fragment is also the whole point of the format and not a style choice: fragments are never
    // sent to a server, so a code scanned with the stock camera app opens a harmless landing page
    // while the endpoint, crypto profile and fingerprints stay on the device. A query-form code
    // therefore parses as empty rather than working by accident.
    const hash     = trimmed.indexOf("#");
    const fragment = hash < 0 ? "" : trimmed.slice(hash + 1);

    if (fragment.length === 0)
        throw new PairingFormatError(
            "the pairing code carries no fragment; data in the query string is not read, "
          + "because a query is sent to the server");

    const fields = parseFields(fragment);

    const version = require_(fields, KEY.version);
    const versionNumber = integer(version);
    if (versionNumber === null)
        throw new PairingFormatError(`version '${version}' is not a number`);

    const host = require_(fields, KEY.host);
    const port = require_(fields, KEY.port);
    const portNumber = integer(port);
    if (portNumber === null || portNumber < 1 || portNumber > 65535)
        throw new PairingFormatError(`port '${port}' is not a port number`);

    const tp = fields.get(KEY.tp);
    /** @type {"tls" | "tcp"} */
    let transport;
    if (tp === undefined || tp === "tls") transport = "tls";
    else if (tp === "tcp")                transport = "tcp";
    else throw new PairingFormatError(`unknown transport '${tp}'`);

    const [wifiSsid, wifiPsk] = splitWifi(fields.get(KEY.wifi) ?? null);

    /** @type {Record<string, string>} */
    const extra = {};
    for (const [key, value] of fields)
        if (!KNOWN.includes(key)) extra[key] = value;

    const proto = fields.get(KEY.proto);

    return {
        version:              versionNumber,
        host,
        port:                 portNumber,
        transport,
        protocols:            proto === undefined
                                  ? []
                                  : proto.split(",").map(p => p.trim()).filter(p => p.length > 0),
        crypto:               fields.get(KEY.crypto)   ?? null,
        nonConformant:        fields.get(KEY.nc) === "1" || fields.get(KEY.nc) === "true",
        nonConformanceReason: fields.get(KEY.ncWhy)    ?? null,
        rootFingerprint:      fields.get(KEY.root)?.toLowerCase() ?? null,
        meter:                fields.get(KEY.meter)    ?? null,
        totp:                 fields.get(KEY.totp)     ?? null,
        evseId:               fields.get(KEY.evseId)   ?? null,
        tariffId:             fields.get(KEY.tariffId) ?? null,
        currency:             fields.get(KEY.currency) ?? null,
        uiLanguage:           fields.get(KEY.language) ?? null,
        wifiSsid,
        wifiPsk,
        extra,
    };
}


/**
 * @param {string} fragment
 * @returns {Map<string, string>}
 */
function parseFields(fragment) {

    /** @type {Map<string, string>} */
    const fields = new Map();

    for (const pair of fragment.split("&")) {

        if (pair.length === 0) continue;

        const at = pair.indexOf("=");
        if (at < 0)
            throw new PairingFormatError(`'${pair}' is not a key=value pair`);

        const key = pair.slice(0, at);

        // First occurrence wins — no, it does not: a repeated key is how a hostile code smuggles a
        // second value past a reader that shows the first one, so it is refused rather than resolved.
        if (fields.has(key))
            throw new PairingFormatError(`parameter '${key}' appears more than once`);

        fields.set(key, decodeURIComponent(pair.slice(at + 1)));
    }

    return fields;
}


/**
 * @param {Map<string, string>} fields
 * @param {string} key
 */
function require_(fields, key) {
    const value = fields.get(key);
    if (value === undefined || value.length === 0)
        throw new PairingFormatError(`required parameter '${key}' is missing`);
    return value;
}


/**
 * A decimal integer, or null.
 *
 * Deliberately not `parseInt`, which reads `"70000abc"` as 70000 and `"0x10"` as 16 — a parser that
 * accepted either would disagree with the other three back ends on exactly the inputs a hostile code
 * would choose.
 *
 * @param {string} text
 * @returns {number | null}
 */
function integer(text) {
    if (!/^[+-]?\d+$/.test(text)) return null;
    const value = Number(text);
    return Number.isSafeInteger(value) ? value : null;
}


/**
 * `ssid:psk`, with `\:` for a colon inside either half.
 *
 * @param {string | null} wifi
 * @returns {[string | null, string | null]}
 */
function splitWifi(wifi) {

    if (wifi === null) return [null, null];

    let ssid = "";

    for (let i = 0; i < wifi.length; i++) {
        if (wifi[i] === "\\" && i + 1 < wifi.length) { ssid += wifi[++i]; continue; }
        if (wifi[i] === ":") return [ssid, unescapeWifi(wifi.slice(i + 1))];
        ssid += wifi[i];
    }

    return [ssid, null];
}

/** @param {string} text */
function unescapeWifi(text) {
    let out = "";
    for (let i = 0; i < text.length; i++) {
        if (text[i] === "\\" && i + 1 < text.length) { out += text[++i]; continue; }
        out += text[i];
    }
    return out;
}


// ─────────────────────────────────────────────────────────────────────────────────────────────────
//  What is wrong with what the code says
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * @typedef {"unsupportedVersion" | "plaintextTransport" | "weakenedCrypto"
 *         | "declaredNonConformance" | "publicTarget" | "noTrustAnchor" | "noProximityProof"
 *         | "carriesWifiCredentials" | "unknownParameters"} WarningKind
 */

/**
 * @typedef {object} PairingWarning
 * @property {WarningKind} kind
 * @property {string} detail
 * @property {boolean} blocking whether this alone should stop the code being offered as connectable
 */

/** The two that stop a session outright, rather than being shown in red and allowed. */
const BLOCKING = ["unsupportedVersion", "publicTarget"];


/**
 * Everything a confirmation sheet must show in red.
 *
 * Separate from parsing on purpose. Parsing asks "what does this code say?"; this asks "what is
 * wrong with what it says?" — and only the second changes as the threat model does.
 *
 * @param {PairingPayload} payload
 * @returns {PairingWarning[]}
 */
export function warningsFor(payload) {

    /** @type {PairingWarning[]} */
    const warnings = [];

    /** @param {WarningKind} kind @param {string} detail */
    const add = (kind, detail) => warnings.push({ kind, detail, blocking: BLOCKING.includes(kind) });

    if (payload.version !== 1)
        add("unsupportedVersion", `payload version ${payload.version}; this build reads version 1`);

    if (payload.transport === "tcp")
        add("plaintextTransport",
            "the counterpart offers plaintext TCP — the session will not be encrypted");

    // "Unstated" is reported as well as "weakened". A code that says nothing about its curve is not
    // thereby conformant, and silence is the easiest thing for a hostile code to offer.
    if (payload.crypto === null)
        add("weakenedCrypto",
            `no crypto profile stated; the -20 conformant profile is ${CONFORMANT_CURVE}`);
    else if (payload.crypto.toLowerCase() !== CONFORMANT_CURVE)
        add("weakenedCrypto",
            `crypto profile is ${payload.crypto}, not the conformant ${CONFORMANT_CURVE}`);

    if (payload.nonConformant)
        add("declaredNonConformance",
            payload.nonConformanceReason && payload.nonConformanceReason.length > 0
                ? payload.nonConformanceReason
                : "the counterpart declares itself non-conformant but gives no reason");

    if (!isPrivateTarget(payload.host))
        add("publicTarget", `${payload.host} is not a private or link-local address`);

    if (payload.rootFingerprint === null)
        add("noTrustAnchor",
            "no root fingerprint; the certificate chain cannot be checked against this code");

    if (payload.totp === null)
        add("noProximityProof", "static code — it proves nothing about being present now");

    if (payload.wifiPsk !== null)
        add("carriesWifiCredentials",
            `the code carries the password for network ${payload.wifiSsid ?? "(unnamed)"}`);

    const unread = Object.keys(payload.extra);
    if (unread.length > 0)
        add("unknownParameters", "unread parameters: " + unread.sort().join(", "));

    return warnings;
}


/**
 * Whether a host is somewhere the intended counterpart could plausibly be.
 *
 * **Nothing here resolves anything, and that is the rule rather than an optimisation.** Resolving a
 * name would mean a DNS query on behalf of a code nobody has decided to trust yet — a callback to
 * whoever printed it, before any human agreed to anything. So the decision is made on the text: an
 * address literal is judged, and anything else is a name, of which only `.local` counts as
 * reachable-but-local.
 *
 * @param {string} host
 */
export function isPrivateTarget(host) {

    const bare = host.split("%")[0] ?? "";       // strip an IPv6 zone: fe80::1%wlan0

    const v4 = ipv4(bare);
    if (v4 !== null) {
        const [a, b] = v4;
        return a === 127                        // loopback
            || a === 10                         // 10/8
            || (a === 172 && b >= 16 && b <= 31) // 172.16/12
            || (a === 192 && b === 168)          // 192.168/16
            || (a === 169 && b === 254);         // link-local
    }

    if (bare.includes(":")) {
        const lower = bare.toLowerCase();
        if (lower === "::1") return true;                                  // loopback
        const head = (lower.split(":")[0] ?? "").padStart(4, "0");
        if (!/^[0-9a-f]{4}$/.test(head)) return false;
        const first = parseInt(head, 16);
        return (first & 0xffc0) === 0xfe80                                 // fe80::/10
            || (first & 0xfe00) === 0xfc00;                                // fc00::/7
    }

    return host.toLowerCase().endsWith(".local");
}


/**
 * Four dotted decimal octets, or null. Deliberately not a resolver — see above.
 *
 * @param {string} text
 * @returns {number[] | null}
 */
function ipv4(text) {

    const parts = text.split(".");
    if (parts.length !== 4) return null;

    /** @type {number[]} */
    const octets = [];

    for (const part of parts) {
        if (!/^\d+$/.test(part)) return null;
        if (part.length > 1 && part.startsWith("0")) return null;   // no octal ambiguity
        const value = Number(part);
        if (value > 255) return null;
        octets.push(value);
    }

    return octets;
}
