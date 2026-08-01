import { registerPlugin } from "@capacitor/core";

import { parseBridgeEvent } from "@open-charging-cloud/v2g-exi/src/bridge/events.ts";
import type { BridgeEvent } from "@open-charging-cloud/v2g-exi/src/bridge/events.ts";
import type { EvSimulatorPlugin, SessionConfig }
    from "@open-charging-cloud/v2g-exi/src/bridge/plugin.ts";

import type { EvSimulatorNative } from "./definitions.ts";

export type { BridgeEvent, EvSimulatorPlugin, SessionConfig };

/**
 * The Capacitor adapter (`docs/CONCEPT.md` B1).
 *
 * Everything this file does is carry values across a boundary. There is no session logic here and
 * there should never be: this is the one file in the project that cannot be run by `npm test`,
 * `gradle test`, `swift test` or `dotnet test`, so anything decided here is decided where nothing
 * can check it. What a session *is* lives in `V2GBridge` / `v2g-bridge`, which four back ends agree
 * on character for character.
 *
 * The public shape is {@link EvSimulatorPlugin} — the contract written before Capacitor existed in
 * this repository, and unchanged by its arrival. {@link EvSimulatorNative} is what the bridge really
 * carries, and {@link adapt} is the whole difference between them.
 */

/**
 * Wraps the raw plugin in the contract the application uses.
 *
 * Separate from the `registerPlugin` call below so that it can be tested against a stand-in native
 * side, which is the only part of this file a laptop can check.
 */
export function adapt(native: EvSimulatorNative): EvSimulatorPlugin {
    return {

        start: (options: SessionConfig) => native.start({ config: options }),

        stop:  (options: { sessionId: string }) => native.stop(options),

        /**
         * Subscribes to the event stream.
         *
         * Every event is validated by {@link parseBridgeEvent} before the listener sees it, for the
         * reason that applies to everything arriving over a bridge: the native side is ours today,
         * and a stream replayed from a file, forwarded by a proxy or produced by an older build is
         * not. An unreadable event is dropped with a console error rather than thrown — throwing
         * here would unwind into Capacitor's event dispatch, where nobody is catching, and would
         * take the rest of the session's stream with it.
         */
        addListener: (eventName: "v2gEvent", listener: (event: BridgeEvent) => void) =>
            native.addListener(eventName, payload => {
                let event: BridgeEvent;
                try {
                    event = parseBridgeEvent(JSON.parse(payload.event));
                } catch (problem) {
                    console.error("EvSimulator: an unreadable event was dropped.", problem, payload);
                    return;
                }
                listener(event);
            }),
    };
}


/**
 * The plugin, as an application uses it.
 *
 * `"EvSimulator"` is the name the native halves register under — `@CapacitorPlugin(name =
 * "EvSimulator")` on Android and `identifier` on iOS. The three strings have to match and there is
 * no compiler that checks them, which is why they are named in one place per platform and nowhere
 * else.
 */
export const EvSimulator: EvSimulatorPlugin =
    adapt(registerPlugin<EvSimulatorNative>("EvSimulator"));
