import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { Iso15118_2Codec } from "../src/iso2/Iso15118_2Codec.ts";
import { V2G_Message } from "../src/iso2/V2G_Message.ts";
import { AuthorizationReqType } from "../src/iso2/AuthorizationReqType.ts";
import { MeteringReceiptReqType } from "../src/iso2/MeteringReceiptReqType.ts";
import { CertificateInstallationReqType } from "../src/iso2/CertificateInstallationReqType.ts";
import { CertificateUpdateReqType } from "../src/iso2/CertificateUpdateReqType.ts";
import { CertificateInstallationResType } from "../src/iso2/CertificateInstallationResType.ts";
import { CertificateUpdateResType } from "../src/iso2/CertificateUpdateResType.ts";
import { ChargeParameterDiscoveryResType } from "../src/iso2/ChargeParameterDiscoveryResType.ts";
import { SAScheduleListType } from "../src/iso2/SAScheduleListType.ts";
import { SalesTariffType } from "../src/iso2/SalesTariffType.ts";

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
 * The elements a -2 message actually signs, as reference-URI/fragment pairs.
 *
 * **Two shapes, not one.** A Plug & Charge *request* signs its own body element under a single
 * reference — that was the only shape recorded here until `iso2-ac-eim-tariff`, and this helper
 * returned one fragment because one was all that existed. A §7.9.2.5 tariff signature is the other
 * shape: it rides on a *response*, and it covers the SalesTariffs **inside** the body, one reference
 * per tariff. "The signature covers the body element" was a PnC-shaped assumption wearing the clothes
 * of a general rule, and the first signed offer recorded is what took them off.
 *
 * A `switch` and not a lookup by name, for the reason it always was: a message this build cannot
 * re-encode has to be *reported* rather than guessed at, and null is what lets the caller say "not
 * checked" instead of "wrong".
 */
function signedElementsOf(element: unknown): { uri: string, fragment: Uint8Array }[] | null {

    if (element instanceof AuthorizationReqType)
        return [{ uri: "#" + element.id,
                  fragment: Iso15118_2Codec.encodeFragment_AuthorizationReq(element) }];

    if (element instanceof MeteringReceiptReqType)
        return [{ uri: "#" + element.id,
                  fragment: Iso15118_2Codec.encodeFragment_MeteringReceiptReq(element) }];

    // A car asking for its first contract signs the whole request, as a PnC request does — and the
    // renewal signs its own message type, which is the same shape and a different class. Both arrived
    // with the provisioning recordings; before them this back end had never seen either on the wire.
    if (element instanceof CertificateInstallationReqType)
        return [{ uri: "#" + element.id,
                  fragment: Iso15118_2Codec.encodeFragment_CertificateInstallationReq(element) }];

    if (element instanceof CertificateUpdateReqType)
        return [{ uri: "#" + element.id,
                  fragment: Iso15118_2Codec.encodeFragment_CertificateUpdateReq(element) }];

    // And the answers, which sign **four** elements under one header signature (§7.9.2.4.2) — the
    // contract chain, the encrypted private key, the DH public point and the eMAID, each digested
    // over its own fragment. A verifier that checked only the chain would take an encrypted key
    // nobody signed for. Both response types carry the same five fields in the same order.
    if (element instanceof CertificateInstallationResType || element instanceof CertificateUpdateResType)
        return [{ uri: "#" + element.contractSignatureCertChain.id,
                  fragment: Iso15118_2Codec.encodeFragment_ContractSignatureCertChain(
                                element.contractSignatureCertChain) },
                { uri: "#" + element.contractSignatureEncryptedPrivateKey.id,
                  fragment: Iso15118_2Codec.encodeFragment_ContractSignatureEncryptedPrivateKey(
                                element.contractSignatureEncryptedPrivateKey) },
                { uri: "#" + element.dHpublickey.id,
                  fragment: Iso15118_2Codec.encodeFragment_DHpublickey(element.dHpublickey) },
                { uri: "#" + element.eMAID.id,
                  fragment: Iso15118_2Codec.encodeFragment_eMAID(element.eMAID) }];

    if (element instanceof ChargeParameterDiscoveryResType) {
        const offer = element.sASchedules;
        if (!(offer instanceof SAScheduleListType)) return null;
        return offer.sAScheduleTuple
                    .map(tuple => tuple.salesTariff)
                    .filter((tariff): tariff is SalesTariffType => tariff?.id != null)
                    .map(tariff => ({ uri: "#" + tariff.id,
                                      fragment: Iso15118_2Codec.encodeFragment_SalesTariff(tariff) }));
    }

    return null;
}


