// @ts-check

/**
 * The session screen: what a stream of bridge events looks like to a person.
 *
 * A view model rather than markup, for the reason `sheet.js` gives — but with a second reason of its
 * own. An event's JSON-LD came from a station, so it is no more trustworthy than the QR code was,
 * and it is *deeper*: nested objects, arbitrary strings, hex that may not be hex.
 *
 * @module
 */

/**
 * @typedef {object} BridgeEventBase
 * @property {number} seq
 * @property {number} atMillis
 * @property {string} kind
 */

/**
 * @typedef {BridgeEventBase & Record<string, any>} BridgeEvent
 */

/**
 * @typedef {object} Row
 * @property {number} seq
 * @property {"started" | "out" | "in" | "error" | "finished" | "gap"} tone
 * @property {string} at        milliseconds since the session began, as shown
 * @property {string} title
 * @property {string} subtitle
 * @property {BridgeEvent | null} event  null for a gap, which is not an event but the absence of one
 */


/**
 * Every event as a row, with the gaps made visible.
 *
 * ## The gap row is the point of this function
 *
 * `seq` exists so that "a consumer that sees a gap has lost events" — that is what the field is
 * documented to be for, in four back ends, and until now nothing anywhere actually looked. A dropped
 * event on a bridge is silent by construction: the listener is simply not called, so a screen that
 * rendered what it received would show a shorter session and no sign that it was shorter.
 *
 * @param {BridgeEvent[]} events  in arrival order
 * @returns {Row[]}
 */
export function rowsFor(events) {

    /** @type {Row[]} */
    const rows = [];

    let expected = 0;

    for (const event of events) {

        if (event.seq > expected) {
            const missing = event.seq - expected;
            rows.push({
                seq:      expected,
                tone:     "gap",
                at:       "",
                title:    missing === 1 ? "1 event was lost" : `${missing} events were lost`,
                subtitle: `nothing arrived for seq ${expected}`
                        + (missing > 1 ? `–${event.seq - 1}` : ""),
                event:    null,
            });
        }

        // Not `= event.seq + 1` alone: a stream that went backwards would otherwise erase the gap it
        // just reported, and a repeated seq is as much a fault as a missing one.
        expected = Math.max(expected, event.seq) + 1;

        rows.push(rowFor(event));
    }

    return rows;
}


/**
 * @param {BridgeEvent} event
 * @returns {Row}
 */
function rowFor(event) {

    const at = `${event.atMillis} ms`;

    switch (event.kind) {

        case "sessionStarted":
            return { seq: event.seq, tone: "started", at,
                     title: String(event.name ?? "session"),
                     subtitle: `${event.protocol} · ${event.mode}`,
                     event };

        case "message":
            return { seq: event.seq, tone: event.direction === "out" ? "out" : "in", at,
                     title: String(event.messageName ?? "message"),
                     subtitle: `${event.payloadType} · ${byteCount(String(event.exi ?? ""))}`,
                     event };

        case "sessionFinished":
            return { seq: event.seq, tone: "finished", at,
                     title: event.outcome === "completed" ? "Session completed" : "Session failed",
                     subtitle: `${event.exchanges} exchanges`,
                     event };

        case "error":
            return { seq: event.seq, tone: "error", at,
                     title: "Error",
                     subtitle: String(event.detail ?? ""),
                     event };

        default:
            // A kind this build does not know is shown rather than dropped. The stream is a record,
            // and a record with something quietly missing from it is worse than one with something
            // unexplained in it.
            return { seq: event.seq, tone: "error", at,
                     title: `Unknown event '${String(event.kind)}'`,
                     subtitle: "this build does not know what to make of it",
                     event };
    }
}


/**
 * @typedef {object} Detail
 * @property {{label: string, value: string}[]} facts
 * @property {string | null} json     the JSON-LD document, indented
 * @property {Frame | null} frame     the raw V2GTP frame, annotated and checked
 * @property {SignatureView} signature
 */

/**
 * One event, opened.
 *
 * Both halves when there are both: B1 asks the stream to carry every message as JSON-LD *and* as the
 * raw frame, and a screen that showed only the readable one would quietly discard the evidence.
 *
 * @param {BridgeEvent} event
 * @returns {Detail}
 */
