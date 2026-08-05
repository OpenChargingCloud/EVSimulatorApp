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
 * `app/src/vendor/traces/` exists because a build cannot skip: `entry.ts` imports three recordings
 * at bundle time, and the canonical corpus lives with the C# session tests that record it — in the
 * ISO15118ConformanceTests repository, the parent that carries this app as a submodule. Standalone
 * there is nothing to compare against and this file skips; under the conformance checkout it is the
 * check that the demo is not shipping a stale recording.
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
const BUNDLED = ["iso2-ac-eim", "iso2-ac-pnc", "iso2-dc-eim",
                 "iso20-ac-eim", "iso20-dc-eim", "iso20-dc-pnc"];

const canonical = name => readFileSync(join(repositoryRoot,
    `../../ISO15118ConformanceTests.Simulation/Vectors/Session.${name}.trace.json`), "utf8");

const skipNoCorpus = (() => {
    try { canonical(BUNDLED[0]); return false; }
    catch { return "session trace corpus absent — it lives in the ISO15118ConformanceTests repo"; }
})();


test("every bundled demo trace is byte-identical to the canonical corpus", { skip: skipNoCorpus }, () => {

    for (const name of BUNDLED)
        assert.equal(
            readFileSync(join(repositoryRoot, `app/src/vendor/traces/Session.${name}.trace.json`), "utf8"),
            canonical(name),
            `${name}: app/src/vendor/traces/ is stale — re-copy it from the conformance corpus`);
});
