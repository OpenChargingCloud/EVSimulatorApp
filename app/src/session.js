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
        meter:     meterReadingFor(event),
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
 * @typedef {object} DigestCheck
 * @property {"match" | "mismatch" | "unchecked"} verdict
 * @property {string | null} claimed   the digest the message carries
 * @property {string | null} derived   the digest its own frame produces
 * @property {string} explanation
 */

/**
 * The digest, re-derived from the message's own frame.
 *
 * The one part of a signature that can be checked **with no key at all**: re-encode the covered
 * element as canonical EXI, hash it, compare. A match says the signature covers *this content* —
 * that nobody altered the message under a signature that still parses. It says nothing about who
 * signed, which needs the contract certificate and is a separate sentence on screen.
 *
 * The deriver is injected rather than imported, and that is the whole reason this stays here: the
 * codec is TypeScript and lives in the bundle, while every other source in `app/` is a plain ES
 * module that Node runs for the tests and a browser runs without a build. So the decision — what
 * counts as a match, and what an absent answer means — is testable on a laptop, and only the
 * *computation* needs the bundle.
 *
 * A build without the bundle, a -20 message, or a body this codec has no fragment encoder for all
 * give the same answer: `unchecked`. That is deliberately distinct from `mismatch`. A screen that
 * showed "not verified" and "wrong" the same way would be worse than one that showed neither.
 *
 * @param {BridgeEvent} event
 * @param {null | ((frameHex: string) => Promise<string | null>)} deriveDigest
 * @returns {Promise<DigestCheck>}
 */
export async function digestCheckFor(event, deriveDigest) {

    const view = signatureFor(event);

    if (!view.present)
        return { verdict: "unchecked", claimed: null, derived: null,
                 explanation: "This message carries no signature." };

    const reference = event.json?.header?.signature?.signedInfo?.reference;
    const first     = Array.isArray(reference) ? reference[0] : reference;
    const claimed   = first?.digestValue === undefined ? null : String(first.digestValue).toLowerCase();

    if (deriveDigest === null || typeof event.exi !== "string")
        return { verdict: "unchecked", claimed, derived: null,
                 explanation: "Not checked: this build cannot re-encode the covered element." };

    const derived = await deriveDigest(event.exi);

    if (derived === null)
        return { verdict: "unchecked", claimed, derived: null,
                 explanation: "Not checked: this build has no fragment encoder for this message. "
                            + "The TypeScript codec covers ISO 15118-2 only." };

    if (claimed === null)
        return { verdict: "unchecked", claimed, derived,
                 explanation: "The signature declares no digest to compare against." };

    return derived === claimed
        ? { verdict: "match", claimed, derived,
            explanation: "The digest matches: this signature covers exactly the content in this "
                       + "message. Who signed it is a separate question — that needs the contract "
                       + "certificate, and is not checked here." }
        : { verdict: "mismatch", claimed, derived,
            explanation: "The digest does not match. The signature does not cover the content that "
                       + "arrived — either the message was altered after signing, or the signature "
                       + "belongs to something else." };
}


/**
 * @typedef {object} SignerCheck
 * @property {"signed-by-contract" | "wrong-signer" | "unchecked"} verdict
 * @property {string} explanation
 */

/**
 * Who signed — the other half, and the one that needs a key.
 *
 * The digest says a signature covers this content. This says the signature was made with the
 * private key belonging to the **contract certificate this session presented**, which is the
 * question "is this really that customer's car" reduces to.
 *
 * The certificate is not taken from the signed message: it comes from the `PaymentDetailsReq` the EV
 * sent earlier in the same stream, which is where the station gets it from as well. That is the
 * decision this function exists to make, and it is why the whole event list is a parameter — a
 * per-event check could not reach it.
 *
 * What it deliberately does **not** claim: that the certificate is trustworthy. Whether the contract
 * chain leads to a Mobility Operator root anyone accepts is a different question again, answered by
 * `v2g-certificates` on the native side and by nothing here. A session inspector saying "signed by
 * the certificate that was presented" is exactly as much as it can honestly say.
 *
 * @param {BridgeEvent} event
 * @param {BridgeEvent[]} events  the whole stream, for the certificate
 * @param {null | ((signedFrameHex: string, certificateFrameHex: string) => Promise<boolean | null>)} verify
 * @returns {Promise<SignerCheck>}
 */
