import type { SessionConfig } from "@open-charging-cloud/v2g-exi/src/bridge/plugin.ts";

/**
 * What actually crosses the Capacitor bridge — as opposed to {@link EvSimulatorPlugin}, which is
 * what the application deals in.
 *
 * The two differ in exactly two places, and both differences are here rather than in the contract
 * because they are facts about the transport.
 *
 * ## 1. An event travels as text
 *
 * `notifyListeners` takes a dictionary, and a dictionary is where this project's one JSON guarantee
 * dies. Measured on all 196 events of `Vectors/Bridge.events.json`, on both platforms:
 *
 * - Android — `JSObject` is an `org.json.JSONObject`, and a round trip through it changed **196 of
 *   196** events. Member order is gone at every level, so `@context` and `@type` land wherever the
 *   hash function puts them.
 * - iOS — `JSONSerialization` is the only way to build the `[String: Any]` the bridge takes, and it
 *   changed **196 of 196** as well, in a *different* order from Android's.
 *
 * None of that is a bug in either library: member order is semantically insignificant in JSON, and
 * both are entitled to drop it. But the JSON-LD documents in this repository are pinned as **text**
 * across four back ends, and a document that arrives in a third order on Android and a fourth on iOS
 * is no longer the document the corpus agreed on — an inspector would show three different renderings
 * of one message and a consumer hashing it would get three different hashes.
 *
 * So the event crosses as the string the back ends already agree on, and `JSON.parse` — whose object
 * key order the language specification fixes — rebuilds it here. One JSON implementation in the path
 * instead of three.
 *
 * ## 2. The configuration travels nested
 *
 * Capacitor puts its own `callbackId` into a call's options. `SessionConfig` refuses unknown
 * properties — deliberately, since a silently ignored setting is one the user believes they approved
 * — so the config gets a key of its own and the native side reads exactly that.
 */
export interface EvSimulatorNative {

    start(options: { config: SessionConfig }): Promise<{ sessionId: string }>;

    stop(options: { sessionId: string }): Promise<void>;

    addListener(
        eventName: "v2gEvent",
        listener: (payload: { event: string }) => void,
    ): Promise<{ remove: () => Promise<void> }>;
}
