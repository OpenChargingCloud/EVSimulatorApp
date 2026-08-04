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

import { sheetFor, configFor, defaultChoices, groupHex } from "../src/sheet.js";
import { parsePairingCode } from "../src/pairing.js";

/**
 * What the confirmation sheet decides, over every code in the pairing corpus.
 *
 * The sheet is the screen the whole design rests on: it is where a person is told what they are
 * about to connect to and given the chance to say no. So its decisions are checked as data —
 * whether the button is live, what is shown first, and what would be handed to the plugin — and only
 * the pixels are left untested.
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

const read = (/** @type {string} */ path) =>
    JSON.parse(readFileSync(join(repositoryRoot, path), "utf8"));

const codes   = read("pairing/EVSimulatorApp.Pairing.Tests/Vectors/Pairing.payload.vectors.json");
const configs = read("bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.config.json");


test("a code the corpus calls blocking cannot be connected to, and says why", () => {

    let blocked = 0, offered = 0;

    for (const c of codes.cases) {

        if (c.outcome !== "parsed") continue;

        const sheet   = sheetFor(c.input);
        const blocking = c.warnings.filter((/** @type {any} */ w) => w.blocking);

        assert.equal(sheet.canConnect, blocking.length === 0, c.name);

        if (blocking.length === 0) { offered++; continue; }

        assert.notEqual(sheet.refusal, null, `${c.name}: refused with no reason given`);

        // Blocking first, so the reason a session cannot start is the first thing read rather than
        // the eighth.
        assert.equal(sheet.warnings[0]?.blocking, true, `${c.name}: a warning outranks the refusal`);
        blocked++;
    }

    assert.ok(blocked >= 2, `only ${blocked} codes were blocked`);
    assert.ok(offered >= 8, `only ${offered} codes were offered`);
});


test("a code that is not one, and a code that is broken, are told apart", () => {

    for (const c of codes.cases) {

        const sheet = sheetFor(c.input);

        const expected = c.outcome === "parsed"         ? "code"
                       : c.outcome === "notAPairingCode" ? "notPairing"
                       :                                   "malformed";

        assert.equal(sheet.outcome, expected, c.name);
        assert.equal(sheet.canConnect, expected === "code" && sheet.canConnect, c.name);

        // The distinction is the whole reason the parser has two failure shapes: one is a shrug, the
        // other is worth telling the user about — and a malformed code says what was wrong with it.
        if (expected === "malformed")
            assert.equal(sheet.problem, c.error, c.name);
    }
});


test("the sheet never crashes, whatever it is handed", () => {

    for (const text of ["", " ", "#", "https://open.charging.cloud/evsim/pair#",
                        "https://open.charging.cloud/evsim/pair#v=1&host=<script>alert(1)</script>&port=1",
                        "v2gsim://pair#" + "a=1&".repeat(500),
                        "\x00\x01", "😀", "javascript:alert(1)"])
        assert.doesNotThrow(() => sheetFor(text), JSON.stringify(text));
});


/**
 * The configuration the sheet would hand to the plugin is one the native side accepts.
 *
 * `SessionConfig.parse` refuses unknown properties, deliberately — so a sheet that produced a ninth
 * one would not degrade, it would stop starting sessions, on a phone. The key set and the permitted
 * values are read out of the shared corpus rather than restated here, because a second copy of the
 * rules is the thing that would go stale.
 */
test("every configuration the sheet produces has the shape the native side accepts", () => {

    /** @type {Set<string>} */
    const knownKeys = new Set();
    /** @type {Record<string, Set<string>>} */
    const permitted = { transport: new Set(), protocol: new Set(), mode: new Set(), authorization: new Set() };

    for (const accepted of configs.accepted) {
        for (const [key, value] of Object.entries(accepted.canonical)) {
            knownKeys.add(key);
            if (key in permitted) permitted[key].add(String(value));
        }
    }

    let produced = 0;

    for (const c of codes.cases) {

        if (c.outcome !== "parsed") continue;

        const sheet = sheetFor(c.input);
        if (!sheet.canConnect) continue;

        const config = configFor(/** @type {any} */ (sheet.payload),
                                 /** @type {any} */ (sheet.choices));

        for (const key of Object.keys(config))
            assert.ok(knownKeys.has(key), `${c.name}: '${key}' is not a property the native side reads`);

        for (const [key, values] of Object.entries(permitted))
            assert.ok(values.has(String(/** @type {any} */ (config)[key])),
                      `${c.name}: ${key} is '${/** @type {any} */ (config)[key]}', which the corpus never accepts`);

        assert.ok(config.port >= 1 && config.port <= 65535, c.name);

        // Absent rather than null, matching what every back end writes.
        assert.ok(!("totp" in config) || typeof config.totp === "string", c.name);
        assert.ok(!("rootFingerprint" in config) || typeof config.rootFingerprint === "string", c.name);

        produced++;
    }

    assert.ok(produced >= 8, `only ${produced} configurations were produced`);
});


test("the protocol the code offers is the one the sheet suggests", () => {

    const iso20 = parsePairingCode(
        "https://open.charging.cloud/evsim/pair#v=1&host=10.0.0.1&port=15118&proto=iso20");
    assert.equal(defaultChoices(/** @type {any} */ (iso20)).protocol, "iso15118-20");

    const both = parsePairingCode(
        "https://open.charging.cloud/evsim/pair#v=1&host=10.0.0.1&port=15118&proto=iso2,iso20");
    assert.equal(defaultChoices(/** @type {any} */ (both)).protocol, "iso15118-2",
                 "the first one this build runs, not the last one listed");

    // A name this build does not know is a fact about the station, not a defect in the code.
    const odd = parsePairingCode(
        "https://open.charging.cloud/evsim/pair#v=1&host=10.0.0.1&port=15118&proto=din70121");
    assert.equal(defaultChoices(/** @type {any} */ (odd)).protocol, "iso15118-2");
});


test("a fingerprint is shown in pairs, because that is the only form a person can compare", () => {

    assert.equal(groupHex("9f86d081"), "9F:86:D0:81");

    // And in groups of eight bytes, so that a wrap lands between groups. A colon is not a line-break
    // opportunity, so without the space the box breaks it mid-pair — which is worse than no grouping.
    assert.equal(groupHex("9f86d081884c7d659a2feaa0c55ad015"),
                 "9F:86:D0:81:88:4C:7D:65 9A:2F:EA:A0:C5:5A:D0:15");

    assert.equal(groupHex(""), "");
});
