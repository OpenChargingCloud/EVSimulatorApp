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
import type { SignedInfoType as Iso20SignedInfo } from "@open-charging-cloud/v2g-exi/src/iso20common/SignedInfoType.ts";
import { XmlDsigCodec } from "@open-charging-cloud/v2g-exi/src/xmldsig/XmlDsigCodec.ts";
import { SignedInfoType } from "@open-charging-cloud/v2g-exi/src/xmldsig/SignedInfoType.ts";
import { CanonicalizationMethodType } from "@open-charging-cloud/v2g-exi/src/xmldsig/CanonicalizationMethodType.ts";
import { SignatureMethodType } from "@open-charging-cloud/v2g-exi/src/xmldsig/SignatureMethodType.ts";
import { ReferenceType } from "@open-charging-cloud/v2g-exi/src/xmldsig/ReferenceType.ts";
import { TransformsType } from "@open-charging-cloud/v2g-exi/src/xmldsig/TransformsType.ts";
import { TransformType } from "@open-charging-cloud/v2g-exi/src/xmldsig/TransformType.ts";
import { DigestMethodType } from "@open-charging-cloud/v2g-exi/src/xmldsig/DigestMethodType.ts";

// ISO 15118-20 CommonMessages. Only this set, of the three: it is where AuthorizationReq lives, and
// the -20 signature this project produces covers a fragment of it. AC and DC have signable fragments
// too (their ChargeParameterDiscoveryRes), and nothing in this repository signs one — so importing
// their codecs would add half a megabyte to a WebView bundle to answer a question nobody asks.
import { CommonMessagesCodec } from "@open-charging-cloud/v2g-exi/src/iso20common/CommonMessagesCodec.ts";
import { AuthorizationReq } from "@open-charging-cloud/v2g-exi/src/iso20common/AuthorizationReq.ts";

