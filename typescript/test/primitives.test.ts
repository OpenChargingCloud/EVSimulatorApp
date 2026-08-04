import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { BitReader, BitWriter, ExiPrimitives } from "../src/runtime/index.ts";

/**
 * The TypeScript runtime against the primitive corpus the other three back ends are held to.
 *
 * The corpus is cbV2G's output, so a green run is wire conformance rather than self-consistency —
 * the same standard the C#, Kotlin and Swift runtimes are held to, and the reason this file reads
 * the shared vectors rather than carrying its own.
 *
 * Two families of vector matter more here than anywhere else, because JavaScript is the language
 * most likely to get them wrong:
 *
 * - **Astral characters.** A JavaScript string is UTF-16 code units, and U+1F600 is two of them but
 *   one code point. An encoder written with `s.length` and `charCodeAt` produces a different length
 *   prefix and different values, and every ASCII vector still passes.
 * - **64-bit integers.** `number` is a double, so anything above 2^53 rounds silently. The EXI
 *   Unsigned Integer and Integer are `bigint` here for that reason.
 */

const vectorsDirectory = (() => {
    let directory = dirname(fileURLToPath(import.meta.url));
    while (!existsSync(join(directory, "libs/Vanaheimr.V2G.Exi"))) {
        const parent = dirname(directory);
        if (parent === directory) throw new Error("repository root not found");
        directory = parent;
    }
    return join(directory, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Tests/Vectors");
})();

function existsSync(path: string): boolean {
    try { readFileSync(path); return true; } catch { return isDirectory(path); }
}

function isDirectory(path: string): boolean {
    try { readFileSync(join(path, ".")); return true; } catch (e: any) { return e?.code === "EISDIR"; }
}

type Vector = { name: string; datatype: string; value?: string; valueHex?: string; expectedHex: string; note?: string };

const vectors: Vector[] =
    JSON.parse(readFileSync(join(vectorsDirectory, "Primitives.vectors.json"), "utf8")).vectors;

const hex = (bytes: Uint8Array) => [...bytes].map(b => b.toString(16).padStart(2, "0")).join(" ");
const parseHex = (text: string) =>
    new Uint8Array(text.trim().split(/\s+/).filter(Boolean).map(b => parseInt(b, 16)));


function encode(vector: Vector): Uint8Array {

    const w = new BitWriter();

    switch (vector.datatype) {
        case "unsignedInteger": ExiPrimitives.writeUnsignedInteger(w, BigInt(vector.value!)); break;
        case "signedInteger":   ExiPrimitives.writeSignedInteger(w, BigInt(vector.value!)); break;
        case "string":          ExiPrimitives.writeStringValue(w, vector.value!); break;
        case "binary":          ExiPrimitives.writeBinary(w, parseHex(vector.valueHex!)); break;
        case "boolean":         ExiPrimitives.writeBoolean(w, vector.value === "true"); break;
        default: throw new Error(`unknown datatype '${vector.datatype}'`);
    }

    w.alignToByte();
    return w.bytes;
}


function decode(vector: Vector, bytes: Uint8Array): string {

    const r = new BitReader(bytes);

    switch (vector.datatype) {
        case "unsignedInteger": return ExiPrimitives.readUnsignedInteger(r).toString();
        case "signedInteger":   return ExiPrimitives.readSignedInteger(r).toString();
        case "string":          return ExiPrimitives.readStringValue(r, "slot");
        case "binary":          return hex(ExiPrimitives.readBinary(r));
        case "boolean":         return String(ExiPrimitives.readBoolean(r));
        default: throw new Error(`unknown datatype '${vector.datatype}'`);
    }
}


test("every primitive vector encodes to cbV2G's bytes", () => {

    assert.ok(vectors.length >= 20, `the corpus looks truncated: ${vectors.length}`);

    for (const vector of vectors) {
        assert.equal(hex(encode(vector)), vector.expectedHex.toLowerCase(),
                     `${vector.name}${vector.note ? ` — ${vector.note}` : ""}`);
    }
});


test("every primitive vector decodes back to its value", () => {

    for (const vector of vectors) {

        // Binary vectors name their input `valueHex`; everything else `value`.
        const expected = vector.datatype === "binary" ? hex(parseHex(vector.valueHex!)) : vector.value!;

        assert.equal(decode(vector, parseHex(vector.expectedHex)), expected, vector.name);
    }
});


test("the corpus still covers the two traps JavaScript sets", () => {

    const names = vectors.map(v => v.name);

    assert.ok(names.some(n => n.includes("astral")),
              "without an astral vector, a code-unit-wise string encoder passes everything");

    // Above 2^53: the value a `number` would round. Asserted directly rather than hoped for, since
    // the corpus need not contain one.
    const w = new BitWriter();
    ExiPrimitives.writeUnsignedInteger(w, 9007199254740993n);
    w.alignToByte();

    assert.equal(ExiPrimitives.readUnsignedInteger(new BitReader(w.bytes)), 9007199254740993n);
});
