import { EvSimulator } from "@open-charging-cloud/capacitor-ev-simulator";
import { bundleTraces } from "@open-charging-cloud/capacitor-ev-simulator/src/web.ts";
import type { SessionTrace } from "@open-charging-cloud/v2g-exi/src/bridge/replay.ts";
import type { SessionConfig } from "@open-charging-cloud/v2g-exi/src/bridge/plugin.ts";

import { Iso15118_2Codec } from "@open-charging-cloud/v2g-exi/src/iso2/Iso15118_2Codec.ts";
import { V2G_Message } from "@open-charging-cloud/v2g-exi/src/iso2/V2G_Message.ts";
import { AuthorizationReqType } from "@open-charging-cloud/v2g-exi/src/iso2/AuthorizationReqType.ts";
import { MeteringReceiptReqType } from "@open-charging-cloud/v2g-exi/src/iso2/MeteringReceiptReqType.ts";
import { PaymentDetailsReqType } from "@open-charging-cloud/v2g-exi/src/iso2/PaymentDetailsReqType.ts";

import type { SignedInfoType as Iso2SignedInfo } from "@open-charging-cloud/v2g-exi/src/iso2/SignedInfoType.ts";
import { XmlDsigCodec } from "@open-charging-cloud/v2g-exi/src/xmldsig/XmlDsigCodec.ts";
import { SignedInfoType } from "@open-charging-cloud/v2g-exi/src/xmldsig/SignedInfoType.ts";
import { CanonicalizationMethodType } from "@open-charging-cloud/v2g-exi/src/xmldsig/CanonicalizationMethodType.ts";
import { SignatureMethodType } from "@open-charging-cloud/v2g-exi/src/xmldsig/SignatureMethodType.ts";
import { ReferenceType } from "@open-charging-cloud/v2g-exi/src/xmldsig/ReferenceType.ts";
import { TransformsType } from "@open-charging-cloud/v2g-exi/src/xmldsig/TransformsType.ts";
import { TransformType } from "@open-charging-cloud/v2g-exi/src/xmldsig/TransformType.ts";
import { DigestMethodType } from "@open-charging-cloud/v2g-exi/src/xmldsig/DigestMethodType.ts";

import acEim from "../../../libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Simulation.Tests/Vectors/Session.iso2-ac-eim.trace.json" with { type: "json" };
import acPnc from "../../../libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Simulation.Tests/Vectors/Session.iso2-ac-pnc.trace.json" with { type: "json" };
import dcEim from "../../../libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Simulation.Tests/Vectors/Session.iso2-dc-eim.trace.json" with { type: "json" };

/**
 * The bundle's entry point, and the one place that decides which recordings this build carries.
 *
 * ## Why this file is TypeScript and everything else in `app/` is not
 *
 * It is the only file here that imports the plugin, and through it the EXI codec — which is
 * TypeScript, and which a browser cannot load. So this is the seam: the bundler crosses it once,
 * producing `app/vendor/ev-simulator.js`, and every other source in `app/` stays plain ES modules
 * that Node runs directly for the tests and a browser runs directly without a build.
 *
 * ## Three sessions, and why not six
 *
 * The generator has emitted the SupportedAppProtocol and ISO 15118-2 codecs for TypeScript and not
 * yet the -20 sets. A -20 recording would replay as error events naming payload types, so the plugin
 * refuses that protocol outright rather than delivering it — and bundling a trace it would refuse
 * would be shipping a promise this build cannot keep.
 */
const TRACES: Record<string, unknown> = {
    "iso15118-2/ac/eim": acEim,
    "iso15118-2/ac/pnc": acPnc,
    "iso15118-2/dc/eim": dcEim,
};

bundleTraces((config: SessionConfig) =>
    TRACES[`${config.protocol}/${config.mode}/${config.authorization}`] as SessionTrace | undefined);


const V2GTP_HEADER_BYTES = 8;

/**
 * The digest a signed message's own frame actually produces.
 *
 * The session inspector shows what a signature *claims* — which element it covers, and the digest
 * over it — and says on screen that it verified nothing. This is what lets it stop saying that for
 * ISO 15118-2: decode the frame, re-encode the covered element as canonical EXI, SHA-256 it.
 *
 * **No key is involved**, and that is the point. A matching digest says the signature covers *this
 * content* — that nobody altered the message under a signature that still parses. Whether the right
 * party signed it is a different question, needing the contract certificate from earlier in the
 * session, and this function deliberately does not pretend to answer it.
 *
 * It lives in the bundle because the codec does, and the app's other sources are plain ES modules a
 * browser and Node run unaltered. `null` for anything this build cannot re-encode: the -20 sets are
 * not emitted for TypeScript, and only three fragment encoders exist for -2. A caller must be able
 * to tell "not checked" from "wrong", so the absence is a value rather than an exception.
 *
 * Held to the recorded corpus by `typescript/test/digest.test.ts`, which is also the first oracle
 * this back end's fragment encoders have ever had: the digests it reproduces were computed by C#.
 */
