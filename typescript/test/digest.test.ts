import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { Iso15118_2Codec } from "../src/iso2/Iso15118_2Codec.ts";
import { V2G_Message } from "../src/iso2/V2G_Message.ts";
import { AuthorizationReqType } from "../src/iso2/AuthorizationReqType.ts";
import { MeteringReceiptReqType } from "../src/iso2/MeteringReceiptReqType.ts";

/**
 * The digest of a signed message, re-derived from its own frame.
 *
 * ## Why this test is the fragment encoders' first oracle
 *
 * `iso15118-2.test.ts` round-trips whole documents against `expectedHex`, which is byte-exact vs
 * libcbv2g — but nothing has ever checked `encodeFragment_*`, in this back end. A fragment is a
 * different grammar from a document (its top-level element event-code width tracks the schema's
 * global-element count, which is the whole reason the -2/-20 signature interop needed a dual-grammar
 * implementation), so a document encoder being right says nothing about a fragment encoder being
 * right.
 *
 * The recorded PnC sessions supply the missing oracle, and it costs nothing: C# computed
 * `DigestValue` over *its* canonical EXI fragment and the value is in the corpus. Re-deriving it here
 * from the recorded frame is therefore a byte-exactness check on this back end's fragment encoder,
 * expressed as the thing the app actually wants to do.
 *
 * ## …and it is the app feature, not a stand-in for it
 *
 * The session inspector shows a signature's digest and says, on screen, that it did not verify it.
 * This is what removes that sentence for the -2 messages: the same computation, from the same input
 * an event carries — the raw frame, decoded here rather than trusted from the JSON-LD beside it.
 * **No key is involved.** A digest says the signature covers *this content*; whether the right party
 * signed it is a separate question needing the contract certificate.
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

const sessions: Record<string, any[]> = JSON.parse(readFileSync(
    join(repositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json"),
    "utf8")).sessions;

const V2GTP_HEADER_BYTES = 8;

const parseHex = (text: string) =>
    new Uint8Array((text.match(/.{1,2}/g) ?? []).map(b => parseInt(b, 16)));

const hex = (bytes: Uint8Array) => [...bytes].map(b => b.toString(16).padStart(2, "0")).join("");

/** SHA-256 through WebCrypto, which is the API the browser will use for exactly this. */
const sha256 = async (bytes: Uint8Array) =>
    hex(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)));


/**
 * The fragment encoder for a body element, by the element's own type.
 *
 * A `switch` and not a lookup by name: this back end has three fragment encoders, the -2 signed
 * messages are two of them, and a message this build cannot re-encode has to be *reported* rather
 * than guessed at. Returning null is what lets the caller say "not checked" instead of "wrong".
 */
function fragmentOf(element: unknown): Uint8Array | null {
    if (element instanceof AuthorizationReqType)
        return Iso15118_2Codec.encodeFragment_AuthorizationReq(element);
    if (element instanceof MeteringReceiptReqType)
        return Iso15118_2Codec.encodeFragment_MeteringReceiptReq(element);
    return null;
}


test("the digest of every signed -2 message re-derives from its own frame", async () => {

    /** @see the assertion at the end for why this list is spelled out rather than counted. */
    const expected = ["AuthorizationReqType", "MeteringReceiptReqType"];

    /** @type {string[]} */
    const checkedNames: string[] = [];
    let checked = 0;

    for (const [name, events] of Object.entries(sessions)) {

        if (!name.startsWith("iso2-")) continue;   // this back end has no -20 codecs

        for (const event of events) {

            const signature = event?.json?.header?.signature;
            if (signature === undefined || signature === null) continue;

            // The frame is the input, not the JSON-LD beside it. An event carries both so that a
            // consumer can check one against the other; trusting the document here would make this
            // test a statement about the recorder rather than about the codec.
            const payload = parseHex(event.exi).slice(V2GTP_HEADER_BYTES);
            const message = Iso15118_2Codec.decodeAny(payload) as V2G_Message;
            const element = message.body.bodyElement;

            const fragment = fragmentOf(element);
            assert.notEqual(fragment, null,
                            `${name} seq ${event.seq}: no fragment encoder for ${element?.constructor?.name}`);

            const references = Array.isArray(signature.signedInfo.reference)
                                   ? signature.signedInfo.reference
                                   : [signature.signedInfo.reference];

            assert.equal(references.length, 1,
                         `${name} seq ${event.seq}: expected exactly one reference`);

            assert.equal(await sha256(fragment!), String(references[0].digestValue).toLowerCase(),
                         `${name} seq ${event.seq} (${event.messageName}): the digest re-derived here `
                       + "differs from the one C# recorded — this back end's fragment encoder and C#'s "
                       + "disagree about the canonical EXI of the signed element");

            checkedNames.push(String(event.messageName));
            checked++;
        }
    }

    // Named rather than counted, because the two are the two *kinds* of -2 signature this project
    // produces — the signed AuthorizationReq that authorizes, and the signed MeteringReceiptReq that
    // countersigns the station's meter reading — and each exercises a different fragment encoder. A
    // count would go on passing if one of them stopped being recorded.
    assert.deepEqual(checkedNames.sort(), expected.sort(),
                     `checked ${checked} signed -2 message(s): ${checkedNames.join(", ")}`);
});


test("a digest check that cannot see the content fails rather than passing quietly", async () => {

    const events = sessions["iso2-ac-pnc"] ?? [];
    const signed = events.find(e => e?.json?.header?.signature);
    assert.notEqual(signed, undefined);

    const payload = parseHex(signed.exi).slice(V2GTP_HEADER_BYTES);
    const message = Iso15118_2Codec.decodeAny(payload) as V2G_Message;
    const fragment = fragmentOf(message.body.bodyElement)!;

    // One bit of the *content* moves and the digest must move with it. Without this, a check that
    // hashed a constant would pass the test above just as happily.
    const tampered = new Uint8Array(fragment);
    tampered[tampered.length - 1] ^= 0x01;

    assert.notEqual(await sha256(tampered), await sha256(fragment));
});
