import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { SupportedAppProtocolCodec } from "../src/appprotocol/SupportedAppProtocolCodec.ts";
import { SupportedAppProtocolReq } from "../src/appprotocol/SupportedAppProtocolReq.ts";
import { SupportedAppProtocolRes } from "../src/appprotocol/SupportedAppProtocolRes.ts";
import { AppProtocolType } from "../src/appprotocol/AppProtocolType.ts";
import { ResponseCode } from "../src/appprotocol/ResponseCode.ts";

/**
 * The generated TypeScript codec against cbV2G's bytes.
 *
 * The same corpus, the same `expectedHex`, as the C#, Kotlin and Swift back ends — which is what
 * makes a green run wire conformance rather than four implementations agreeing with each other
 * about something wrong. Each vector is exercised twice: encode must reproduce the bytes, and
 * decoding those bytes must reproduce the message.
 */

type Vector = {
    name: string;
    messageType: string;
    input: Record<string, any>;
    expectedHex: string;
};

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

const vectors: Vector[] = JSON.parse(readFileSync(
    join(repositoryRoot, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/Vanaheimr.V2G.Exi.Tests/Vectors/AppProtocol.vectors.json"),
    "utf8")).vectors;

const hex = (bytes: Uint8Array) => [...bytes].map(b => b.toString(16).padStart(2, "0")).join(" ");


/** A vector's `input` object as the generated message it describes. */
function build(vector: Vector): unknown {

    if (vector.messageType === "SupportedAppProtocolReq") {
        return new SupportedAppProtocolReq(
            vector.input.appProtocols.map((p: any) =>
                new AppProtocolType(p.protocolNamespace, p.versionNumberMajor,
                                    p.versionNumberMinor, p.schemaId, p.priority)));
    }

    if (vector.messageType === "SupportedAppProtocolRes") {
        // The corpus names the response code; on the wire it is an index. Looking it up here rather
        // than trusting the string is the point — passing the name straight through produced 0 for
        // every code, which the first vector happened not to notice.
        const code = ResponseCode[vector.input.code as keyof typeof ResponseCode];
        assert.notEqual(code, undefined, `${vector.name}: unknown response code`);
        return new SupportedAppProtocolRes(code, vector.input.schemaId ?? null);
    }

    throw new Error(`unknown message type '${vector.messageType}'`);
}


test("every AppProtocol vector encodes to cbV2G's bytes", () => {

    assert.ok(vectors.length >= 10, `the corpus looks truncated: ${vectors.length}`);

    for (const vector of vectors) {
        assert.equal(hex(SupportedAppProtocolCodec.encodeAny(build(vector))),
                     vector.expectedHex.toLowerCase(), vector.name);
    }
});


test("every AppProtocol vector decodes back to its message", () => {

    for (const vector of vectors) {

        const bytes = new Uint8Array(
            vector.expectedHex.trim().split(/\s+/).map(b => parseInt(b, 16)));

        const decoded = SupportedAppProtocolCodec.decodeAny(bytes);

        // Re-encoded rather than compared field by field: the bytes are the contract, and a
        // structural comparison would need a deep-equality helper that could itself be wrong.
        assert.equal(hex(SupportedAppProtocolCodec.encodeAny(decoded)),
                     vector.expectedHex.toLowerCase(), vector.name);
    }
});
