import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { Iso15118_2Codec } from "../src/iso2/Iso15118_2Codec.ts";

/**
 * The generated ISO 15118-2 codec against cbV2G's bytes.
 *
 * Every vector is decoded and re-encoded, and the result must be the original bytes. That is a
 * weaker statement than the AppProtocol test's — it never builds a message from the corpus's
 * `input` — and a much wider one: it drives the whole of both directions over 39 real messages,
 * with no fixtures to get wrong, and any disagreement between the decoder and the encoder shows up
 * as changed bytes.
 *
 * It reaches what AppProtocol cannot: the `V2G_Message` wrapper, the `BodyType` substitution group,
 * attributes, simple content, optional runs and bounded lists.
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

const vectors: { name: string; expectedHex: string }[] = JSON.parse(readFileSync(
    join(repositoryRoot, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/WWCP_ISO15118_EXI_Tests/Vectors/Iso15118_2.vectors.json"),
    "utf8")).vectors;

const hex = (bytes: Uint8Array) => [...bytes].map(b => b.toString(16).padStart(2, "0")).join(" ");
const parseHex = (text: string) => new Uint8Array(text.trim().split(/\s+/).map(b => parseInt(b, 16)));


test("every ISO 15118-2 vector survives decode and re-encode", () => {

    assert.ok(vectors.length >= 30, `the corpus looks truncated: ${vectors.length}`);

    for (const vector of vectors) {

        const original = parseHex(vector.expectedHex);
        const decoded  = Iso15118_2Codec.decodeAny(original);

        assert.equal(hex(Iso15118_2Codec.encodeAny(decoded)), hex(original), vector.name);
    }
});


test("the corpus reaches the constructs AppProtocol cannot", () => {

    const names = vectors.map(v => v.name);

    for (const required of ["SessionSetupReq", "ServiceDiscoveryRes", "CurrentDemandRes"]) {
        assert.ok(names.includes(required),
                  `the corpus no longer covers '${required}'; ${names.length} vectors present`);
    }
});