export function detailFor(event) {

    /** @type {{label: string, value: string}[]} */
    const facts = [
        { label: "Sequence", value: String(event.seq) },
        { label: "At",       value: `${event.atMillis} ms` },
        { label: "Kind",     value: String(event.kind) },
    ];

    if (event.kind === "message") {
        facts.push({ label: "Direction",    value: event.direction === "out" ? "sent" : "received" });
        facts.push({ label: "Payload type", value: String(event.payloadType) });
        facts.push({ label: "Message",      value: String(event.messageName) });
    }

    if (event.kind === "sessionFinished") {
        facts.push({ label: "Outcome",   value: String(event.outcome) });
        facts.push({ label: "Exchanges", value: String(event.exchanges) });
    }

    if (event.kind === "error")
        facts.push({ label: "Detail", value: String(event.detail) });

    return {
        facts,
        json: event.json === undefined || event.json === null
                  ? null
                  : JSON.stringify(event.json, null, 2),
        frame:     typeof event.exi === "string" ? frameFor(event.exi) : null,
        signature: signatureFor(event),
    };
}


/**
 * @typedef {object} FrameField
 * @property {string} label
 * @property {string} bytes    the octets, as hex
 * @property {string} value    what they mean
 * @property {string | null} problem
 */

/**
 * @typedef {object} Frame
 * @property {FrameField[]} header   the 8-byte V2GTP header, field by field
 * @property {string} body           the EXI payload, in lines of 16 bytes
 * @property {number} bodyBytes
 * @property {string[]} problems
 */

/**
 * The V2GTP payload types, by the numbers the dispatchers use.
 *
 * `0x8001` carries two different things and that is not a mistake in this table: the
 * SupportedAppProtocol handshake and every ISO 15118-2 message share it, and which one a frame holds
 * is decided by session phase — SAP is what comes first. Four back ends have that rule written down
 * because deciding it from the bytes is a guess that yields a message which looks fine.
 *
 * @type {Record<number, string>}
 */
const PAYLOAD_TYPES = {
    0x8001: "SupportedAppProtocol / ISO 15118-2",
    0x8002: "ISO 15118-20 CommonMessages",
    0x8003: "ISO 15118-20 AC",
    0x8004: "ISO 15118-20 DC",
    0x8005: "ISO 15118-20 ACDP",
    0x8006: "ISO 15118-20 WPT",
};

/**
 * A raw frame, read as the transport reads it.
 *
 * The 8-byte V2GTP header is the one part of a frame that can be annotated without the codec's
 * grammar plan, and — more usefully — the one part that can be *checked*. Three things are worth
 * saying out loud, because each is a real failure this project has met:
 *
 * - the declared payload length against the bytes actually present, which is what tells a stream
 *   reader where a frame ends (`tools/EVSimulatorApp.WsBridge`'s whole content is getting this right);
 * - the version byte and its inverse, which is the only integrity the header has;
 * - the payload type, since 0x8003 and 0x8004 are two whole grammars seven bits apart.
 *
 * @param {string} hex  the whole frame, header included
 * @returns {Frame}
 */
export function frameFor(hex) {

    const bytes = (hex.match(/.{1,2}/g) ?? []).map(b => parseInt(b, 16));

    /** @type {string[]} */
    const problems = [];

    if (bytes.length < 8) {
        problems.push(`${bytes.length} byte(s): shorter than the 8-byte V2GTP header, so there is `
                    + "no frame here to read.");
        return { header: [], body: hexLines(hex), bodyBytes: bytes.length, problems };
    }

    const version     = bytes[0];
    const inverse     = bytes[1];
    const payloadType = (bytes[2] << 8) | bytes[3];
    const declared    = ((bytes[4] << 24) | (bytes[5] << 16) | (bytes[6] << 8) | bytes[7]) >>> 0;
    const actual      = bytes.length - 8;

    const versionProblem = version !== 0x01 ? `expected 0x01` : null;
    // Deliberately checked against the version rather than against the constant 0xFE: the field is an
    // inverse, and comparing it to what it is the inverse *of* is the check the protocol intends.
    const inverseProblem = inverse !== ((~version) & 0xFF)
                               ? `expected 0x${(((~version) & 0xFF)).toString(16).padStart(2, "0")}, `
                               + "the one's complement of the version"
                               : null;
    const typeProblem    = PAYLOAD_TYPES[payloadType] === undefined
                               ? "not a payload type any back end here dispatches"
                               : null;
    const lengthProblem  = declared !== actual
                               ? `the header declares ${declared}, but ${actual} byte(s) follow it`
                               : null;

    for (const problem of [versionProblem, inverseProblem, typeProblem, lengthProblem])
        if (problem !== null) problems.push(problem);

    return {
        header: [
            { label: "Version",      bytes: hexOf(bytes, 0, 1),
              value: `0x${hexOf(bytes, 0, 1)}`, problem: versionProblem },
            { label: "Version (inv)", bytes: hexOf(bytes, 1, 1),
              value: `0x${hexOf(bytes, 1, 1)}`, problem: inverseProblem },
            { label: "Payload type", bytes: hexOf(bytes, 2, 2),
              value: `0x${payloadType.toString(16).padStart(4, "0")}`
                   + (PAYLOAD_TYPES[payloadType] !== undefined ? ` · ${PAYLOAD_TYPES[payloadType]}` : ""),
              problem: typeProblem },
            { label: "Payload length", bytes: hexOf(bytes, 4, 4),
              value: `${declared} byte(s)`, problem: lengthProblem },
        ],
        body:      hexLines(hex.slice(16)),
        bodyBytes: actual,
        problems,
    };
}

