import type { JsonObject } from "../runtime/index.ts";

/**
 * What the bridge sends out while a session runs (`docs/CONCEPT.md` B1).
 *
 * ## The stream is a record, never a channel
 *
 * No event asks the native side to do anything. That is what makes it safe to hand to a WebView: a
 * page holding the stream can display, diff and export, and cannot drive a charging session by
 * replying to it. Commands go the other way, through {@link EvSimulatorPlugin}, where there are
 * exactly three and each is named.
 *
 * ## Every message twice
 *
 * A {@link MessageEvent} carries the message as JSON-LD *and* as the raw V2GTP frame. Each half is
 * checked on its own elsewhere — the codecs against cbV2G's bytes, the JSON-LD against the shared
 * document corpus — but only having both makes a single event **self-checkable**: decode the frame,
 * serialise it, compare. A consumer can do that without trusting the sender, and
 * `bridge.test.ts` does exactly that for every message of every recorded session.
 */
export type BridgeEvent = SessionStartedEvent | MessageEvent | SessionFinishedEvent | ErrorEvent;

interface BridgeEventBase {
    /** Position in the stream, from 0. A consumer that sees a gap has lost events. */
    readonly seq: number;

    /**
     * Milliseconds since the session started, from a monotonic clock.
     *
     * Relative and monotonic rather than wall-clock: a wall clock can step backwards over NTP or a
     * timezone change, and would then show a response arriving before its request.
     */
    readonly atMillis: number;
}

export interface SessionStartedEvent extends BridgeEventBase {
    readonly kind: "sessionStarted";
    readonly name: string;
    /** `iso15118-2` or `iso15118-20`. */
    readonly protocol: string;
    /** `ac` or `dc`. */
    readonly mode: string;
    /**
     * The station meter's public key, as the two P-256 field elements in hex — absent unless the
     * session carries signed meter readings.
     *
     * Once, at the head of the stream, rather than beside every reading: it is a property of the
     * station, and repeating it per message would invite a reader to check each reading against the
     * key that arrived with it — which is no check at all.
     */
    readonly meterKey?: { readonly x: string; readonly y: string };
}

export interface MessageEvent extends BridgeEventBase {
    readonly kind: "message";
    /** `out` for a message this EV sent, `in` for one it received. */
    readonly direction: "out" | "in";
    /** The V2GTP payload type, e.g. `0x8001` — which message set the frame is in. */
    readonly payloadType: string;
    readonly messageName: string;
    /** The complete V2GTP frame, header included, as lower-case hex. */
    readonly exi: string;
    /** The same message as JSON-LD — the generated form, not a summary of it. */
    readonly json: JsonObject;
}

export interface SessionFinishedEvent extends BridgeEventBase {
    readonly kind: "sessionFinished";
    readonly exchanges: number;
    /** `completed`, or `failed` when an error event preceded this one. */
    readonly outcome: "completed" | "failed";
}

export interface ErrorEvent extends BridgeEventBase {
    readonly kind: "error";
    readonly detail: string;
    /** The frame being processed when this happened, if there was one. */
    readonly exi?: string;
}


/**
 * Reads an event that arrived over the bridge, refusing anything that is not one.
 *
 * Everything crossing a bridge is untrusted by the same reasoning as a scanned QR code: the native
 * side is ours today, and a stream replayed from a file, forwarded by a proxy or produced by an
 * older build is not. A consumer that trusted the shape would fail somewhere far from the cause.
 */
export function parseBridgeEvent(value: unknown): BridgeEvent {

    if (typeof value !== "object" || value === null || Array.isArray(value)) {
        throw new TypeError("a bridge event is a JSON object.");
    }

    const event = value as Record<string, unknown>;

    if (typeof event.seq !== "number" || !Number.isSafeInteger(event.seq)) {
        throw new TypeError("a bridge event needs an integer 'seq'.");
    }
    if (typeof event.atMillis !== "number" || !Number.isSafeInteger(event.atMillis)) {
        throw new TypeError(`event ${event.seq} needs an integer 'atMillis'.`);
    }

    switch (event.kind) {

        case "sessionStarted":
            require(event, ["name", "protocol", "mode"], "sessionStarted");
            return event as unknown as SessionStartedEvent;

        case "message":
            require(event, ["direction", "payloadType", "messageName", "exi"], "message");
            if (event.direction !== "out" && event.direction !== "in") {
                throw new TypeError(`event ${event.seq}: direction is '${event.direction}'.`);
            }
            if (typeof event.json !== "object" || event.json === null || Array.isArray(event.json)) {
                throw new TypeError(`event ${event.seq}: 'json' is not a JSON-LD object.`);
            }
            return event as unknown as MessageEvent;

        case "sessionFinished":
            if (typeof event.exchanges !== "number") {
                throw new TypeError(`event ${event.seq}: 'exchanges' is not a number.`);
            }
            if (event.outcome !== "completed" && event.outcome !== "failed") {
                throw new TypeError(`event ${event.seq}: outcome is '${event.outcome}'.`);
            }
            return event as unknown as SessionFinishedEvent;

        case "error":
            require(event, ["detail"], "error");
            return event as unknown as ErrorEvent;

        default:
            throw new TypeError(`event ${event.seq}: unknown kind '${String(event.kind)}'.`);
    }
}

function require(event: Record<string, unknown>, keys: string[], kind: string): void {
    for (const key of keys) {
        if (typeof event[key] !== "string") {
            throw new TypeError(`event ${event.seq}: a ${kind} event needs a string '${key}'.`);
        }
    }
}