// Checked-in copies of three recordings from the canonical corpus, which lives with the C# session
// tests that record it — in the ISO15118ConformanceTests repository, the parent that carries this
// app as a submodule. Copies because a build cannot skip: the tests reach for the corpus and skip
// standalone, but this import either resolves or there is no bundle. `app/test/traces.test.js` is
// the drift guard — under the conformance checkout it holds each copy byte-identical to the corpus.
import acEim from "./traces/Session.iso2-ac-eim.trace.json" with { type: "json" };
import acPnc from "./traces/Session.iso2-ac-pnc.trace.json" with { type: "json" };
import dcEim from "./traces/Session.iso2-dc-eim.trace.json" with { type: "json" };

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
 * The -20 codecs are generated for TypeScript since 2026-08-04, but the bridge does not carry them
 * yet (the roadmap's high-priority TODO). A -20 recording would replay as error events naming
 * payload types, so the plugin refuses that protocol outright rather than delivering it — and
 * bundling a trace it would refuse would be shipping a promise this build cannot keep.
 */
const TRACES: Record<string, unknown> = {
    "iso15118-2/ac/eim": acEim,
    "iso15118-2/ac/pnc": acPnc,
    "iso15118-2/dc/eim": dcEim,
};

bundleTraces((config: SessionConfig) =>
    TRACES[`${config.protocol}/${config.mode}/${config.authorization}`] as SessionTrace | undefined);


const V2GTP_HEADER_BYTES = 8;

const bytesOfHex = (hex: string) =>
    new Uint8Array((hex.match(/.{1,2}/g) ?? []).map(b => parseInt(b, 16)));

/**
 * Which message set a frame belongs to, from the two bytes that decide it.
 *
 * Never guessed from the content. `0x8001` carries the SupportedAppProtocol handshake *and* every
 * ISO 15118-2 message, and 0x8003 and 0x8004 are two whole grammars seven bits apart — decoding a
 * frame with the wrong set yields a message that looks fine.
 */
const payloadTypeOf = (frame: Uint8Array): number =>
    frame.length < V2GTP_HEADER_BYTES ? 0 : (frame[2] << 8) | frame[3];

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
 * browser and Node run unaltered. `null` for anything this build cannot re-encode: only a handful
 * of fragment encoders are carried — three for -2, one for -20 CommonMessages. A caller must be
 * able to tell "not checked" from "wrong", so the absence is a value rather than an exception.
 *
 * Held to the recorded corpus by `typescript/test/digest.test.ts`, which is also the first oracle
 * this back end's fragment encoders have ever had: the digests it reproduces were computed by C#.
 */
async function digestOfFrame(frameHex: string): Promise<string | null> {

    try {

        const bytes = bytesOfHex(frameHex);
        if (bytes.length <= V2GTP_HEADER_BYTES) return null;

        const fragment = fragmentOf(bytes);
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
/**
 * The SignedInfo of either protocol, as the standalone-xmldsig codec's own type.
 *
 * Structural rather than per-protocol: -2 and -20 CommonMessages each generate their own
 * `SignedInfoType` from their own copy of `xmldsig-core-schema.xsd`, and the two classes are
 * identical field for field. Both are signed in the *standalone* grammar, so both convert here and
 * one encoder serves them.
 */
function toStandalone(s: Iso2SignedInfo | Iso20SignedInfo): SignedInfoType {
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
 * The octets a signed message's own signature covers, re-encoded from the frame.
 *
 * Two protocols, and they differ in *what* is signed rather than merely in how. ISO 15118-2 signs
 * the whole body element — `AuthorizationReq`, `MeteringReceiptReq`. ISO 15118-20 signs a piece of
 * one: `PnC_AReqAuthorizationMode`, the part of an `AuthorizationReq` carrying the challenge and the
 * contract chain, referenced from the header by its `Id`. Encoding the -20 request whole would
 * produce a digest that matches nothing and looks like tampering.
 *
 * `null` for everything else, and there is plenty: -20 AC and DC have signable fragments this build
 * deliberately does not carry, and -2 has three fragment encoders rather than one per message.
 */
function fragmentOf(frame: Uint8Array): Uint8Array | null {

    const payload = frame.slice(V2GTP_HEADER_BYTES);

    if (payloadTypeOf(frame) === 0x8001) {
        const decoded = Iso15118_2Codec.decodeAny(payload);
        if (!(decoded instanceof V2G_Message)) return null;

        const element = decoded.body.bodyElement;
        return element instanceof AuthorizationReqType
                   ? Iso15118_2Codec.encodeFragment_AuthorizationReq(element)
             : element instanceof MeteringReceiptReqType
                   ? Iso15118_2Codec.encodeFragment_MeteringReceiptReq(element)
             : null;
    }

    if (payloadTypeOf(frame) === 0x8002) {
        const decoded = CommonMessagesCodec.decodeAny(payload);
        return decoded instanceof AuthorizationReq && decoded.pnC_AReqAuthorizationMode !== null
                   ? CommonMessagesCodec.encodeFragment_PnC_AReqAuthorizationMode(
                         decoded.pnC_AReqAuthorizationMode)
                   : null;
    }

    return null;
}


/**
 * The header signature of a frame of either protocol, and the contract certificate to check it with.
 *
 * The two protocols put the certificate in different places, and the difference is not cosmetic. In
 * -2 it arrives in an earlier `PaymentDetailsReq` and the signed message never carries it — so the
 * caller has to supply that frame. In -20 the chain travels *inside* the signed `AuthorizationReq`,
 * in the very fragment the signature covers, so the message is self-contained and the caller passes
 * the same frame twice.
 *
 * That is what the station does in each case, which is the standard this is held to — and it does
 * not make -20's answer weaker: a chain covered by the signature it authenticates cannot be swapped
 * without breaking the signature. What neither protocol's answer says is whether the certificate is
 * one anybody should trust.
 */
function signatureAndKeyOf(signedFrame: Uint8Array, certificateFrame: Uint8Array):
    { signedInfo: Iso2SignedInfo | Iso20SignedInfo; value: Uint8Array; spki: Uint8Array } | null {

    if (payloadTypeOf(signedFrame) === 0x8001) {

        const details = Iso15118_2Codec.decodeAny(certificateFrame.slice(V2GTP_HEADER_BYTES));
        if (!(details instanceof V2G_Message)) return null;
        const chain = details.body.bodyElement;
        if (!(chain instanceof PaymentDetailsReqType)) return null;

        const spki = subjectPublicKeyInfo(chain.contractSignatureCertChain.certificate);
        if (spki === null) return null;

        const signed = Iso15118_2Codec.decodeAny(signedFrame.slice(V2GTP_HEADER_BYTES));
        if (!(signed instanceof V2G_Message)) return null;
        const signature = signed.header.signature;
        if (signature === null || signature === undefined) return null;

        return { signedInfo: signature.signedInfo, value: signature.signatureValue.value, spki };
    }

    if (payloadTypeOf(signedFrame) === 0x8002) {

        const signed = CommonMessagesCodec.decodeAny(signedFrame.slice(V2GTP_HEADER_BYTES));
        if (!(signed instanceof AuthorizationReq)) return null;

        const mode = signed.pnC_AReqAuthorizationMode;
        const signature = signed.header.signature;
        if (mode === null || signature === null || signature === undefined) return null;

        const spki = subjectPublicKeyInfo(mode.contractCertificateChain.certificate);
        if (spki === null) return null;

        return { signedInfo: signature.signedInfo, value: signature.signatureValue.value, spki };
    }

    return null;
}


/**
 * Whether a signed message was signed by the contract certificate the session presented.
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

        const found = signatureAndKeyOf(bytesOfHex(signedFrameHex), bytesOfHex(certificateFrameHex));
        if (found === null) return null;

        const key = await crypto.subtle.importKey(
            "spki", found.spki as BufferSource, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);

        // ISO 15118 carries the signature as raw r‖s, which is exactly what WebCrypto expects — the
        // DER-wrapped form every other ECDSA API defaults to would be rejected here.
        return await crypto.subtle.verify(
            { name: "ECDSA", hash: "SHA-256" }, key,
            found.value as BufferSource,
            XmlDsigCodec.encodeFragment_SignedInfo(toStandalone(found.signedInfo)) as BufferSource);

    } catch {
        return null;
    }

}

export { EvSimulator, digestOfFrame, verifySignatureOfFrame };