export async function signerCheckFor(event, events, verify) {

    if (!signatureFor(event).present)
        return { verdict: "unchecked", explanation: "This message carries no signature." };

    if (verify === null || typeof event.exi !== "string")
        return { verdict: "unchecked",
                 explanation: "Not checked: this build cannot verify a signature." };

    // The certificate arrives in PaymentDetailsReq, and only in a Contract session. Its absence is
    // the ordinary case for EIM and is not a fault.
    const details = events.find(e => e.kind === "message"
                                  && String(e.messageName ?? "").startsWith("PaymentDetailsReq")
                                  && typeof e.exi === "string");

    if (details === undefined)
        return { verdict: "unchecked",
                 explanation: "No contract certificate in this session — nothing to check the "
                            + "signature against. A PaymentDetailsReq carries it, and only a "
                            + "Plug & Charge session sends one." };

    const ok = await verify(event.exi, String(details.exi));

    if (ok === null)
        return { verdict: "unchecked",
                 explanation: "Not checked: this build could not re-encode the signed element or "
                            + "read the certificate's public key. The TypeScript codec covers "
                            + "ISO 15118-2 only." };

    return ok
        ? { verdict: "signed-by-contract",
            explanation: "Signed by the contract certificate this session presented. Whether that "
                       + "certificate is one anyone should trust is a separate question, and is not "
                       + "answered here." }
        : { verdict: "wrong-signer",
            explanation: "The signature was not made with the key of the contract certificate this "
                       + "session presented. Either it came from somewhere else, or the message was "
                       + "altered after signing." };
}


/**
 * @typedef {object} MeterView
 * @property {boolean} present
 * @property {string} meterId
 * @property {bigint} readingWh
 * @property {bigint | null} timestamp
 * @property {string | null} signature   raw r‖s, hex, when the meter signed
 * @property {{label: string, value: string}[]} facts
 */

/**
 * The station's meter reading, as the message carries it.
 *
 * ISO 15118-2 puts this in `MeterInfo`, and `SigMeterReading` beside it is a field almost nothing in
 * the field ever populates — 64 bytes, exactly one raw ECDSA P-256 `r‖s` pair, for the **meter** to
 * sign what it measured rather than the station asserting it. That is the Eichrecht trust model
 * carried over stock ISO 15118, and a phone that shows and checks one is the point of §4.3.
 *
 * `MeterStatus` and `TMeter` are shown when present because they are part of what was signed; a
 * screen that displayed the number without the moment it was taken would be inviting the wrong
 * comparison.
 *
 * @param {BridgeEvent} event
 * @returns {MeterView}
 */
export function meterReadingFor(event) {

    const absent = { present: false, meterId: "", readingWh: 0n, timestamp: null,
                     signature: null, facts: [] };

    const info = event.json?.body?.bodyElement?.meterInfo;
    if (info === undefined || info === null || typeof info !== "object")
        return absent;

    // Wh and seconds arrive as strings — both are unsignedLong/long in the schema, and JSON numbers
    // would quietly lose the top bits. They are also what gets signed, so parsing them wrong is the
    // one mistake that would produce a confident wrong answer.
    const readingWh = bigintOrNull(info.meterReading);
    if (typeof info.meterID !== "string" || readingWh === null)
        return absent;

    const timestamp = bigintOrNull(info.tMeter);
    const signature = typeof info.sigMeterReading === "string"
                          ? info.sigMeterReading.toLowerCase() : null;

    /** @type {{label: string, value: string}[]} */
    const facts = [
        { label: "Meter",   value: info.meterID },
        { label: "Reading", value: `${readingWh} Wh` },
    ];

    if (timestamp !== null)
        facts.push({ label: "Taken at", value: `${new Date(Number(timestamp) * 1000).toISOString()} `
                                             + `(TMeter ${timestamp})` });
    if (info.meterStatus !== undefined && info.meterStatus !== null)
        facts.push({ label: "Meter status", value: String(info.meterStatus) });

    facts.push({ label: "Meter signature", value: signature ?? "— (this station's meter does not sign)" });

    return { present: true, meterId: info.meterID, readingWh, timestamp, signature, facts };
}