/** @param {number[]} bytes @param {number} at @param {number} count */
function hexOf(bytes, at, count) {
    return bytes.slice(at, at + count).map(b => b.toString(16).padStart(2, "0")).join("");
}


/**
 * Hex in lines of 16 bytes, the way a frame is read.
 *
 * @param {string} hex
 */
export function hexLines(hex) {

    const bytes = hex.match(/.{1,2}/g) ?? [];
    /** @type {string[]} */
    const lines = [];

    for (let at = 0; at < bytes.length; at += 16)
        lines.push(bytes.slice(at, at + 16).join(" "));

    return lines.join("\n");
}


/** @param {string} hex */
function byteCount(hex) {
    const bytes = Math.floor(hex.length / 2);
    return bytes === 1 ? "1 byte" : `${bytes} bytes`;
}


/**
 * How long a phase may keep answering `Ongoing` before our EVCCs end the session.
 *
 * 60 s, the value `OngoingGuard` carries in all three back ends — quoted here as *this project's*
 * documented limit rather than as a requirement number, because the standard's text is not on hand
 * and an unchecked `[V2G2-nnn]` reads as a verified one. It is the only budget the repository
 * actually owns; per-message performance budgets are not written down anywhere here, so this screen
 * does not pretend to compare against them.
 */
export const ONGOING_BUDGET_MS = 60_000;

/**
 * @typedef {object} Phase
 * @property {string} name        the message that repeated, or the single message
 * @property {number} count       how many exchanges
 * @property {number} fromSeq
 * @property {number} millis      wall time across the whole run of them
 * @property {number} share       fraction of the session's measured time, 0…1
 * @property {boolean} overBudget
 */

/**
 * @typedef {object} Timings
 * @property {Phase[]} phases
 * @property {number} totalMillis
 * @property {number} budgetMillis
 * @property {boolean} anyOverBudget
 */

/**
 * Where the session spent its time, by phase.
 *
 * Consecutive exchanges of the same message are one **poll loop**, not thirty rows, and collapsing
 * them is what turns a list into a waterfall: a -20 DC session is 100-odd exchanges of which 78 are
 * `DC_CableCheckReq`, and the interesting statement is "the cable check took nine seconds", not the
 * seventy-eight lines that say so.
 *
 * The clock is the bridge's own `atMillis`, stamped when an event was delivered. In a replay that
 * measures the replay — including the browser's timer throttling — and not the recorded session,
 * which carries no timings at all. That is worth knowing before reading anything into a number here.
 *
 * @param {BridgeEvent[]} events
 * @returns {Timings}
 */
