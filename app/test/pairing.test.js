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

// @ts-check
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { parsePairingCode, warningsFor, PairingFormatError, isPrivateTarget } from "../src/pairing.js";

/**
 * This back end's reading of a scanned pairing code, against C#'s.
 *
 * The Pi renders the code and the phone reads it, and the two never run in the same process — so the
 * format is pinned by a corpus rather than by two readings of a paragraph. Most of what is pinned is
 * refusals, because a pairing code is an image anyone can tape over a display.
 *
 * The **refusal texts** are part of the corpus. Four back ends that say no for four different reasons
 * are four different products, and the difference only ever shows up in front of a user who cannot
 * act on it.
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

const corpus = JSON.parse(readFileSync(
    join(repositoryRoot, "pairing/EVSimulatorApp.Pairing.Tests/Vectors/Pairing.payload.vectors.json"),
    "utf8"));


test("every code C# parses is parsed here, field for field", () => {

    let checked = 0;

    for (const c of corpus.cases) {

        if (c.outcome !== "parsed") continue;

        const payload = parsePairingCode(c.input);

        assert.notEqual(payload, null, `${c.name}: refused as not-a-pairing-code`);

        // Compared as text, in the corpus's own key order, so that a renamed or reordered field is a
        // failure rather than something deepEqual would forgive.
        assert.equal(JSON.stringify(reorder(payload, c.payload)), JSON.stringify(c.payload), c.name);
        checked++;
    }

    assert.ok(checked >= 12, `only ${checked} codes were parsed`);
});


test("every code C# refuses is refused here, in the same words", () => {

    let malformed = 0, notPairing = 0;

    for (const c of corpus.cases) {

        if (c.outcome === "parsed") continue;

        if (c.outcome === "notAPairingCode") {
            assert.equal(parsePairingCode(c.input), null,
                         `${c.name}: read as a pairing code; C# shrugs at it`);
            notPairing++;
            continue;
        }

        assert.throws(() => parsePairingCode(c.input),
                      /** @param {unknown} error */
                      error => {
                          assert.ok(error instanceof PairingFormatError, `${c.name}: ${error}`);
                          assert.equal(error.message, c.error, c.name);
                          return true;
                      },
                      `${c.name} was accepted; C# refuses it with: ${c.error}`);
        malformed++;
    }

    assert.ok(malformed >= 8, `only ${malformed} malformed cases`);
    assert.ok(notPairing >= 2, `only ${notPairing} not-a-pairing-code cases`);
});


test("every warning C# raises is raised here, in the same order and with the same blocking flag", () => {

    for (const c of corpus.cases) {

        if (c.outcome !== "parsed") continue;

        const warnings = warningsFor(/** @type {any} */ (parsePairingCode(c.input)));

        assert.deepEqual(warnings.map(w => ({ kind: w.kind, blocking: w.blocking })),
                         c.warnings, c.name);
    }
});


/**
 * The one rule here that is about safety rather than shape, checked away from the corpus too.
 *
 * The corpus covers it through whole codes; these are the boundaries, where an off-by-one in a mask
 * would let a public address through while every corpus case still passed.
 */
test("a private target is judged from the text, and never resolved", () => {

    for (const host of ["127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                        "169.254.1.1", "::1", "fe80::1", "fe80::1%en0", "fc00::1", "fd12:3456::1",
                        "evsim-pi.local", "EVSIM-PI.LOCAL"])
        assert.equal(isPrivateTarget(host), true, host);

    for (const host of ["8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.1.1", "169.253.1.1",
                        "2001:db8::1", "fb00::1", "station.example.com", "localhost",
                        "010.0.0.1", "1.2.3", "1.2.3.4.5", "256.1.1.1"])
        assert.equal(isPrivateTarget(host), false, host);
});


/**
 * Reorders `actual`'s keys to match `expected`'s, so the comparison above is about values and names
 * rather than about which order two languages happen to write an object literal in.
 *
 * Any key `expected` does not have is appended, so an extra field still fails.
 *
 * @param {Record<string, unknown>} actual
 * @param {Record<string, unknown>} expected
 */
function reorder(actual, expected) {
    /** @type {Record<string, unknown>} */
    const out = {};
    for (const key of Object.keys(expected)) out[key] = actual[key];
    for (const key of Object.keys(actual)) if (!(key in out)) out[key] = actual[key];
    return out;
}
