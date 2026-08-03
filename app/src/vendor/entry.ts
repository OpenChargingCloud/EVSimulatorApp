import { EvSimulator } from "@open-charging-cloud/capacitor-ev-simulator";
import { bundleTraces } from "@open-charging-cloud/capacitor-ev-simulator/src/web.ts";
import type { SessionTrace } from "@open-charging-cloud/v2g-exi/src/bridge/replay.ts";
import type { SessionConfig } from "@open-charging-cloud/v2g-exi/src/bridge/plugin.ts";

import { Iso15118_2Codec } from "@open-charging-cloud/v2g-exi/src/iso2/Iso15118_2Codec.ts";
import { V2G_Message } from "@open-charging-cloud/v2g-exi/src/iso2/V2G_Message.ts";
import { AuthorizationReqType } from "@open-charging-cloud/v2g-exi/src/iso2/AuthorizationReqType.ts";
import { MeteringReceiptReqType } from "@open-charging-cloud/v2g-exi/src/iso2/MeteringReceiptReqType.ts";

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

export { EvSimulator, digestOfFrame };
