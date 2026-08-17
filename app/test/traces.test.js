/*
 * Copyright (c) 2021-2026 GraphDefined GmbH <achim.friedland@graphdefined.com>
 * This file is part of EVSimulatorApp
 *
 * Licensed under the Affero GPL license, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.gnu.org/licenses/agpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * The bundled demo traces are copies, and copies drift.
 *
 * `app/src/vendor/traces/` exists because a build cannot skip: `entry.ts` imports the recordings at
 * bundle time, from inside `app/src/` where the bundler can reach them. The canonical corpus is
 * `vectors/` at the repository root — so this is a copy of a sibling, and the check below is what
 * keeps the demo from shipping a stale recording.
 *
 * It used to be a copy of something in *another repository*: the corpus lived with the C# session
 * tests that record it, in the ISO15118ConformanceTests parent, and this test skipped whenever the
 * app was checked out on its own. It no longer skips, because there is no longer a checkout in
 * which the corpus is absent.
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

/** The recordings `app/src/vendor/entry.ts` bundles — keep the two lists in step. */
const BUNDLED = ["iso2-ac-eim-meter", "iso2-ac-pnc", "iso2-dc-eim",
                 "iso20-ac-eim", "iso20-dc-eim", "iso20-dc-pnc"];

const canonical = name => readFileSync(join(repositoryRoot,
    `vectors/Session.${name}.trace.json`), "utf8");

const bundled = name => JSON.parse(readFileSync(
    join(repositoryRoot, `app/src/vendor/traces/Session.${name}.trace.json`), "utf8"));


test("every bundled demo trace is byte-identical to the canonical corpus", () => {

    for (const name of BUNDLED)
        assert.equal(
            readFileSync(join(repositoryRoot, `app/src/vendor/traces/Session.${name}.trace.json`), "utf8"),
            canonical(name),
            `${name}: app/src/vendor/traces/ is stale — re-copy it from vectors/`);
});


/**
 * The demo can still demonstrate the one claim the inspector *checks*.
 *
 * `session.js`'s `meterCheckFor` verifies a station's signed reading against the key the session
 * announced — everything else in the inspector is displayed rather than verified. Reaching it needs
 * two things from the same recording, and the -2 AC EIM slot holds the metered session so that it
 * has both. Swapping the plain recording back in would take nothing away that is visible on screen
 * and would silently remove the only verdict in the app that can come out wrong.
 *
 * Reads the bundled copies rather than the corpus, so it checks what the demo actually ships.
 */
test("a bundled recording brings both halves of a meter check: a key and a signed reading", () => {

    const trace = bundled("iso2-ac-eim-meter");

    assert.match(trace.meterKey?.x ?? "", /^[0-9a-f]{64}$/, "no meter key to check a reading against");
    assert.match(trace.meterKey?.y ?? "", /^[0-9a-f]{64}$/);

    const signed = trace.exchanges
        .flatMap(exchange => [exchange.request, exchange.response])
        .filter(frame => frame?.meterSignature);

    // Three, one per charge-loop sample: a single reading would verify and say nothing about a
    // register that has to advance.
    assert.ok(signed.length >= 3, `only ${signed.length} signed readings are bundled`);
});