/** @param {any} value @returns {bigint | null} */
function bigintOrNull(value) {
    if (typeof value !== "string" && typeof value !== "number") return null;
    try { return BigInt(value); } catch { return null; }
}


/**
 * @typedef {object} MeterCheck
 * @property {"signed-by-meter" | "wrong-meter" | "unsigned" | "unchecked"} verdict
 * @property {string} explanation
 */

/**
 * Whether the station's meter really signed the reading in this message.
 *
 * A third signer, and the one that is not the vehicle's. The digest check says a signature covers
 * this content; the signer check says the vehicle's contract key made it. This says the **station's
 * meter** vouched for the number beside it — which is the only reason to believe a reading you are
 * about to be billed for.
 *
 * ## Three deliberate choices
 *
 * **No codec.** Unlike the other two checks, everything this needs is in the decoded message:
 * meter id, reading, timestamp, and the session id from the header. So it works in a build with no
 * bundle at all, and `crypto.subtle` is used directly rather than injected — the seam in the others
 * exists because the *codec* is TypeScript, not because crypto is.
 *
 * **The values on screen, not the frame.** It verifies the JSON-LD the inspector displays rather
 * than re-decoding the EXI. Where the two could disagree, a tick earned by the frame would sit
 * beside a number taken from the JSON — and a wrong number under a green tick is the most
 * convincing way to be wrong. (`SessionEventStreamTests` pins the two halves to be one message, so
 * this costs nothing.)
 *
 * **The key comes from the session.** Which is exactly as much as this can be, and less than it
 * looks: a key handed over by the station it authenticates shows the reading was not altered on the
 * way here and is bound to this session — not that the station is who it says. That needs the key
 * out of band, from the pairing code (`docs/CONCEPT.md` §4.5), and the wording below says so.
 *
 * @param {BridgeEvent} event
 * @param {BridgeEvent[]} events  the whole stream, for the meter key
 * @returns {Promise<MeterCheck>}
 */
export async function meterCheckFor(event, events) {

    const reading = meterReadingFor(event);

    if (!reading.present)
        return { verdict: "unchecked", explanation: "This message carries no meter reading." };

    if (reading.signature === null)
        return { verdict: "unsigned",
                 explanation: "This reading is not signed. That is what almost every station in the "
                            + "field does — the field exists and is left empty — so it is worth "
                            + "noticing rather than treating as a fault." };

    const started = events.find(e => e.kind === "sessionStarted");
    const key     = started?.meterKey;

    if (key === undefined || key === null
        || typeof key.x !== "string" || typeof key.y !== "string")
        return { verdict: "unchecked",
                 explanation: "The reading is signed, and this session brought no meter key to check "
                            + "it against. 64 bytes nobody can verify is a decoration; the key "
                            + "belongs in the station's pairing code." };

    const sessionId = String(event.json?.header?.sessionID ?? "");
    if (!/^[0-9a-fA-F]*$/.test(sessionId) || sessionId.length === 0)
        return { verdict: "unchecked",
                 explanation: "This message has no session id, and the session id is part of what "
                            + "the meter signed." };

    const ok = await verifyMeterSignature(key, 2, sessionId, reading);

    if (ok === null)
        return { verdict: "unchecked",
                 explanation: "Not checked: this build has no Web Crypto, or the meter key could not "
                            + "be read." };

    return ok
        ? { verdict: "signed-by-meter",
            explanation: "The station's meter signed this reading, for this session. So the number "
                       + "above is the number the meter measured, and it was not altered on the way "
                       + "here. Whether the meter itself is the one this station should have is a "
                       + "separate question — that needs its key from the pairing code, not from the "
                       + "station." }
        : { verdict: "wrong-meter",
            explanation: "The signature does not match this reading. Either the number was changed "
                       + "after the meter signed it, or it was signed by a different meter, or it "
                       + "belongs to another session." };
}


