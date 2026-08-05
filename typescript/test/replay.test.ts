import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { replay, steppingClock, type SessionTrace } from "../src/bridge/replay.ts";

/**
 * This back end's event stream, against the one C# produces.
 *
 * The fourth port of `SessionEventStream`, held to `Vectors/Bridge.events.json` as text — the same
 * check the Kotlin and Swift ports pass, reached from the language the events are actually consumed
 * in. Timings included: the clock is stepped, and a port that read it in different places would
 * produce the same events with different `atMillis` and fail at the second one.
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

const read = (path: string) => JSON.parse(readFileSync(join(repositoryRoot, path), "utf8"));

const sessions: Record<string, unknown[]> =
    read("bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json").sessions;

// The session trace corpus is the ISO15118ConformanceTests repo's — it lives with the C# session
// tests that record it, in the parent that carries this app as a submodule. These C#-vs-port replay
// checks therefore run under conformance and skip when the app is checked out on its own.
const trace = (name: string): SessionTrace =>
    read(`../../ISO15118ConformanceTests.Simulation/Vectors/Session.${name}.trace.json`);

const skipNoTraces = (() => {
    try { trace("iso2-ac-eim"); return false; }
    catch { return "session trace corpus absent — it lives in the ISO15118ConformanceTests repo"; }
})();


/** The sessions this build's codecs cover: SupportedAppProtocol and ISO 15118-2. */
const DECODABLE = ["iso2-ac-eim", "iso2-ac-pnc", "iso2-dc-eim"];


test("every ISO 15118-2 session produces exactly the events C# produces", { skip: skipNoTraces }, () => {

    let checked = 0;

    for (const name of DECODABLE) {

        const produced = replay(trace(name), steppingClock());
        const expected = sessions[name]!;

        assert.equal(produced.length, expected.length, `${name}: event count`);

        for (let i = 0; i < produced.length; i++) {
            // As text, in the corpus's own order: the JSON-LD documents are pinned that way across
            // four back ends, and deepEqual would forgive a reordering that a consumer would not.
            assert.equal(JSON.stringify(produced[i]), JSON.stringify(expected[i]), `${name}/${i}`);
            checked++;
        }
    }

    assert.ok(checked >= 80, `only ${checked} events were compared`);
});


/**
 * A -20 session replays as error events naming the payload type, and says so.
 *
 * The generator has emitted the SAP and -2 codecs for TypeScript and not yet the -20 sets. That is
 * not hidden: a frame this build cannot place becomes an error carrying the frame, which is what
 * every back end does — and the wording is C#'s, character for character, so a consumer cannot tell
 * from an event which back end produced the session.
 *
 * When the -20 codecs are generated, this test fails, and the three sessions move into DECODABLE.
 */
test("an ISO 15118-20 session is not silently wrong — every -20 frame becomes a named error", { skip: skipNoTraces }, () => {

    const produced = replay(trace("iso20-ac-eim"), steppingClock());

    const errors = produced.filter(e => e.kind === "error");
    const messages = produced.filter(e => e.kind === "message");

    // The handshake decodes. A -20 session opens with SupportedAppProtocol on 0x8001 — the payload
    // type it shares with every -2 message — and that codec *is* generated for TypeScript. So "a -20
    // session" is not "no readable frames", and an earlier draft of this test asserting zero messages
    // was wrong about the protocol rather than about the code.
    assert.equal(messages.length, 2, "the SupportedAppProtocol exchange should still decode");
    assert.ok(messages.every(m => m.payloadType === "0x8001"));

    assert.ok(errors.length >= 20, `only ${errors.length} errors`);
    assert.ok(errors.every(e => /0x800[234]/.test(e.detail)), "an error named something other than a -20 set");

    assert.match(errors[0]!.detail,
                 /^SessionSetupReq \(0x8002\) could not be read: payload type '0x8002' is not a message set this build carries\.$/);

    // And the stream still ends, marked failed. A consumer that received no ending would wait for
    // one for ever, which is the one outcome worse than a session of errors.
    const last = produced[produced.length - 1]!;
    assert.equal(last.kind, "sessionFinished");
    assert.equal((last as { outcome: string }).outcome, "failed");

    // The frame travels with the error, because a decode failure whose bytes are not in the stream
    // is a report nobody can act on.
    assert.ok(errors[0]!.exi!.length > 16);
});


test("the clock is read once before the first event and once per event", { skip: skipNoTraces }, () => {

    let reads = 0;
    const counting = () => { reads++; return 0; };

    const events = replay(trace("iso2-ac-eim"), counting);

    // The coupling the corpus depends on. Kotlin's and Swift's ports hold the same shape, which is
    // why all four agree on `atMillis` without ever having agreed on a clock.
    assert.equal(reads, events.length + 1);
});
