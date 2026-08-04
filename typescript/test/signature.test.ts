import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { webcrypto } from "node:crypto";

import { Iso15118_2Codec } from "../src/iso2/Iso15118_2Codec.ts";
import { V2G_Message } from "../src/iso2/V2G_Message.ts";
import { PaymentDetailsReqType } from "../src/iso2/PaymentDetailsReqType.ts";
import type { SignedInfoType as Iso2SignedInfo } from "../src/iso2/SignedInfoType.ts";

import { XmlDsigCodec } from "../src/xmldsig/XmlDsigCodec.ts";
import { SignedInfoType } from "../src/xmldsig/SignedInfoType.ts";
import { CanonicalizationMethodType } from "../src/xmldsig/CanonicalizationMethodType.ts";
import { SignatureMethodType } from "../src/xmldsig/SignatureMethodType.ts";
import { ReferenceType } from "../src/xmldsig/ReferenceType.ts";
import { TransformsType } from "../src/xmldsig/TransformsType.ts";
import { TransformType } from "../src/xmldsig/TransformType.ts";
import { DigestMethodType } from "../src/xmldsig/DigestMethodType.ts";

/**
 * Who signed a recorded message — verified for real, against the contract certificate the session
 * presented.
 *
 * ## The grammar is the whole test
 *
 * `SignedInfo` is signed as a **standalone** xmldsig fragment, built from `xmldsig-core-schema.xsd`
 * alone, and *not* as a fragment of the -2 schema set. Both decode identically; they encode
 * differently, because an EXI fragment's top-level event-code width tracks the schema's
 * global-element count. For these messages that is **209 bytes against 210** — the same pair
 * `docs/phase5-report.md` records from the live Josev work, reproduced here by a codec generated for
 * this back end afterwards.
 *
 * So the first assertion below is not decoration. Verifying with the -2 form would reject a
 * perfectly good signature, and a signature view that cried wolf would be worse than one that stayed
 * quiet — which is exactly the mistake this test was written to prevent, after measuring 210 and
 * watching the verification fail.
 *
 * ## No key material is checked in for this
 *
 * The public key comes out of the contract certificate in the recording's own `PaymentDetailsReq`,
 * by the same DER walk the browser does — the app has no X.509 parser and `crypto.subtle` imports a
 * SubjectPublicKeyInfo and nothing larger.
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

const bytesOf = (hex: string) =>
    new Uint8Array((hex.match(/.{1,2}/g) ?? []).map(b => parseInt(b, 16)));

const messageOf = (frameHex: string) =>
    Iso15118_2Codec.decodeAny(bytesOf(frameHex).slice(V2GTP_HEADER_BYTES)) as V2G_Message;


/** The DER walk the browser needs, mirrored here so the test exercises the same path. */
function subjectPublicKeyInfo(der: Uint8Array): Uint8Array | null {

    const tlv = (at: number) => {
        if (at + 2 > der.length) return null;
        const tag = der[at];
        let length = der[at + 1];
        let from = at + 2;
        if (length & 0x80) {
            const count = length & 0x7f;
            if (count === 0 || count > 4 || from + count > der.length) return null;
            length = 0;
            for (let i = 0; i < count; i++) length = (length << 8) | der[from + i];
            from += count;
        }
        const to = from + length;
        return to <= der.length ? { tag, from, to } : null;
    };

    const certificate = tlv(0);
    if (certificate === null || certificate.tag !== 0x30) return null;
    const tbs = tlv(certificate.from);
    if (tbs === null || tbs.tag !== 0x30) return null;

    let at = tbs.from;
    const first = tlv(at);
    if (first === null) return null;
    if (first.tag === 0xa0) at = first.to;

    for (let skip = 0; skip < 5; skip++) {
        const field = tlv(at);
        if (field === null) return null;
        at = field.to;
    }

    const spki = tlv(at);
    return spki !== null && spki.tag === 0x30 ? der.slice(at, spki.to) : null;
}

function toStandalone(s: Iso2SignedInfo): SignedInfoType {
    return new SignedInfoType(
        s.id,
        new CanonicalizationMethodType(s.canonicalizationMethod.algorithm, s.canonicalizationMethod.aNY),
        new SignatureMethodType(s.signatureMethod.algorithm, s.signatureMethod.hMACOutputLength,
                                s.signatureMethod.aNY),
        s.reference.map(r => new ReferenceType(
            r.id, r.type, r.uRI,
            r.transforms === null ? null
                : new TransformsType(r.transforms.transform.map(
                      t => new TransformType(t.algorithm, t.xPath, t.aNY))),
            new DigestMethodType(r.digestMethod.algorithm, r.digestMethod.aNY),
            r.digestValue)));
}


