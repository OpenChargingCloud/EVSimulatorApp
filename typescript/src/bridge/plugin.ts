import type { BridgeEvent } from "./events.ts";

/**
 * The bridge's command surface — everything the WebView may ask the native side to do
 * (`docs/CONCEPT.md` B1).
 *
 * ## Why this file imports nothing from Capacitor
 *
 * Capacitor is how these calls *travel*, not what they *are*. Keeping the contract free of it means
 * the whole surface can be typed, reviewed and tested with no packages installed — and it makes the
 * dependency exactly one adapter of about forty lines per platform, rather than something threaded
 * through every signature.
 *
 * When Capacitor is added, `registerPlugin<EvSimulatorPlugin>("EvSimulator")` returns something
 * assignable to this interface, and nothing here changes.
 *
 * ## Three commands, and no more
 *
 * The event stream is a record rather than a channel, so everything a page can *cause* is here and
 * can be counted. A fourth command is a decision, not a detail.
 */
export interface EvSimulatorPlugin {

    /**
     * Starts a session against a station, and begins the event stream.
     *
     * @returns the session's id, which every later call names.
     */
    start(options: SessionConfig): Promise<{ sessionId: string }>;

    /** Stops a running session. Idempotent: stopping a finished session is not an error. */
    stop(options: { sessionId: string }): Promise<void>;

    /**
     * Subscribes to the event stream.
     *
     * @returns a handle that removes the listener — a page that navigates away without calling it
     *          leaks the subscription, which on a phone means a session that keeps running.
     */
    addListener(
        eventName: "v2gEvent",
        listener: (event: BridgeEvent) => void,
    ): Promise<{ remove: () => Promise<void> }>;
}


/**
 * What a session needs to know before it can start.
 *
 * This is the *scanned pairing code's* content plus the choices a user makes on the confirmation
 * sheet — deliberately, so that the only path into a session is one a human approved. `V2GPairing`
 * produces the first part; nothing here re-parses a QR code.
 */
export interface SessionConfig {

    /** Where the station is. An address literal or a `.local` name — never resolved by the app. */
    readonly host: string;
    readonly port: number;

    /** `tls` or `tcp`. Plaintext is a warning on the sheet, not a default. */
    readonly transport: "tls" | "tcp";

    /** `iso15118-2` or `iso15118-20`. */
    readonly protocol: "iso15118-2" | "iso15118-20";

    /** `ac` or `dc`. */
    readonly mode: "ac" | "dc";

    /** `eim` for external payment, `pnc` for Plug & Charge. */
    readonly authorization: "eim" | "pnc";

    /**
     * The TOTP read off the station's display, if the code carried one.
     *
     * Sent as it was read, never as this device thinks the time is — the phone's clock is not
     * trustworthy, and the station decides (§4.6).
     */
    readonly totp?: string;

    /** The root certificate fingerprint the pairing code pinned, if it carried one. */
    readonly rootFingerprint?: string;
}
