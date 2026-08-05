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


/**
 * Every recorded session, because this build now decodes every set they travel on.
 *
 * Read from the corpus rather than listed: a hand-kept list is what let `iso2-ac-eim-meter` and the
 * `-sapboth` pair sit unchecked while three names were, and the meter sessions were the ones with a
 * field this port did not emit.
 */
const DECODABLE = Object.keys(sessions);


test("every recorded session produces exactly the events C# produces", { skip: skipNoTraces }, () => {

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

    // Every session in the corpus, not a subset of it: a floor that a shrinking DECODABLE could slip
    // under is the failure this number exists to prevent, and it was 80 while three sessions ran.
    assert.equal(DECODABLE.length, Object.keys(sessions).length);
    assert.ok(checked >= 380, `only ${checked} events were compared`);
});


/**
 * A -20 session decodes on every set it travels on, and each frame under its own payload type.
 *
 * This test replaces the one that stood here until 2026-08-05, which asserted the opposite: that a
 * -20 frame became a named error, because the sets were not generated for TypeScript. Its own
 * comment said it would fail on the day they were wired through, and it did.
 *
 * What it checks now is the property that made the old one necessary — **a -20 session is not
 * silently half-read.** All three sets appear (CommonMessages carries the session's spine, AC or DC
 * the energy-transfer half), nothing falls back to an error, and the session ends `completed`.
 */
test("an ISO 15118-20 session decodes on all three sets, each under its own payload type", { skip: skipNoTraces }, () => {

    for (const [name, energySet] of [["iso20-ac-eim", "0x8003"], ["iso20-dc-eim", "0x8004"]] as const) {

        const produced = replay(trace(name), steppingClock());

        const errors   = produced.filter(e => e.kind === "error");
        const messages = produced.filter(e => e.kind === "message");

        assert.equal(errors.length, 0, `${name}: ${errors[0]?.detail}`);

        // The handshake still arrives on 0x8001 — the payload type it shares with every -2 message,
        // because it happens before a protocol has been agreed and cannot have one of its own.
        assert.equal(messages.filter(m => m.payloadType === "0x8001").length, 2,
                     `${name}: the SupportedAppProtocol exchange`);

        // And the session's own frames on the two -20 sets it uses, never on the other energy set.
        assert.ok(messages.some(m => m.payloadType === "0x8002"), `${name}: no CommonMessages frame`);
        assert.ok(messages.some(m => m.payloadType === energySet), `${name}: no ${energySet} frame`);

        const sets = new Set(messages.map(m => m.payloadType));
        assert.deepEqual([...sets].sort(), ["0x8001", "0x8002", energySet].sort(), `${name}: sets`);

        const last = produced[produced.length - 1]!;
        assert.equal(last.kind, "sessionFinished");
        assert.equal((last as { outcome: string }).outcome, "completed", `${name}: outcome`);
    }
});


/**
 * A set this build does not carry is still a named error, with the frame attached.
 *
 * The refusal path did not stop being reachable when -20 was wired through — WPT (`0x8005`) and ACDP
 * (`0x8006`) are deliberately not bundled (see `src/bridge/replay.ts`), and a station that sent one
 * would arrive here. Synthetic, because no recorded session contains such a frame: that is exactly
 * why the path needs a test of its own rather than a corpus entry.
 */
test("a frame from a set this build does not carry becomes a named error carrying the frame", { skip: skipNoTraces }, () => {

    const wpt = trace("iso20-ac-eim") as SessionTrace;
    const anyFrame = wpt.exchanges.find(e => e.request)!.request!;

    const produced = replay({
        ...wpt,
        name: "synthetic-wpt",
        exchanges: [{ request: { ...anyFrame, payloadType: "0x8005", message: "WPT_FinePositioningReq" } }],
    }, steppingClock());

    const error = produced.find(e => e.kind === "error")!;

    // The wording is C#'s, character for character: a consumer must not be able to tell from an
    // event which back end produced the session.
    assert.match(error.detail,
                 /^WPT_FinePositioningReq \(0x8005\) could not be read: payload type '0x8005' is not a message set this build carries\.$/);

    // The frame travels with the error, because a decode failure whose bytes are not in the stream
    // is a report nobody can act on.
    assert.equal(error.exi, anyFrame.frame.toLowerCase());

    // And the stream still ends, marked failed. A consumer that received no ending would wait for
    // one for ever, which is the one outcome worse than a session of errors.
    const last = produced[produced.length - 1]!;
    assert.equal(last.kind, "sessionFinished");
    assert.equal((last as { outcome: string }).outcome, "failed");
});


/**
 * The signing meter's key rides the first event, and only when there is one.
 *
 * Absent from this port until 2026-08-05, and invisible because `DECODABLE` was a hand-kept list of
 * three sessions that did not include either meter recording. The other three back ends had it.
 */
test("a session with a signing meter carries the meter key, and one without carries none", { skip: skipNoTraces }, () => {

    const withMeter = replay(trace("iso2-ac-eim-meter"), steppingClock())[0] as
        { meterKey?: { x: string; y: string } };

    assert.ok(withMeter.meterKey, "the meter session's first event has no meterKey");
    assert.match(withMeter.meterKey.x, /^[0-9a-f]{64}$/);
    assert.match(withMeter.meterKey.y, /^[0-9a-f]{64}$/);

    // Absent rather than null or empty: the corpus's events have no such key at all, and a consumer
    // reading `"meterKey" in event` must get the same answer from every back end.
    const without = replay(trace("iso2-ac-eim"), steppingClock())[0]!;
    assert.ok(!("meterKey" in without), "a session without a signing meter announced a key");
});


test("the clock is read once before the first event and once per event", { skip: skipNoTraces }, () => {

    let reads = 0;
    const counting = () => { reads++; return 0; };

    const events = replay(trace("iso2-ac-eim"), counting);

    // The coupling the corpus depends on. Kotlin's and Swift's ports hold the same shape, which is
    // why all four agree on `atMillis` without ever having agreed on a clock.
    assert.equal(reads, events.length + 1);
});
