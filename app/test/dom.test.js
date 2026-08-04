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
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * The rule that everything this application shows is written as text, enforced by absence.
 *
 * Every string on every screen came from a QR code or from a station: an image anyone can tape over
 * a display, and a peer on a network. Escaping them correctly is a discipline, and a discipline is
 * satisfied by being careful — which is to say, until the day somebody is not. Not using the
 * dangerous API at all is a property, and this is what checks it.
 *
 * The same applies to `eval` and to `new Function`: a scanned code that reached either would not
 * need an escaping mistake at all.
 */

const here = dirname(fileURLToPath(import.meta.url));
const src  = join(here, "..", "src");

/**
 * Every source file, wherever it sits.
 *
 * `.js` only, which scopes the rule to what this application writes: `src/vendor/entry.ts` is the
 * bundler's entry and touches no DOM, and `vendor/ev-simulator.js` is a build output containing
 * `@capacitor/core`, which certainly uses the APIs below somewhere and is entitled to. The guarantee
 * here is about the screens — nothing bundled ever writes to one.
 */
function sources(/** @type {string} */ directory) {
    /** @type {string[]} */
    const files = [];
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const path = join(directory, entry.name);
        if (entry.isDirectory()) files.push(...sources(path));
        else if (entry.name.endsWith(".js")) files.push(path);
    }
    return files;
}

/**
 * Spelled apart so that this file does not trip its own check.
 *
 * A test that fails on itself is a test somebody deletes.
 */
const FORBIDDEN = [
    ["inner", "HTML"],
    ["outer", "HTML"],
    ["insertAdjacent", "HTML"],
    ["document.", "write"],
    ["", "eval("],
    ["new ", "Function("],
    ["dangerouslySet", "InnerHTML"],
].map(parts => parts.join(""));


/**
 * Comments removed before the check.
 *
 * The rule is about what the code does, not about what it is allowed to explain — and the files that
 * explain this rule best are the ones that name the API they refuse to use. The first run of this
 * test failed on `sheet.js`, whose doc comment says why there is no `innerHTML` in it.
 *
 * Crude on purpose: it does not understand string literals, so a forbidden token inside one is still
 * a failure. That is the right way round.
 *
 * @param {string} text
 */
function code(text) {
    return text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^[ \t]*\/\/.*$/gm, "");
}


test("no source file can turn a scanned string into markup or into code", () => {

    const files = sources(src);

    assert.ok(files.length >= 6, `only ${files.length} source files were scanned`);

    for (const file of files) {

        const text = code(readFileSync(file, "utf8"));

        for (const forbidden of FORBIDDEN)
            assert.ok(!text.includes(forbidden),
                      `${file} uses '${forbidden}'. Everything on these screens is untrusted; `
                    + `build elements and set textContent instead.`);
    }
});


/**
 * And the page itself forbids the same things a second time, from the other direction.
 *
 * The policy is a lock on the same door: `script-src 'self'` with no `unsafe-inline` means a scanned
 * code cannot become a script even if a renderer somewhere forgot itself, and `connect-src 'none'`
 * means a code cannot make the phone fetch anything. Neither replaces the rule above — a page can be
 * loaded without its policy honoured, and a policy cannot stop a screen from being wrong. Two locks.
 */
test("the page declares a content security policy that forbids remote code and network access", () => {

    const html = readFileSync(join(here, "..", "index.html"), "utf8");

    // Only the delimiter is excluded, not both kinds of quote: the policy is full of `'none'` and
    // `'self'`, and a pattern that stopped at a single quote captured the first two words of it and
    // then complained that the rest was missing.
    const policy = /content="([^"]*default-src[^"]*)"/s.exec(html)?.[1]?.replace(/\s+/g, " ");

    assert.notEqual(policy, undefined, "index.html declares no Content-Security-Policy");

    for (const directive of ["default-src 'none'", "script-src 'self'", "connect-src 'none'",
                             "base-uri 'none'", "form-action 'none'"])
        assert.ok(/** @type {string} */ (policy).includes(directive),
                  `the policy is missing ${directive}: ${policy}`);

    assert.ok(!/** @type {string} */ (policy).includes("unsafe-inline"), policy);
    assert.ok(!/** @type {string} */ (policy).includes("unsafe-eval"), policy);
});


/**
 * The page loads its own files and nothing else.
 *
 * A CDN in a WebView is a third party with script rights on the screen a person approves a charging
 * session on. The policy above already forbids it; this says the same thing about what is written
 * rather than about what is permitted, so that a policy relaxed by mistake does not silently allow
 * something that was already there.
 */
test("the page references no remote asset", () => {

    const html = readFileSync(join(here, "..", "index.html"), "utf8");

    for (const [, url] of html.matchAll(/(?:src|href)=["']([^"']+)["']/g))
        assert.ok(!/^(?:https?:)?\/\//.test(url), `index.html loads ${url} from somewhere else`);
});
