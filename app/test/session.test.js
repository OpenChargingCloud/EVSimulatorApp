// @ts-check
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { rowsFor, detailFor, statusOf, hexLines } from "../src/session.js";

/**
 * The session screen, over the recorded event streams.
 *
 * The corpus is the one C# generates from the six recorded sessions and three other back ends are
 * held to, so this is the same events a phone would receive — reached from the last place they go.
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

/** @type {Record<string, any[]>} */
const sessions = JSON.parse(readFileSync(
    join(repositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json"),
    "utf8")).sessions;


test("every recorded session renders one row per event, and no gaps", () => {

    let checked = 0;

    for (const [name, events] of Object.entries(sessions)) {

        const rows = rowsFor(events);

        assert.equal(rows.length, events.length, `${name}: a complete stream grew or shrank`);
        assert.equal(rows.filter(r => r.tone === "gap").length, 0, `${name}: a gap in a complete stream`);

        assert.equal(rows[0]?.tone, "started", name);
        assert.equal(rows[rows.length - 1]?.tone, "finished", name);

        const status = statusOf(events);
        assert.equal(status.running, false, name);
        assert.equal(status.outcome, "completed", name);
        assert.equal(status.lost, 0, name);

        checked += rows.length;
    }

    assert.ok(checked >= 190, `only ${checked} rows`);
});


/**
 * A dropped event is reported rather than silently shortening the session.
 *
 * `seq` is documented in four back ends as being there so that "a consumer that sees a gap has lost
 * events", and until this screen existed **nothing anywhere looked**. A loss on a bridge is silent by
 * construction — the listener is simply not called — so a screen that rendered what it received would
 * show a shorter session with no sign that it was shorter.
 */
test("a lost event becomes a row of its own", () => {

    const whole = Object.values(sessions)[0] ?? [];

    const missingOne  = whole.filter((/** @type {any} */ _, /** @type {number} */ i) => i !== 3);
    const missingFour = whole.filter((/** @type {any} */ _, /** @type {number} */ i) => i < 3 || i > 6);

    const one = rowsFor(missingOne).filter(r => r.tone === "gap");
    assert.equal(one.length, 1);
    assert.equal(one[0]?.title, "1 event was lost");
    assert.equal(one[0]?.subtitle, "nothing arrived for seq 3");

    const four = rowsFor(missingFour).filter(r => r.tone === "gap");
    assert.equal(four.length, 1);
    assert.equal(four[0]?.title, "4 events were lost");
    assert.equal(four[0]?.subtitle, "nothing arrived for seq 3–6");

    assert.equal(statusOf(missingFour).lost, 1);

    // And the rest of the session still renders: a gap is a note in the record, not the end of it.
    assert.equal(rowsFor(missingFour).length, missingFour.length + 1);
});


test("a stream that repeats or rewinds a sequence number is not silently smoothed over", () => {

    const whole = Object.values(sessions)[0] ?? [];
    const rewound = [whole[0], whole[1], whole[2], whole[1], whole[3]];

    // seq 1 arrives twice: the second one does not lower the expectation, so seq 3 is still expected
    // where it is and no phantom gap appears — but nothing is dropped either.
    const rows = rowsFor(rewound);
    assert.equal(rows.length, rewound.length, "an out-of-order event was swallowed");
    assert.equal(rows.filter(r => r.tone === "gap").length, 0);
});


test("an opened message shows both halves — the document and the frame", () => {

    let messages = 0;

    for (const events of Object.values(sessions)) {
        for (const event of events) {

            const detail = detailFor(event);

            if (event.kind !== "message") continue;

            assert.notEqual(detail.json, null, `event ${event.seq}: no document`);
            assert.notEqual(detail.hex,  null, `event ${event.seq}: no frame`);

            // The claim and its evidence, both readable. A screen that showed only the JSON-LD would
            // be telling the user the bytes are right there without ever showing them.
            assert.ok(/** @type {string} */ (detail.json).includes("\"@type\""));
            assert.equal(/** @type {string} */ (detail.hex).replace(/[\s]/g, ""), event.exi);

            messages++;
        }
    }

    assert.ok(messages >= 130, `only ${messages} messages`);
});


test("an event kind this build does not know is shown, not dropped", () => {

    const rows = rowsFor([
        { seq: 0, atMillis: 0, kind: "sessionStarted", name: "x", protocol: "iso15118-2", mode: "ac" },
        { seq: 1, atMillis: 1, kind: "somethingNewer" },
    ]);

    assert.equal(rows.length, 2);
    assert.equal(rows[1]?.tone, "error");
    assert.match(/** @type {string} */ (rows[1]?.title), /somethingNewer/);
});


test("a frame is shown sixteen bytes to the line", () => {
    assert.equal(hexLines("00112233445566778899aabbccddeeff00"),
                 "00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff\n00");
});