async function digestOfFrame(frameHex: string): Promise<string | null> {

    try {

        const bytes = new Uint8Array((frameHex.match(/.{1,2}/g) ?? []).map(b => parseInt(b, 16)));
        if (bytes.length <= V2GTP_HEADER_BYTES) return null;

        const decoded = Iso15118_2Codec.decodeAny(bytes.slice(V2GTP_HEADER_BYTES));
        if (!(decoded instanceof V2G_Message)) return null;

        const element = decoded.body.bodyElement;
        const fragment = element instanceof AuthorizationReqType
                             ? Iso15118_2Codec.encodeFragment_AuthorizationReq(element)
                       : element instanceof MeteringReceiptReqType
                             ? Iso15118_2Codec.encodeFragment_MeteringReceiptReq(element)
                       : null;

        if (fragment === null) return null;

        const digest = await crypto.subtle.digest("SHA-256", fragment as BufferSource);
        return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("");

    } catch {
        // A frame this build cannot read is "not checked", not "wrong". The inspector already shows
        // the frame's own problems; a decode failure here would be saying the same thing twice, in a
        // place where it reads as a verdict on the signature.
        return null;
    }

}

/**
 * The SubjectPublicKeyInfo of a DER X.509 certificate, as the bytes WebCrypto imports.
 *
 * A browser has no certificate parser — `crypto.subtle` imports an SPKI and nothing larger — so the
 * seven fields in front of it have to be stepped over by hand. This walks the two nested SEQUENCEs
 * and skips, it does not interpret: tag, length, jump. Everything it could get wrong ends in
 * `importKey` refusing the bytes, which is the failure mode to want — a mis-parse cannot become a
 * wrong answer, only a missing one.
 *
 * ```
 * Certificate     ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signature }
 * TBSCertificate  ::= SEQUENCE { [0] version, serialNumber, signature, issuer,
 *                                validity, subject, subjectPublicKeyInfo, ... }
 * ```
 */
function subjectPublicKeyInfo(der: Uint8Array): Uint8Array | null {

    /** Reads one TLV at `at`, returning where its value starts and ends. */
    const tlv = (at: number): { tag: number; from: number; to: number } | null => {

        if (at + 2 > der.length) return null;

        const tag = der[at];
        let length = der[at + 1];
        let from = at + 2;

        if (length & 0x80) {                       // long form: the low bits count the length's bytes
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

    // `[0] EXPLICIT version` is optional and, being context-tagged, is the one field that may or may
    // not be there. Everything after it is positional, which is what makes counting safe.
    const first = tlv(at);
    if (first === null) return null;
    if (first.tag === 0xa0) at = first.to;

    // serialNumber, signature, issuer, validity, subject — then SubjectPublicKeyInfo.
    for (let skip = 0; skip < 5; skip++) {
        const field = tlv(at);
        if (field === null) return null;
        at = field.to;
    }

    const spki = tlv(at);
    return spki !== null && spki.tag === 0x30 ? der.slice(at, spki.to) : null;
}


/** The -2 `SignedInfo`, in the standalone-xmldsig type graph its signature was computed over. */
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


/**
 * Whether a signed ISO 15118-2 message was signed by the contract certificate the session presented.
 *
 * The digest says a signature covers this content; this says **who** covered it. The certificate is
 * not taken on the message's word — it comes from the `PaymentDetailsReq` the EV sent earlier in the
 * same session, which is where a station gets it from too.
 *
 * ## The grammar is the whole difficulty, and it is why this needed a new codec
 *
 * `SignedInfo` is signed as a **standalone** xmldsig fragment — a grammar built from
 * `xmldsig-core-schema.xsd` alone — not as a fragment of the -2 schema set. The two decode
 * identically and encode differently: for these messages, 209 bytes against 210, because an EXI
 * fragment's top-level event-code width tracks the schema's global-element count. Verifying with the
 * -2 form would reject a perfectly good signature, which is the one outcome worse than not checking.
 * `typescript/src/xmldsig/` is that second grammar, generated for this.
 *
 * `null` when the answer is unavailable — no certificate in the session, a message this build cannot
 * re-encode, a certificate whose SubjectPublicKeyInfo will not import. Never `false` for those: a
 * caller must be able to tell "not checked" from "the wrong party signed this".
 */
async function verifySignatureOfFrame(signedFrameHex: string,
                                      certificateFrameHex: string): Promise<boolean | null> {

    try {

        const bytesOf = (h: string) =>
            new Uint8Array((h.match(/.{1,2}/g) ?? []).map(b => parseInt(b, 16)));

        const details = Iso15118_2Codec.decodeAny(bytesOf(certificateFrameHex).slice(V2GTP_HEADER_BYTES));
        if (!(details instanceof V2G_Message)) return null;
        const chain = details.body.bodyElement;
        if (!(chain instanceof PaymentDetailsReqType)) return null;

        const spki = subjectPublicKeyInfo(chain.contractSignatureCertChain.certificate);
        if (spki === null) return null;

        const signed = Iso15118_2Codec.decodeAny(bytesOf(signedFrameHex).slice(V2GTP_HEADER_BYTES));
        if (!(signed instanceof V2G_Message)) return null;
        const signature = signed.header.signature;
        if (signature === null || signature === undefined) return null;

        const key = await crypto.subtle.importKey(
            "spki", spki as BufferSource, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);

        // ISO 15118 carries the signature as raw r‖s, which is exactly what WebCrypto expects — the
        // DER-wrapped form every other ECDSA API defaults to would be rejected here.
        return await crypto.subtle.verify(
            { name: "ECDSA", hash: "SHA-256" }, key,
            signature.signatureValue.value as BufferSource,
            XmlDsigCodec.encodeFragment_SignedInfo(toStandalone(signature.signedInfo)) as BufferSource);

    } catch {
        return null;
    }

}

export { EvSimulator, digestOfFrame, verifySignatureOfFrame };
