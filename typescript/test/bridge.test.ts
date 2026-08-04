import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { parseBridgeEvent, type BridgeEvent, type MessageEvent } from "../src/bridge/events.ts";
import { SupportedAppProtocolCodec } from "../src/appprotocol/SupportedAppProtocolCodec.ts";
import { SupportedAppProtocolCodecJson } from "../src/appprotocol/SupportedAppProtocolCodecJson.Json.ts";
import { Iso15118_2Codec } from "../src/iso2/Iso15118_2Codec.ts";
import { Iso15118_2CodecJson } from "../src/iso2/Iso15118_2CodecJson.Json.ts";

/**
 * The bridge's event stream, read by the side that will actually read it.
 *
 * The C# tests check that each event's two halves agree, using the producer's own codecs. This
 * checks the same property from the **consumer's** side, in the consumer's language, and it is the
 * stronger of the two: a consumer that can decode the frame in an event and get back that event's
 * JSON-LD needs to trust nothing about where the event came from. That is the whole reason B1 puts
 * the raw frame in the stream next to the readable form.
 *
 * The corpus is the one C# generates from the recorded sessions, so this is also a fifth check that
 * the four back ends agree — reached from a direction none of the others take.
 */

const repositoryRoot = (() => {
    let directory = dirname(fileURLToPath(import.meta.url));
    for (;;) {
        try { readFileSync(join(directory, "EVSimulatorApp.slnx")); return directory; }
        catch { /* keep walking */ }
        const parent = dirname(directory);
        if (parent === directory) throw new Error("repository root not found");
        directory = parent;
    }
})();

const sessions: Record<string, unknown[]> = JSON.parse(readFileSync(
    join(repositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json"),
    "utf8")).sessions;

const parseHex = (text: string) =>
    new Uint8Array((text.match(/../g) ?? []).map(b => parseInt(b, 16)));

/** The V2GTP header — version, payload type, length — ahead of the EXI document. */
const V2GTP_HEADER_BYTES = 8;


/** The message set a frame belongs to. `0x8001` carries both SAP and every ISO 15118-2 message. */
function codecFor(event: MessageEvent) {
    if (event.payloadType === "0x8001") {
        return event.messageName.startsWith("SupportedAppProtocol")
            ? { codec: SupportedAppProtocolCodec, json: SupportedAppProtocolCodecJson }
            : { codec: Iso15118_2Codec, json: Iso15118_2CodecJson };
    }
    return null;   // the -20 sets are not generated for TypeScript yet
}


test("every event in the corpus parses as a bridge event", () => {

    let count = 0;

    for (const [name, events] of Object.entries(sessions)) {

        let expectedSeq = 0;

        for (const raw of events) {
            const event: BridgeEvent = parseBridgeEvent(raw);

            // The sequence is the whole point of `seq`: a consumer detects lost events by the gap,
            // so a stream whose numbering is merely present rather than consecutive would make that
            // detection silently useless.
            assert.equal(event.seq, expectedSeq++, `${name}: the sequence has a gap`);
            count++;
        }

        const last = parseBridgeEvent(events[events.length - 1]);
        assert.equal(last.kind, "sessionFinished", `${name}: the stream does not end with the session`);
    }

    assert.ok(count >= 150, `only ${count} events were read`);
});


test("an event's JSON-LD is what its own frame decodes to", () => {

    let checked = 0;

    for (const [name, events] of Object.entries(sessions)) {
        for (const raw of events) {

            const event = parseBridgeEvent(raw);
            if (event.kind !== "message") continue;

            const set = codecFor(event);
            if (set === null) continue;

            const payload = parseHex(event.exi).slice(V2GTP_HEADER_BYTES);
            const decoded = set.codec.decodeAny(payload);

            assert.equal(JSON.stringify(set.json.toJSON(decoded)), JSON.stringify(event.json),
                         `${name}/${event.seq}: the event's JSON-LD is not what its own frame decodes to`);
            checked++;
        }
    }

    assert.ok(checked >= 50, `only ${checked} messages were self-checked`);
});


test("a malformed event is refused, and says which one and why", () => {

    const good = sessions["iso2-ac-eim"][1] as Record<string, unknown>;

    assert.throws(() => parseBridgeEvent({ ...good, direction: "sideways" }), /direction is 'sideways'/);
    assert.throws(() => parseBridgeEvent({ ...good, kind: "runSession" }),    /unknown kind 'runSession'/);
    assert.throws(() => parseBridgeEvent({ ...good, json: "not an object" }), /'json' is not a JSON-LD object/);
    assert.throws(() => parseBridgeEvent({ ...good, seq: 1.5 }),              /integer 'seq'/);
    assert.throws(() => parseBridgeEvent([]),                                 /a JSON object/);
});


/**
 * No event kind is a command. The stream is what makes a WebView safe to hand this to, and a
 * command-shaped event would be the thing nobody notices arriving.
 */
test("the stream carries observations, never commands", () => {

    const kinds = new Set(Object.values(sessions).flat().map(e => (e as { kind: string }).kind));

    assert.deepEqual([...kinds].sort(), ["message", "sessionFinished", "sessionStarted"]);
});
