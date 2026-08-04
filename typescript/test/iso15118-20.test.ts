import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { CommonMessagesCodec } from "../src/iso20common/CommonMessagesCodec.ts";
import { ACCodec } from "../src/iso20ac/ACCodec.ts";
import { DCCodec } from "../src/iso20dc/DCCodec.ts";

/**
 * The generated ISO 15118-20 codecs against the reference bytes, the same gate the -2 codec passes.
 *
 * ## Why this arrived last
 *
 * The other three back ends have had -20 since July; this one had ISO 15118-2 and nothing else, so
 * **every -20 message in the inspector answered `unchecked`** — a digest it could not re-derive, a
 * signature it could not verify, and an honest sentence saying so. That is a defensible place to
 * stop and a poor place to stay, given that half the protocol work in this project is -20.
 *
 * ## Three separate grammars, and the test is per set for that reason
 *
 * CommonMessages, AC and DC are not layers of one schema: they are independent message sets that
 * happen to embed the same `CommonTypes`, each with its own V2GTP payload type and its own copy of
 * the XMLDSig schema. A codec that muddled two of them would still round-trip its own vectors, so
 * they are driven separately and each is required to reach messages only it has.
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

const corpus = (file: string): { name: string; expectedHex: string }[] =>
    JSON.parse(readFileSync(
        join(repositoryRoot, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Tests/Vectors", file),
        "utf8")).vectors;

const hex = (bytes: Uint8Array) => [...bytes].map(b => b.toString(16).padStart(2, "0")).join(" ");
const parseHex = (text: string) => new Uint8Array(text.trim().split(/\s+/).map(b => parseInt(b, 16)));


/** @type {{set: string, codec: any, file: string, floor: number}[]} */
const sets = [
    { set: "CommonMessages", codec: CommonMessagesCodec,
      file: "Iso15118_20.CommonMessages.vectors.json", floor: 20 },
    { set: "AC", codec: ACCodec, file: "Iso15118_20.AC.vectors.json", floor: 4 },
    { set: "DC", codec: DCCodec, file: "Iso15118_20.DC.vectors.json", floor: 8 },
];


for (const { set, codec, file, floor } of sets) {

    test(`every ISO 15118-20 ${set} vector survives decode and re-encode`, () => {

        const vectors = corpus(file);
        assert.ok(vectors.length >= floor, `${set}: the corpus looks truncated: ${vectors.length}`);

        for (const vector of vectors) {
            const original = parseHex(vector.expectedHex);
            const decoded  = codec.decodeAny(original);
            assert.equal(hex(codec.encodeAny(decoded)), hex(original), `${set}/${vector.name}`);
        }
    });
}


/**
 * The Josev corpus, kept apart from the reference one on purpose.
 *
 * These are bytes a live counterparty produced rather than a reference encoder's, so passing them
 * is interoperability evidence and not conformance evidence. Mixing the two into one array would
 * quietly promote the weaker claim.
 */
test("the DC messages a live Josev station sent also round-trip", () => {

    const vectors = corpus("Iso15118_20.DC.josev.vectors.json");
    assert.ok(vectors.length > 0, "the Josev corpus is empty");

    for (const vector of vectors) {
        const original = parseHex(vector.expectedHex);
        assert.equal(hex(DCCodec.encodeAny(DCCodec.decodeAny(original))), hex(original), vector.name);
    }
});


/**
 * Each set reaches messages only it has.
 *
 * Without this, three codecs that all round-tripped one shared subset of `CommonTypes` would look
 * exactly like three working codecs — and 0x8003 and 0x8004 are seven bits apart on the wire, so
 * "which set is this" is the distinction most worth being sure about.
 */
test("each message set is exercised on messages only it has", () => {

    const names = (file: string) => corpus(file).map(v => v.name);

    for (const required of ["SessionSetupReq", "AuthorizationReq", "ScheduleExchangeRes"])
        assert.ok(names("Iso15118_20.CommonMessages.vectors.json").some(n => n.includes(required)),
                  `CommonMessages has no ${required} vector`);

    assert.ok(names("Iso15118_20.AC.vectors.json").some(n => n.includes("AC_ChargeLoop")),
              "the AC corpus never reaches the AC charge loop");
    assert.ok(names("Iso15118_20.DC.vectors.json").some(n => n.includes("DC_ChargeLoop")),
              "the DC corpus never reaches the DC charge loop");
    assert.ok(names("Iso15118_20.DC.vectors.json").some(n => n.includes("CableCheck")),
              "the DC corpus never reaches the cable check, which AC does not have");
});
