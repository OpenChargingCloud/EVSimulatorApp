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
 * @property {string | null} json  the JSON-LD document, indented
 * @property {string | null} hex   the raw V2GTP frame, in lines of 16 bytes
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
        hex:  typeof event.exi === "string" ? hexLines(event.exi) : null,
    };
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
