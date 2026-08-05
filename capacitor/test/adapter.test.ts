import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { adapt } from "../src/index.ts";
import type { EvSimulatorNative } from "../src/definitions.ts";
import type { BridgeEvent } from "@open-charging-cloud/v2g-exi/src/bridge/events.ts";
import type { SessionConfig } from "@open-charging-cloud/v2g-exi/src/bridge/plugin.ts";

/**
 * The Capacitor adapter's own half — the only part of it a laptop can check.
 *
 * The native halves need a phone. What does not is the translation this file performs, and the
 * translation is where the interesting decision lives: an event crosses as **text**, not as a
 * dictionary, because a dictionary is where this project's one JSON guarantee dies. See
 * `src/definitions.ts` for the measurement; the last test here is that measurement kept honest.
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

const configs: { accepted: { name: string; canonical: SessionConfig }[] } = JSON.parse(readFileSync(
    join(repositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.config.json"), "utf8"));


/** A stand-in native side that records what it was asked and replays what it is told to. */
function stubNative() {

    const calls: { method: string; options: unknown }[] = [];
    let deliver: ((payload: { event: string }) => void) | null = null;
    let removed = false;

    const native: EvSimulatorNative = {
        start: async options => { calls.push({ method: "start", options }); return { sessionId: "s-1" }; },
        stop:  async options => { calls.push({ method: "stop",  options }); },
        addListener: async (eventName, listener) => {
            calls.push({ method: "addListener", options: eventName });
            deliver = listener;
            return { remove: async () => { removed = true; } };
        },
    };

    return {
        native,
        calls,
        emit: (text: string) => deliver!({ event: text }),
        wasRemoved: () => removed,
    };
}


test("a configuration is nested under its own key, because Capacitor adds callbackId to the options", async () => {

    const stub   = stubNative();
    const config = configs.accepted[0]!.canonical;

    const { sessionId } = await adapt(stub.native).start(config);

    assert.equal(sessionId, "s-1");
    assert.deepEqual(stub.calls[0], { method: "start", options: { config } });

    // The nesting is the whole point: what the native side parses is the config and nothing else.
    // SessionConfig refuses unknown properties, so a flat call would fail on Capacitor's own key —
    // and would fail on a phone rather than here.
    assert.deepEqual(Object.keys((stub.calls[0]!.options as { config: unknown })), ["config"]);
});


test("stop passes the session id through, and the listener handle removes", async () => {

    const stub   = stubNative();
    const plugin = adapt(stub.native);

    await plugin.stop({ sessionId: "s-1" });
    assert.deepEqual(stub.calls[0], { method: "stop", options: { sessionId: "s-1" } });

    const handle = await plugin.addListener("v2gEvent", () => {});
    await handle.remove();
    assert.equal(stub.wasRemoved(), true);
});


test("every event of every recorded session survives the bridge unchanged", async () => {

    const stub     = stubNative();
    const received: BridgeEvent[] = [];

    await adapt(stub.native).addListener("v2gEvent", event => received.push(event));

    let sent = 0;

    for (const events of Object.values(sessions)) {
        for (const event of events) {
            stub.emit(JSON.stringify(event));
            sent++;

            // Not deepEqual: the JSON-LD documents are pinned as TEXT in this project, so the
            // comparison that matters is the text — member order included, at every depth.
            assert.equal(JSON.stringify(received[received.length - 1]), JSON.stringify(event));
        }
    }

    assert.equal(received.length, sent);
    assert.ok(sent >= 190, `only ${sent} events crossed`);
});


test("an unreadable event is dropped rather than thrown, and the stream continues", async () => {

    const stub     = stubNative();
    const received: BridgeEvent[] = [];

    await adapt(stub.native).addListener("v2gEvent", event => received.push(event));

    const errors: unknown[] = [];
    const wasError = console.error;
    console.error = (...args: unknown[]) => { errors.push(args); };

    try {
        stub.emit("{ this is not JSON");                              // malformed text
        stub.emit(JSON.stringify({ seq: 0, atMillis: 0, kind: "hack" }));   // not an event kind
        stub.emit(JSON.stringify(Object.values(sessions)[0]![0]));    // a real one, after both
    } finally {
        console.error = wasError;
    }

    // A throw here would unwind into Capacitor's event dispatch, where nobody is catching, and would
    // take the rest of the session's stream with it.
    assert.equal(errors.length, 2);
    assert.equal(received.length, 1);
});


test("the adapter reads the payload and nothing else — no repair, no defaults, no re-ordering", async () => {

    // The event is text on purpose, and this side must not start improving it. `JSON.parse` fixes
    // the object's key order to insertion order by specification, so the only way the adapter could
    // change a document is by touching it — which it must not, since the JSON-LD in an event is
    // pinned as text across four back ends.
    //
    // The two platform measurements that decide the payload's shape are made where the real
    // libraries are: `EvSimulatorPluginTest` on Android (com.getcapacitor.JSObject) and
    // `SessionConfigTests` on iOS (Foundation's JSONSerialization).

    const stub = stubNative();
    let received: BridgeEvent | null = null;

    await adapt(stub.native).addListener("v2gEvent", event => { received = event; });

    const text = JSON.stringify(Object.values(sessions)[0]![1]);
    stub.emit(text);

    assert.equal(JSON.stringify(received), text);
    assert.notEqual(received, null);
});
