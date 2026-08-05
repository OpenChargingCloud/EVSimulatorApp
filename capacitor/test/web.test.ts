import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { EvSimulatorWeb } from "../src/web.ts";
import { adapt } from "../src/index.ts";
import { parseBridgeEvent } from "@open-charging-cloud/v2g-exi/src/bridge/events.ts";
import type { SessionTrace } from "@open-charging-cloud/v2g-exi/src/bridge/replay.ts";
import type { SessionConfig } from "@open-charging-cloud/v2g-exi/src/bridge/plugin.ts";

/**
 * The plugin in a browser: what it delivers, and what it refuses.
 *
 * The events themselves are already pinned — `typescript/test/replay.test.ts` requires them to match
 * `Vectors/Bridge.events.json` character for character. What is checked here is the plugin's own
 * behaviour around them: that a session ends however it ends, that stopping is not silence, and that
 * a protocol this build cannot decode is refused rather than delivered as a stream of errors.
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

// The session trace corpus is the ISO15118ConformanceTests repo's — it lives with the C# session
// tests that record it, in the parent that carries this app as a submodule. These replay checks
// therefore run under conformance and skip when the app is checked out on its own.
const trace = (name: string): SessionTrace => JSON.parse(readFileSync(join(repositoryRoot,
    `../ISO15118ConformanceTests.Simulation/Vectors/Session.${name}.trace.json`),
    "utf8"));

const skipNoTraces = (() => {
    try { trace("iso2-ac-eim"); return false; }
    catch { return "session trace corpus absent — it lives in the ISO15118ConformanceTests repo"; }
})();

const corpus: Record<string, unknown[]> = JSON.parse(readFileSync(join(repositoryRoot,
    "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json"), "utf8")).sessions;

const CONFIG: SessionConfig = {
    host: "127.0.0.1", port: 15118, transport: "tcp",
    protocol: "iso15118-2", mode: "ac", authorization: "eim",
};


/** A plugin with one recording bundled and no waiting between events. */
function web(recorded = "iso2-ac-eim") {

    const plugin = new EvSimulatorWeb();
    plugin.traces = () => trace(recorded);
    plugin.pace   = 0;

    const received: any[] = [];
    plugin.addListener("v2gEvent", (payload: any) => received.push(JSON.parse(payload.event)));

    return { plugin, received };
}

const settle = () => new Promise(resume => setTimeout(resume, 50));


test("a bundled session arrives as the events the corpus pins", { skip: skipNoTraces }, async () => {

    const { plugin, received } = web();

    const { sessionId } = await plugin.start({ config: CONFIG });
    assert.match(sessionId, /^web-\d+$/);

    await settle();

    const expected = corpus["iso2-ac-eim"]!;
    assert.equal(received.length, expected.length);

    // Everything but the timings, which are a real clock here rather than the corpus's stepping one —
    // a live replay legitimately has its own, and `atMillis` is documented as monotonic rather than
    // reproducible. Everything else is pinned, the JSON-LD documents included.
    for (let i = 0; i < received.length; i++) {
        const { atMillis: _a, ...got }  = received[i];
        const { atMillis: _b, ...want } = expected[i] as any;
        assert.equal(JSON.stringify(got), JSON.stringify(want), `event ${i}`);
    }

    // Monotonic and integral, which is what `atMillis` actually claims — and both are stamped at
    // delivery rather than at derivation, so they describe this replay rather than how long the
    // decoding took. An inspector otherwise labels a session spread over three visible seconds
    // "0–4 ms" for every event of it.
    const times = received.map(e => e.atMillis);
    assert.deepEqual(times, [...times].sort((a, b) => a - b));
    assert.ok(times.every(t => Number.isSafeInteger(t)), `${times}`);
});


test("a protocol this build cannot decode is refused before anything starts", { skip: skipNoTraces }, async () => {

    const { plugin, received } = web("iso20-ac-eim");

    await assert.rejects(
        () => plugin.start({ config: { ...CONFIG, protocol: "iso15118-20" } }),
        /-20 codecs are not generated for TypeScript yet/);

    await settle();

    // Nothing at all, rather than a session that opens and immediately fills with errors. `replay`
    // would happily produce those — the judgement that they are not a session belongs here.
    assert.equal(received.length, 0);
});


test("a session nobody bundled a recording for is refused, and says so", async () => {

    const plugin = new EvSimulatorWeb();

    await assert.rejects(() => plugin.start({ config: CONFIG }),
                         /no recorded iso15118-2 ac session is bundled/);
});


test("stopping ends the stream rather than leaving it hanging", { skip: skipNoTraces }, async () => {

    const { plugin, received } = web();
    plugin.pace = 5;

    const { sessionId } = await plugin.start({ config: CONFIG });

    await new Promise(resume => setTimeout(resume, 30));
    await plugin.stop({ sessionId });

    await settle();

    const last = received[received.length - 1];
    const before = received[received.length - 2];

    // A consumer that received no ending would wait for one for ever, which is worse than a session
    // that failed. The vocabulary is the existing one: an error event, then `failed` — no third
    // outcome, because that would be a format change four back ends would have to agree to.
    assert.equal(last.kind, "sessionFinished");
    assert.equal(last.outcome, "failed");
    assert.equal(before.kind, "error");
    assert.match(before.detail, /stopped/);

    assert.ok(received.length < corpus["iso2-ac-eim"]!.length,
              "the session was stopped, so it should be shorter than the whole recording");

    // And the sequence numbers still run without a gap, so the inspector does not report a loss it
    // did not have.
    assert.deepEqual(received.map(e => e.seq), received.map((_, i) => i));
});


/**
 * The web implementation and the adapter, composed the way an application composes them.
 *
 * **This is the test that was missing, and a browser found what it would have found.** The tests
 * above read the payload straight out of `notifyListeners`; `adapter.test.ts` feeds the adapter
 * corpus events by hand. Each half was checked against a stand-in for the other, and the two
 * together were checked by nothing — so a producer emitting something the consumer refuses was
 * invisible.
 *
 * It was not hypothetical: `replay` was driven by `performance.now()`, which returns a float, and
 * `parseBridgeEvent` requires `Number.isSafeInteger(atMillis)`. Every event of every session was
 * dropped by the adapter, the console filled with "an unreadable event was dropped", and the session
 * screen simply stayed empty — no exception, no failing test, nothing to notice but an inspector
 * showing nothing.
 */
test("every event the web implementation emits is one the adapter accepts", { skip: skipNoTraces }, async () => {

    const web = new EvSimulatorWeb();
    web.traces = () => trace("iso2-ac-eim");
    web.pace   = 0;

    const received: unknown[] = [];
    const plugin = adapt(web);

    await plugin.addListener("v2gEvent", event => received.push(event));
    await plugin.start({ ...CONFIG });

    await settle();

    assert.equal(received.length, corpus["iso2-ac-eim"]!.length,
                 "the adapter dropped events the web implementation emitted");

    // And every one of them survives the validator on its own, so the count above cannot be a
    // coincidence of two errors cancelling.
    for (const event of received) assert.doesNotThrow(() => parseBridgeEvent(event));

    for (const event of received as { atMillis: number }[])
        assert.ok(Number.isSafeInteger(event.atMillis), `atMillis ${event.atMillis} is not an integer`);
});


test("stopping twice, or stopping something that never ran, is not an error", { skip: skipNoTraces }, async () => {

    const { plugin } = web();
    const { sessionId } = await plugin.start({ config: CONFIG });

    await plugin.stop({ sessionId });
    await plugin.stop({ sessionId });
    await plugin.stop({ sessionId: "web-does-not-exist" });
});