test("the digest of every signed -2 message re-derives from its own frame", async () => {

    /** @see the assertion at the end for why this list is spelled out rather than counted. */
    const expected = ["AuthorizationReqType", "MeteringReceiptReqType",
                      "ChargeParameterDiscoveryResType",
                      "CertificateInstallationReqType", "CertificateUpdateReqType",
                      "CertificateInstallationResType", "CertificateUpdateResType"];

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

            const signed = signedElementsOf(element);
            assert.notEqual(signed, null,
                            `${name} seq ${event.seq}: no fragment encoder for ${element?.constructor?.name}`);

            const references = Array.isArray(signature.signedInfo.reference)
                                   ? signature.signedInfo.reference
                                   : [signature.signedInfo.reference];

            // One reference per signed element, matched by URI rather than by position. A tariff
            // signature carries one per SalesTariff, and comparing them in offer order would pass a
            // SignedInfo that referenced the same tariff twice.
            assert.equal(references.length, signed!.length,
                         `${name} seq ${event.seq}: ${signed!.length} signed element(s), `
                       + `${references.length} reference(s)`);

            for (const { uri, fragment } of signed!) {

                // `uri` — the JSON-LD name, which is not the codec's `uRI`. Nothing caught the
                // difference before because a one-reference signature never had to be matched.
                const reference = references.find(r => String(r.uri) === uri);
                assert.notEqual(reference, undefined,
                                `${name} seq ${event.seq}: nothing references ${uri}`);

                assert.equal(await sha256(fragment), String(reference.digestValue).toLowerCase(),
                             `${name} seq ${event.seq} (${event.messageName}) ${uri}: the digest `
                           + "re-derived here differs from the one C# recorded — this back end's "
                           + "fragment encoder and C#'s disagree about the canonical EXI of the "
                           + "signed element");
            }

            checkedNames.push(String(event.messageName));
            checked++;
        }
    }

    // Named rather than counted, because these are the *kinds* of -2 signature this project produces
    // — the signed AuthorizationReq that authorizes, the signed MeteringReceiptReq that countersigns
    // the station's meter reading, and the signed SalesTariffs a station offers under §7.9.2.5 — and
    // each exercises a different fragment encoder. A count would go on passing if one of them stopped
    // being recorded, which is how the tariff shape could have gone missing again after being added.
    assert.deepEqual(checkedNames.sort(), expected.sort(),
                     `checked ${checked} signed -2 message(s): ${checkedNames.join(", ")}`);
});


test("a digest check that cannot see the content fails rather than passing quietly", async () => {

    const events = sessions["iso2-ac-pnc"] ?? [];
    const signed = events.find(e => e?.json?.header?.signature);
    assert.notEqual(signed, undefined);

    const payload = parseHex(signed.exi).slice(V2GTP_HEADER_BYTES);
    const message = Iso15118_2Codec.decodeAny(payload) as V2G_Message;
    const fragment = signedElementsOf(message.body.bodyElement)![0].fragment;

    // One bit of the *content* moves and the digest must move with it. Without this, a check that
    // hashed a constant would pass the test above just as happily.
    const tampered = new Uint8Array(fragment);
    tampered[tampered.length - 1] ^= 0x01;

    assert.notEqual(await sha256(tampered), await sha256(fragment));
});
