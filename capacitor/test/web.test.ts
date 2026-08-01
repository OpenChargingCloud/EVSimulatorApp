import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { EvSimulatorWeb } from "../src/web.ts";
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
        try { readFileSync(join(directory, "libs/Vanaheimr.V2G.Exi/CLAUDE.md")); return directory; }
        catch { /* keep walking */ }
        const parent = dirname(directory);
        if (parent === directory) throw new Error("repository root not found");
        directory = parent;
    }
})();

const trace = (name: string): SessionTrace => JSON.parse(readFileSync(join(repositoryRoot,
    `libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Simulation.Tests/Vectors/Session.${name}.trace.json`),
    "utf8"));

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


test("a bundled session arrives as the events the corpus pins", async () => {

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

    // Monotonic, which is the property `atMillis` actually claims.
    const times = received.map(e => e.atMillis);
    assert.deepEqual(times, [...times].sort((a, b) => a - b));
});


test("a protocol this build cannot decode is refused before anything starts", async () => {

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


test("stopping ends the stream rather than leaving it hanging", async () => {

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


test("stopping twice, or stopping something that never ran, is not an error", async () => {

    const { plugin } = web();
    const { sessionId } = await plugin.start({ config: CONFIG });

    await plugin.stop({ sessionId });
    await plugin.stop({ sessionId });
    await plugin.stop({ sessionId: "web-does-not-exist" });
});