export function timingsFor(events) {

    const messages = events.filter(e => e.kind === "message");

    /** @type {Phase[]} */
    const phases = [];

    for (const event of messages) {

        // The station's answer belongs to the request it answers, so only outbound messages open a
        // phase; an inbound one extends the phase it completes.
        const name = String(event.messageName ?? "message");
        const last = phases[phases.length - 1];

        if (event.direction === "out" && (last === undefined || last.name !== name)) {
            phases.push({ name, count: 1, fromSeq: event.seq, millis: 0, share: 0, overBudget: false });
            continue;
        }

        if (last === undefined) continue;   // an inbound message before any outbound one

        if (event.direction === "out") last.count += 1;
        last.millis = Math.max(last.millis, event.atMillis - firstAtOf(messages, last.fromSeq));
    }

    const totalMillis = messages.length === 0
                            ? 0
                            : messages[messages.length - 1].atMillis - messages[0].atMillis;

    for (const phase of phases) {
        phase.share      = totalMillis > 0 ? phase.millis / totalMillis : 0;
        phase.overBudget = phase.millis > ONGOING_BUDGET_MS;
    }

    return {
        phases,
        totalMillis,
        budgetMillis:  ONGOING_BUDGET_MS,
        anyOverBudget: phases.some(p => p.overBudget),
    };
}

/** @param {BridgeEvent[]} messages @param {number} seq */
function firstAtOf(messages, seq) {
    const found = messages.find(m => m.seq === seq);
    return found === undefined ? 0 : found.atMillis;
}


/**
 * @typedef {object} SignatureView
 * @property {boolean} present
 * @property {{label: string, value: string}[]} facts
 * @property {string[]} problems
 * @property {string[]} limits   what this screen cannot tell you, said out loud
 */

/**
 * The XMLDSig on a message, opened.
 *
 * This is §4.2's point — *"show the exact bytes being signed, the digest, and the signature; this is
 * the part nobody can normally see"* — as far as a screen holding only the event can go. What it
 * shows is what the signature **claims**: which element it covers, by `Id`; the digest over that
 * element; the algorithms; the signature value.
 *
 * What it checks is the one thing checkable without a codec: that the reference actually points at an
 * element **in this message**. A signature over `#id2` in a message whose only `Id` is `id1` is
 * covering nothing that is here, and no amount of correct cryptography would make that all right.
 *
 * What it cannot do is stated rather than implied, because a screen that shows a digest next to a
 * green tick it did not earn is worse than one that shows neither. Re-deriving the digest needs the
 * referenced element re-encoded as canonical EXI — and that is now a *wiring* job rather than an
 * impossible one: `typescript/src/iso2/Iso15118_2Codec.ts` has `encodeFragment_MeteringReceiptReq`,
 * `encodeFragment_AuthorizationReq` and `encodeFragment_SignedInfo`, and WebCrypto has SHA-256. The
 * digest needs no key at all.
 *
 * @param {BridgeEvent} event
 * @returns {SignatureView}
 */
export function signatureFor(event) {

    const signature = event.json?.header?.signature;

    if (signature === undefined || signature === null)
        return { present: false, facts: [], problems: [], limits: [] };

    const signedInfo = signature.signedInfo ?? {};
    const references = Array.isArray(signedInfo.reference) ? signedInfo.reference
                     : signedInfo.reference !== undefined  ? [signedInfo.reference]
                                                           : [];

    /** @type {{label: string, value: string}[]} */
    const facts = [];
    /** @type {string[]} */
    const problems = [];

    facts.push({ label: "Canonicalization",
                 value: String(signedInfo.canonicalizationMethod?.algorithm ?? "—") });
    facts.push({ label: "Signature method",
                 value: String(signedInfo.signatureMethod?.algorithm ?? "—") });

    if (references.length === 0)
        problems.push("The SignedInfo references nothing, so this signature covers no element.");

    const ids = idsIn(event.json);

    for (const reference of references) {

        const uri = String(reference.uri ?? "");
        facts.push({ label: "Covers",        value: uri || "—" });
        facts.push({ label: "Digest method", value: String(reference.digestMethod?.algorithm ?? "—") });
        facts.push({ label: "Digest",        value: String(reference.digestValue ?? "—") });

        const target = uri.startsWith("#") ? uri.slice(1) : uri;
        if (target !== "" && !ids.includes(target))
            problems.push(`The signature covers "${uri}", and no element in this message carries `
                        + `Id "${target}"`
                        + (ids.length > 0 ? ` (present: ${ids.join(", ")}).` : "."));
    }

    facts.push({ label: "Signature", value: String(signature.signatureValue?.value ?? "—") });

    return {
        present: true,
        facts,
        problems,
        limits: [
            "Not verified here. The digest can be re-derived without any key — re-encode the covered "
          + "element as canonical EXI and SHA-256 it — but that needs the codec's fragment encoder, "
          + "which this screen does not have.",
            "Verifying the signature itself needs the signer's public key, which arrives earlier in "
          + "the session inside PaymentDetailsReq.",
        ],
    };
}

