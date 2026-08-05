import type { JsonObject } from "../runtime/index.ts";
import type { BridgeEvent } from "./events.ts";

import { SupportedAppProtocolCodec } from "../appprotocol/SupportedAppProtocolCodec.ts";
import { SupportedAppProtocolCodecJson } from "../appprotocol/SupportedAppProtocolCodecJson.Json.ts";
import { Iso15118_2Codec } from "../iso2/Iso15118_2Codec.ts";
import { Iso15118_2CodecJson } from "../iso2/Iso15118_2CodecJson.Json.ts";
import { CommonMessagesCodec } from "../iso20common/CommonMessagesCodec.ts";
import { CommonMessagesCodecJson } from "../iso20common/CommonMessagesCodecJson.Json.ts";
import { ACCodec } from "../iso20ac/ACCodec.ts";
import { ACCodecJson } from "../iso20ac/ACCodecJson.Json.ts";
import { DCCodec } from "../iso20dc/DCCodec.ts";
import { DCCodecJson } from "../iso20dc/DCCodecJson.Json.ts";

/**
 * A recorded session, as the event stream a WebView receives.
 *
 * The fourth port of C#'s `SessionEventStream`, next to Kotlin's and Swift's, and held to the same
 * corpus: `replayTests` feeds it the recorded traces and requires exactly what
 * `Vectors/Bridge.events.json` pins, event for event.
 *
 * ## What this back end can and cannot decode
 *
 * Both protocols, since 2026-08-05: SupportedAppProtocol, ISO 15118-2, and the three -20 sets a
 * session actually travels on — CommonMessages, AC and DC. WPT and ACDP are generated for the other
 * back ends and not for this one, deliberately: no stack anywhere implements a WPT or ACDP *session*
 * (see `docs/roadmap.md`), so no recorded session contains one, and half a megabyte of WebView bundle
 * per set would answer a question nobody asks. The same goes for the two AC DER grammar variants,
 * which have no payload type of their own to arrive under.
 *
 * A frame from a set this build does not carry still becomes an **error event naming the payload
 * type** — what every back end does with a frame it cannot place, and why nothing here special-cases
 * it.
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
    /** The station meter's signing key, present only in the sessions that sign meter readings. */
    readonly meterKey?: { readonly x: string; readonly y: string } | null;
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

    // The -20 sets, where the payload type *is* enough: each namespace has one, and a session's
    // frames arrive under the set they belong to. The order is C#'s `MessageSetCodecs`.
    if (payloadType === "0x8002")
        return CommonMessagesCodecJson.toJSON(CommonMessagesCodec.decodeAny(payload));

    if (payloadType === "0x8003")
        return ACCodecJson.toJSON(ACCodec.decodeAny(payload));

    if (payloadType === "0x8004")
        return DCCodecJson.toJSON(DCCodec.decodeAny(payload));

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
        // Only when the trace carries one, and spread rather than set to undefined: the corpus's
        // events have no `meterKey` key at all for a session without a signing meter, and
        // `JSON.stringify` of an explicit `undefined` drops it — but `deepEqual` would not.
        ...(trace.meterKey ? { meterKey: { x: trace.meterKey.x, y: trace.meterKey.y } } : {}),
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
