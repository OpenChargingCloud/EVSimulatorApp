import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import type { JsonObject } from "../src/runtime/index.ts";
import { SupportedAppProtocolCodec } from "../src/appprotocol/SupportedAppProtocolCodec.ts";
import { SupportedAppProtocolCodecJson } from "../src/appprotocol/SupportedAppProtocolCodecJson.Json.ts";
import { Iso15118_2Codec } from "../src/iso2/Iso15118_2Codec.ts";
import { Iso15118_2CodecJson } from "../src/iso2/Iso15118_2CodecJson.Json.ts";

/**
 * This back end's JSON-LD documents, against the ones the C# back end produces.
 *
 * ## Why this is the check, and the round trip is not
 *
 * `EXI → JSON → EXI` proves a mapping loses nothing, and it is **blind to what the mapping is
 * called**: rename every property and it stays green, because the serializer and the parser rename
 * together. Measured on the C# side — replacing the naming rule with a naïve
 * lower-the-first-character one turned `evseStatus` into `eVSEStatus` in every message of every set,
 * and all 163 round-trip tests still passed.
 *
 * So the agreement is checked against **text**. `JsonLd.documents.json` holds every vector's JSON
 * form exactly as C# wrote it, and this compares character for character: property names, property
 * order, `@context` and `@type` placement, hex for binary, strings for 64-bit integers, and which
 * optional properties are omitted rather than written as null.
 *
 * Both directions, because they can fail apart: a serializer can agree while a parser quietly
 * accepts something it should not.
 *
 * **This is the back end the 64-bit-as-string rule was written for.** The other three carry it and
 * would round-trip either way; here a `TimeStamp` written as a JSON number is rounded the moment it
 * passes 2^53.
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

const vectorsDirectory = join(repositoryRoot, "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Tests/Vectors");
const read = (name: string) => JSON.parse(readFileSync(join(vectorsDirectory, name), "utf8"));

const documents = read("JsonLd.documents.json").sets as Record<string, Record<string, JsonObject>>;

const parseHex = (text: string) => new Uint8Array(text.trim().split(/\s+/).map(b => parseInt(b, 16)));
const hex = (bytes: Uint8Array) => [...bytes].map(b => b.toString(16).padStart(2, "0")).join(" ");

const sets = [
    {
        name: "AppProtocol",
        vectors: read("AppProtocol.vectors.json").vectors as { name: string; expectedHex: string }[],
        codec: SupportedAppProtocolCodec,
        json: SupportedAppProtocolCodecJson,
    },
    {
        name: "ISO 15118-2",
        vectors: read("Iso15118_2.vectors.json").vectors as { name: string; expectedHex: string }[],
        codec: Iso15118_2Codec,
        json: Iso15118_2CodecJson,
    },
];


test("this back end writes the documents C# writes", () => {

    let checked = 0;

    for (const set of sets) {

        const expected = documents[set.name];
        assert.ok(expected, `the corpus has no documents for ${set.name}`);

        for (const vector of set.vectors) {

            const produced = set.json.toJSON(set.codec.decodeAny(parseHex(vector.expectedHex)));

            assert.equal(JSON.stringify(produced), JSON.stringify(expected[vector.name]),
                         `${set.name}/${vector.name}`);
            checked++;
        }
    }

    assert.ok(checked >= 55, `only ${checked} documents were compared`);
});


test("this back end reads the documents C# writes", () => {

    for (const set of sets) {
        for (const vector of set.vectors) {

            const message = set.json.parseJSON(documents[set.name][vector.name]);

            assert.equal(hex(set.codec.encodeAny(message)), hex(parseHex(vector.expectedHex)),
                         `${set.name}/${vector.name}: the bytes changed on the way through JSON`);
        }
    }
});


test("every document carries its vocabulary", () => {

    for (const set of sets) {
        const context = documents[set.name]["@context"];
        for (const vector of set.vectors) {
            const produced = set.json.toJSON(set.codec.decodeAny(parseHex(vector.expectedHex)));
            assert.equal(produced["@context"], context, `${set.name}/${vector.name}`);
        }
    }
});
