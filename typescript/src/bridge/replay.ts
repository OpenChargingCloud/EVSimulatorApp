import type { JsonObject } from "../runtime/index.ts";
import type { BridgeEvent } from "./events.ts";

import { SupportedAppProtocolCodec } from "../appprotocol/SupportedAppProtocolCodec.ts";
import { SupportedAppProtocolCodecJson } from "../appprotocol/SupportedAppProtocolCodecJson.Json.ts";
import { Iso15118_2Codec } from "../iso2/Iso15118_2Codec.ts";
import { Iso15118_2CodecJson } from "../iso2/Iso15118_2CodecJson.Json.ts";

/**
 * A recorded session, as the event stream a WebView receives.
 *
 * The fourth port of C#'s `SessionEventStream`, next to Kotlin's and Swift's, and held to the same
 * corpus: `replayTests` feeds it the recorded traces and requires exactly what
 * `Vectors/Bridge.events.json` pins, event for event.
 *
 * ## What this back end can and cannot decode
 *
 * The generator has emitted the SupportedAppProtocol and ISO 15118-2 codecs for TypeScript and not
 * yet the -20 sets, so a -20 trace replays as **error events naming the payload type** — which is
 * what every back end does with a frame it cannot place, and is why nothing here special-cases it.
 * Refusing a -20 session before it starts is the *plugin's* job (`capacitor/src/web.ts`), because
 * that is knowable in advance and a stream of errors is not a session.
 *
 * @module
 */

/** The V2GTP header: version, payload type, and the payload's length. */
export const V2GTP_HEADER_BYTES = 8;


/**
 * A recorded frame, as the traces under `Vectors/Session.*.trace.json` carry it.
 *
 * @typedef {object} RecordedFrame
 */
export interface RecordedFrame {
    readonly payloadType: string;
    readonly message: string;
    readonly frame: string;
}

export interface RecordedExchange {
    readonly request?: RecordedFrame | null;
    readonly response?: RecordedFrame | null;
}

export interface SessionTrace {
    readonly name: string;
    readonly protocol: string;
    readonly mode: string;
    readonly exchanges: readonly RecordedExchange[];
}


/**
 * Decodes a complete V2GTP frame and returns the message as JSON-LD.
 *
 * **The payload type is not enough, and that is a fact about ISO 15118 rather than about this code.**
 * `0x8001` carries both the SupportedAppProtocol handshake and every ISO 15118-2 message — the
 * handshake happens before a protocol has been agreed, so it cannot have a payload type of its own.
 * Here the message's own name resolves it, because the events are built from a record of the session
 * rather than from a live socket.
 */
export function toJSONLD(frame: Uint8Array, payloadType: string, messageName: string): JsonObject {

    if (frame.length <= V2GTP_HEADER_BYTES)
        throw new Error(`a V2GTP frame is longer than its ${V2GTP_HEADER_BYTES}-byte header.`);

    const payload = frame.subarray(V2GTP_HEADER_BYTES);

    if (payloadType === "0x8001")
        return messageName.startsWith("SupportedAppProtocol")
            ? SupportedAppProtocolCodecJson.toJSON(SupportedAppProtocolCodec.decodeAny(payload))
            : Iso15118_2CodecJson.toJSON(Iso15118_2Codec.decodeAny(payload));

    // The wording is C#'s, character for character: a consumer reading this in an event stream should
    // not be able to tell which back end produced the session.
    throw new Error(`payload type '${payloadType}' is not a message set this build carries.`);
}


/**
 * Turns a recorded session into the event stream the bridge emits (`docs/CONCEPT.md` B1).
 *
 * The clock is injected, and **the ports have to read it in the same places**: once before the first
 * event and once per event. The corpus is generated with a clock that steps by one millisecond per
 * reading, so a port that read it a different number of times would produce the same events with
 * different timings — which is exactly the kind of divergence the corpus exists to catch.
 */
export function replay(trace: SessionTrace, monotonicMillis: () => number): BridgeEvent[] {

    const events: BridgeEvent[] = [];

    const start = monotonicMillis();
    let seq = 0;
    let failed = false;

    events.push({
        seq:      seq++,
        atMillis: monotonicMillis() - start,
        kind:     "sessionStarted",
        name:     trace.name,
        protocol: trace.protocol,
        mode:     trace.mode,
    });

    for (const exchange of trace.exchanges) {
        for (const [side, direction] of [["request", "out"], ["response", "in"]] as const) {

            const frame = exchange[side];
            if (frame === undefined || frame === null) continue;

            const event = describe(frame, direction, seq++, monotonicMillis() - start);

            if (event.kind === "error") failed = true;
            events.push(event);
        }
    }

    events.push({
        seq:       seq,
        atMillis:  monotonicMillis() - start,
        kind:      "sessionFinished",
        exchanges: trace.exchanges.length,
        outcome:   failed ? "failed" : "completed",
    });

    return events;
}


/** One recorded frame as an event — the message twice over, or an error naming the frame. */
function describe(frame: RecordedFrame, direction: "out" | "in",
                  seq: number, atMillis: number): BridgeEvent {

    const hex = frame.frame.toLowerCase();

    try {
        return {
            seq,
            atMillis,
            kind:        "message",
            direction,
            payloadType: frame.payloadType,
            messageName: frame.message,
            exi:         hex,
            json:        toJSONLD(parseHex(hex), frame.payloadType, frame.message),
        };
    } catch (problem) {
        // The frame goes out with the error. A decode failure whose bytes are not in the stream is a
        // report nobody can act on.
        return {
            seq,
            atMillis,
            kind:   "error",
            detail: `${frame.message} (${frame.payloadType}) could not be read: `
                  + (problem instanceof Error ? problem.message : String(problem)),
            exi:    hex,
        };
    }
}


export function parseHex(hex: string): Uint8Array {
    const bytes = hex.match(/../g) ?? [];
    return new Uint8Array(bytes.map(b => parseInt(b, 16)));
}


/**
 * A clock that advances a fixed amount per reading.
 *
 * The corpus would not be a corpus otherwise, and a live replay gets the real one. A port of C#'s
 * `SteppingClock` and Kotlin's, down to the step.
 */
export function steppingClock(stepMillis = 1): () => number {
    let now = 0;
    return () => { const value = now; now += stepMillis; return value; };
}