/**
 * ISO 15118 defines the *field* and not its content, so the octets below are this project's own
 * layout — `MeterSigningPayload.cs` is where it is written down, and this is the third
 * implementation of it after Kotlin and Swift.
 *
 * Length-prefixing the meter id is the part that matters and is easy to skip: without it, meter
 * `"A1"` reading 23 and meter `"A"` reading 123 produce the same octets. The protocol byte is the
 * other: it never travels on the wire, and it is the only thing stopping a -20 reading being
 * presented as a -2 one.
 *
 * @param {{x: string, y: string}} key
 * @param {number} protocol
 * @param {string} sessionIdHex
 * @param {MeterView} reading
 * @returns {Promise<boolean | null>}
 */
async function verifyMeterSignature(key, protocol, sessionIdHex, reading) {

    const subtle = globalThis.crypto?.subtle;
    if (subtle === undefined) return null;

    const meterId   = new TextEncoder().encode(reading.meterId);
    const sessionId = bytesOfHex(sessionIdHex);
    const signature = bytesOfHex(reading.signature ?? "");

    if (meterId.length > 255 || signature.length !== 64) return null;

    const payload = new Uint8Array(12 + 1 + sessionId.length + 1 + meterId.length + 8 + 8);
    const view    = new DataView(payload.buffer);
    let at = 0;

    payload.set(new TextEncoder().encode("V2G-METER-1\0"), at); at += 12;
    payload[at++] = protocol;
    payload.set(sessionId, at); at += sessionId.length;
    payload[at++] = meterId.length;
    payload.set(meterId, at); at += meterId.length;

    view.setBigUint64(at, reading.readingWh, false); at += 8;
    // Absent is a zero rather than an omission, so the payload's length never depends on which
    // optional fields happen to be present.
    view.setBigInt64(at, reading.timestamp ?? 0n, false);

    try {
        const publicKey = await subtle.importKey(
            "jwk",
            { kty: "EC", crv: "P-256", x: base64url(bytesOfHex(key.x)), y: base64url(bytesOfHex(key.y)) },
            { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);

        // Raw r‖s, which is what the 64-byte field holds and what WebCrypto expects — the DER form
        // every other ECDSA API defaults to would not fit the field in the first place.
        return await subtle.verify({ name: "ECDSA", hash: "SHA-256" }, publicKey, signature, payload);
    } catch {
        return null;
    }
}

/** @param {string} hex @returns {Uint8Array} */
function bytesOfHex(hex) {
    return new Uint8Array((hex.match(/.{1,2}/g) ?? []).map(b => parseInt(b, 16)));
}

/** @param {Uint8Array} bytes @returns {string} */
function base64url(bytes) {
    return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
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

    // The same gap one signer along, and it lands on the *responses* — which is exactly the half a
    // trace built from an event stream is most likely to be assumed fine.
    if (events.some(e => meterReadingFor(e).signature !== null))
        caveats.push("This session carries meter-signed readings. Those 64 bytes are random per "
                   + "recording too, and the corpus records them — and the meter's public key — "
                   + "separately. Neither is in an event stream, so the readings here can be read "
                   + "but not re-verified or replayed.");

    if (statusOf(events).lost > 0)
        caveats.push("Events were lost on the way to this screen, so the trace has holes.");

    const trace = {
        schemaVersion: 3,
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
