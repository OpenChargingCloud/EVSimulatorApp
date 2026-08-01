import { EvSimulator } from "@open-charging-cloud/capacitor-ev-simulator";
import { bundleTraces } from "@open-charging-cloud/capacitor-ev-simulator/src/web.ts";
import type { SessionTrace } from "@open-charging-cloud/v2g-exi/src/bridge/replay.ts";
import type { SessionConfig } from "@open-charging-cloud/v2g-exi/src/bridge/plugin.ts";

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

export { EvSimulator };