/**
 * Every `Id` in a message, at any depth — the set a signature reference can legally point into.
 *
 * @param {any} node
 * @param {string[]} [found]
 * @returns {string[]}
 */
function idsIn(node, found = []) {

    if (node === null || typeof node !== "object") return found;

    if (Array.isArray(node)) {
        for (const item of node) idsIn(item, found);
        return found;
    }

    // `id` is the JSON-LD spelling of the schema's `Id` attribute; the header's own `signature`
    // subtree is skipped, since a reference pointing into it would not be covering the message.
    if (typeof node.id === "string" && !found.includes(node.id)) found.push(node.id);

    for (const [key, value] of Object.entries(node))
        if (key !== "signature") idsIn(value, found);

    return found;
}


/**
 * @typedef {object} ExportFile
 * @property {string} name
 * @property {string} mime
 * @property {string} text
 */

/**
 * @typedef {object} Exports
 * @property {ExportFile[]} files
 * @property {string[]} caveats
 */

/**
 * The session, as files to take away.
 *
 * Two of them, and the second is the interesting one. `events.json` is the record as it arrived. The
 * `.trace.json` is the **corpus shape** — the same `Session.*.trace.json` layout the C# recorder
 * writes and the Kotlin, Swift and TypeScript suites replay — so a session that did something
 * surprising in the field can become a fixture the whole project is held to, without anyone
 * re-recording it.
 *
 * The caveats are part of the export, not a footnote to it. A trace built from an event stream is
 * missing exactly what the event stream does not carry, and a file that is silently not corpus-grade
 * is worse than one that says so at the top.
 *
 * @param {BridgeEvent[]} events
 * @param {string} name
 * @returns {Exports}
 */
export function exportsFor(events, name) {

    const started  = events.find(e => e.kind === "sessionStarted");
    const messages = events.filter(e => e.kind === "message");

    /** @type {any[]} */
    const exchanges = [];
    /** @type {string[]} */
    const caveats = [];

    for (const event of messages) {

        const frame = { payloadType: String(event.payloadType ?? ""),
                        message:     String(event.messageName ?? ""),
                        frame:       String(event.exi ?? "") };

        if (event.direction === "out")
            exchanges.push({ index: exchanges.length, request: frame, response: null });
        else if (exchanges.length > 0 && exchanges[exchanges.length - 1].response === null)
            exchanges[exchanges.length - 1].response = frame;
        else
            caveats.push(`Exchange ${exchanges.length}: a response arrived with no request before it, `
                       + "so the pairing below is not the session's.");
    }

    if (exchanges.some(e => e.response === null))
        caveats.push("At least one request has no response — the session did not end cleanly, and a "
                   + "strict replay will stop there.");

    if (events.some(e => e.json?.header?.signature !== undefined && e.json?.header?.signature !== null))
        caveats.push("This session carries signed messages. The corpus records each signature "
                   + "separately so a replay can substitute it before comparing bytes; an event "
                   + "stream does not carry that, so this file is not corpus-grade as it stands.");

    if (statusOf(events).lost > 0)
        caveats.push("Events were lost on the way to this screen, so the trace has holes.");

    const trace = {
        schemaVersion: 2,
        name,
        protocol: String(started?.protocol ?? ""),
        mode:     String(started?.mode ?? ""),
        note:     "Exported from the session inspector, from the bridge's event stream. "
                + (caveats.length > 0 ? "Read the caveats before using it as a fixture." : ""),
        exchanges,
    };

    return {
        files: [
            { name: `${name}.events.json`, mime: "application/json",
              text: JSON.stringify(events, null, 2) },
            { name: `Session.${name}.trace.json`, mime: "application/json",
              text: JSON.stringify(trace, null, 2) },
        ],
        caveats,
    };
}


/**
 * Whether the session is over, and how.
 *
 * @param {BridgeEvent[]} events
 * @returns {{running: boolean, outcome: string | null, lost: number}}
 */
export function statusOf(events) {

    const finished = events.find(e => e.kind === "sessionFinished");
    const lost     = rowsFor(events).filter(r => r.tone === "gap").length;

    return {
        running: finished === undefined,
        outcome: finished === undefined ? null : String(finished.outcome),
        lost,
    };
}
