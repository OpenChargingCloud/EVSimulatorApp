import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { webcrypto } from "node:crypto";

import { CommonMessagesCodec } from "../src/iso20common/CommonMessagesCodec.ts";
import { AuthorizationReq } from "../src/iso20common/AuthorizationReq.ts";
import type { SignedInfoType as Iso20SignedInfo } from "../src/iso20common/SignedInfoType.ts";

import { XmlDsigCodec } from "../src/xmldsig/XmlDsigCodec.ts";
import { SignedInfoType } from "../src/xmldsig/SignedInfoType.ts";
import { CanonicalizationMethodType } from "../src/xmldsig/CanonicalizationMethodType.ts";
import { SignatureMethodType } from "../src/xmldsig/SignatureMethodType.ts";
import { ReferenceType } from "../src/xmldsig/ReferenceType.ts";
import { TransformsType } from "../src/xmldsig/TransformsType.ts";
import { TransformType } from "../src/xmldsig/TransformType.ts";
import { DigestMethodType } from "../src/xmldsig/DigestMethodType.ts";

/**
 * A recorded ISO 15118-20 Plug & Charge signature, re-derived and verified here.
 *
 * The -2 half of this has existed since 2026-08-03 (`digest.test.ts`, `signature.test.ts`); until
 * the -20 codecs arrived a day later, every -20 message in the inspector answered `unchecked` and
 * said so. This is the same two questions asked of the other protocol, and -20 differs in both.
 *
 * ## What is signed is not the message
 *
 * ISO 15118-2 signs the whole body element. ISO 15118-20 signs a *part* of one:
 * `PnC_AReqAuthorizationMode` — the challenge and the contract chain inside an `AuthorizationReq` —
 * referenced from the header by its `Id`. Digesting the request whole would produce a value matching
 * nothing, and on a screen that reads as tampering rather than as a mistake in the reader.
 *
 * ## Where the certificate is differs too
 *
 * -2 sends the chain in an earlier `PaymentDetailsReq`; -20 puts it inside the fragment the
 * signature covers. So a -20 message answers for itself — and that is not the weaker arrangement:
 * a chain covered by the signature that authenticates it cannot be swapped without breaking it.
 *
 * ## The grammar is the same trap as -2's
 *
 * `SignedInfo` is signed in the **standalone** xmldsig grammar here as well, not as a fragment of
 * the -20 schema set — even though CommonMessages carries its own copy of `xmldsig-core-schema.xsd`
 * and generates a perfectly good `encodeFragment_SignedInfo` of its own. Using that one would reject
 * a valid signature. The last test below is what keeps that honest.
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

const requestOf = (frameHex: string) =>
    CommonMessagesCodec.decodeAny(bytesOf(frameHex).slice(V2GTP_HEADER_BYTES)) as AuthorizationReq;


/** The DER walk the browser needs; mirrored from `signature.test.ts`, which explains why. */
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

function toStandalone(s: Iso20SignedInfo): SignedInfoType {
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


const signedEvent = (sessions["iso20-dc-pnc"] ?? [])
    .find(e => e?.json?.header?.signature && e.payloadType === "0x8002");


test("the -20 signature covers PnC_AReqAuthorizationMode, and this back end reproduces its digest", async () => {

    assert.notEqual(signedEvent, undefined, "no signed CommonMessages frame in the -20 PnC recording");

    const request = requestOf(signedEvent.exi);
    assert.notEqual(request.pnC_AReqAuthorizationMode, null,
                    "a -20 PnC AuthorizationReq without the PnC mode is an EIM one under another name");

    const fragment = CommonMessagesCodec.encodeFragment_PnC_AReqAuthorizationMode(
                         request.pnC_AReqAuthorizationMode!);

    const derived = [...new Uint8Array(await webcrypto.subtle.digest("SHA-256", fragment as BufferSource))]
                    .map(b => b.toString(16).padStart(2, "0")).join("");

    const reference = request.header.signature!.signedInfo.reference[0];
    const claimed   = [...reference.digestValue].map(b => b.toString(16).padStart(2, "0")).join("");

    assert.equal(derived, claimed,
                 "the digest C# recorded is not the one this fragment encoder produces — the two "
               + "back ends disagree about which octets a -20 signature covers");

    // …and the reference really points at the fragment that was digested, by Id.
    assert.equal(reference.uRI, "#" + request.pnC_AReqAuthorizationMode!.id);
});


test("the recorded -20 signature verifies against the chain inside the message it signs", async () => {

    const request = requestOf(signedEvent.exi);
    const mode    = request.pnC_AReqAuthorizationMode!;
    const signature = request.header.signature!;

    const spki = subjectPublicKeyInfo(mode.contractCertificateChain.certificate);
    assert.notEqual(spki, null, "the DER walk did not find a SubjectPublicKeyInfo");

    const key = await webcrypto.subtle.importKey(
        "spki", spki! as BufferSource, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);

    // Raw r‖s, as in -2. -20's mandatory suite is stronger in general, and this contract key is
    // P-256 — a P-521 one would want SHA-512 and 132 bytes.
    assert.equal(signature.signatureValue.value.length, 64);

    assert.equal(await webcrypto.subtle.verify(
                     { name: "ECDSA", hash: "SHA-256" }, key,
                     signature.signatureValue.value as BufferSource,
                     XmlDsigCodec.encodeFragment_SignedInfo(toStandalone(signature.signedInfo)) as BufferSource),
                 true,
                 "the recorded -20 signature does not verify against the contract chain the same "
               + "message carries");
});


test("the -20 set's own SignedInfo grammar does not verify, which is why the standalone codec exists", async () => {

    const request   = requestOf(signedEvent.exi);
    const signature = request.header.signature!;
    const spki      = subjectPublicKeyInfo(request.pnC_AReqAuthorizationMode!.contractCertificateChain.certificate)!;

    const key = await webcrypto.subtle.importKey(
        "spki", spki as BufferSource, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);

    const own = CommonMessagesCodec.encodeFragment_SignedInfo(signature.signedInfo);
    const standalone = XmlDsigCodec.encodeFragment_SignedInfo(toStandalone(signature.signedInfo));

    assert.notEqual(own.length, standalone.length,
                    "the two grammars produced the same length, so this test can no longer tell "
                  + "them apart");

    assert.equal(await webcrypto.subtle.verify(
                     { name: "ECDSA", hash: "SHA-256" }, key,
                     signature.signatureValue.value as BufferSource, own as BufferSource),
                 false,
                 "the -20 set's own SignedInfo fragment verified too, so the grammars are no longer "
               + "distinguishable and the standalone codec is not earning its keep here");
});


test("a tampered -20 signature does not verify", async () => {

    const request   = requestOf(signedEvent.exi);
    const signature = request.header.signature!;
    const spki      = subjectPublicKeyInfo(request.pnC_AReqAuthorizationMode!.contractCertificateChain.certificate)!;

    const key = await webcrypto.subtle.importKey(
        "spki", spki as BufferSource, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);

    const tampered = new Uint8Array(signature.signatureValue.value);
    tampered[tampered.length - 1] ^= 0x01;

    assert.equal(await webcrypto.subtle.verify(
                     { name: "ECDSA", hash: "SHA-256" }, key, tampered as BufferSource,
                     XmlDsigCodec.encodeFragment_SignedInfo(toStandalone(signature.signedInfo)) as BufferSource),
                 false);
});