const pncSession = sessions["iso2-ac-pnc"] ?? [];
const signedEvents = pncSession.filter(e => e?.json?.header?.signature);
const detailsEvent = pncSession.find(
    e => String(e.messageName ?? "").startsWith("PaymentDetailsReq"));


test("the two SignedInfo grammars differ, and the standalone one is what was signed", () => {

    assert.ok(signedEvents.length > 0, "the PnC corpus should carry signed messages");

    for (const event of signedEvents) {

        const signedInfo = messageOf(event.exi).header.signature!.signedInfo;

        const combined  = Iso15118_2Codec.encodeFragment_SignedInfo(signedInfo);
        const standalone = XmlDsigCodec.encodeFragment_SignedInfo(toStandalone(signedInfo));

        assert.notEqual(combined.length, standalone.length,
                        `${event.messageName}: the two grammars produced the same length, so this `
                      + "test can no longer tell them apart");
        assert.equal(standalone.length, 209, event.messageName);
        assert.equal(combined.length,   210, event.messageName);
    }
});


test("every signed -2 message verifies against the session's contract certificate", async () => {

    assert.notEqual(detailsEvent, undefined, "no PaymentDetailsReq in the PnC recording");

    const details = messageOf(detailsEvent.exi).body.bodyElement;
    assert.ok(details instanceof PaymentDetailsReqType);

    const spki = subjectPublicKeyInfo(details.contractSignatureCertChain.certificate);
    assert.notEqual(spki, null, "the DER walk did not find a SubjectPublicKeyInfo");

    const key = await webcrypto.subtle.importKey(
        "spki", spki! as BufferSource, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);

    for (const event of signedEvents) {

        const signature = messageOf(event.exi).header.signature!;
        const octets = XmlDsigCodec.encodeFragment_SignedInfo(toStandalone(signature.signedInfo));

        // Raw r‖s, 64 bytes — what ISO 15118 puts on the wire and what WebCrypto expects. The
        // DER-wrapped form every other ECDSA API defaults to would be rejected here.
        assert.equal(signature.signatureValue.value.length, 64, event.messageName);

        assert.equal(await webcrypto.subtle.verify(
                         { name: "ECDSA", hash: "SHA-256" }, key,
                         signature.signatureValue.value as BufferSource, octets as BufferSource),
                     true,
                     `${event.messageName}: the recorded signature does not verify against the `
                   + "contract certificate the same recording presented");
    }
});


test("verification fails on the -2 grammar, which is why the second codec exists", async () => {

    const details = messageOf(detailsEvent.exi).body.bodyElement as PaymentDetailsReqType;
    const key = await webcrypto.subtle.importKey(
        "spki", subjectPublicKeyInfo(details.contractSignatureCertChain.certificate)! as BufferSource,
        { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);

    for (const event of signedEvents) {

        const signature = messageOf(event.exi).header.signature!;

        assert.equal(await webcrypto.subtle.verify(
                         { name: "ECDSA", hash: "SHA-256" }, key,
                         signature.signatureValue.value as BufferSource,
                         Iso15118_2Codec.encodeFragment_SignedInfo(signature.signedInfo) as BufferSource),
                     false,
                     `${event.messageName}: the -2 combined form verified too, so the grammars are `
                   + "no longer distinguishable and the standalone codec is not earning its keep");
    }
});


test("a tampered signature does not verify", async () => {

    const details = messageOf(detailsEvent.exi).body.bodyElement as PaymentDetailsReqType;
    const key = await webcrypto.subtle.importKey(
        "spki", subjectPublicKeyInfo(details.contractSignatureCertChain.certificate)! as BufferSource,
        { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);

    const signature = messageOf(signedEvents[0].exi).header.signature!;
    const octets = XmlDsigCodec.encodeFragment_SignedInfo(toStandalone(signature.signedInfo));

    const tampered = new Uint8Array(signature.signatureValue.value);
    tampered[tampered.length - 1] ^= 0x01;

    assert.equal(await webcrypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key,
                                               tampered as BufferSource, octets as BufferSource),
                 false);
});
