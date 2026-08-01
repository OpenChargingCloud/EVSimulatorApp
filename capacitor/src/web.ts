import { WebPlugin } from "@capacitor/core";

import { replay, type SessionTrace } from "@open-charging-cloud/v2g-exi/src/bridge/replay.ts";
import type { SessionConfig } from "@open-charging-cloud/v2g-exi/src/bridge/plugin.ts";

import type { EvSimulatorNative } from "./definitions.ts";

/**
 * The plugin, in a browser.
 *
 * Capacitor routes `EvSimulator` here when there is no native side — which is every plain browser,
 * and every developer who has not built the shell. It replays a **recorded session**: the same
 * traces the four back ends are held to, turned into the same events by
 * `typescript/src/bridge/replay.ts`, which `replay.test.ts` requires to match
 * `Vectors/Bridge.events.json` character for character.
 *
 * ## What it is not
 *
 * **It cannot open a session**, and it is not pretending to. A browser cannot open a TCP socket, and
 * the EVCC state machines exist in three languages rather than four — so there is nothing here that
 * could talk to a station even if the transport existed. `tools/EVSimulatorApp.WsBridge` is the
 * transport half of that answer; the state machines are the other half and are not written.
 *
 * So this is for the inspector, for Chargy, and for working on the UI without a phone. Anything it
 * shows happened once, on a real wire, and was recorded — which is a good deal more than a mock.
 *
 * ## The events are the real events
 *
 * Not a fixture and not a summary: every message carries the JSON-LD this back end's codec produced
 * from the recorded frame, and the raw frame beside it, so the inspector's self-check — decode the
 * frame, compare with the document — passes here exactly as it does on a phone.
 */
export class EvSimulatorWeb extends WebPlugin implements EvSimulatorNative {

    /**
     * Where a recorded session comes from.
     *
     * Installed by the application rather than fetched, for two reasons. The page's content security
     * policy forbids `connect-src` outright — a scanned code must not be able to make anything fetch
     * — so a trace has to arrive as a module, not as a request. And *which* recordings a build ships
     * is a packaging decision, exactly as it is for `TraceSessionRunner` on the native side.
     */
    public traces: (config: SessionConfig) => SessionTrace | undefined = () => undefined;

    /**
     * How long to wait between events, in milliseconds.
     *
     * A recorded session has no timings of its own — the corpus is generated with a clock that steps
     * one millisecond per event, which is a test device rather than a pace. Replaying at that speed
     * would deliver a whole session before the first paint, so the inspector would show a finished
     * session and never a running one.
     */
    public pace = 120;


    private running = new Map<string, () => void>();
    private nextId  = 1;


    public async start(options: { config: SessionConfig }): Promise<{ sessionId: string }> {

        const config = options.config;
        const trace  = this.traces(config);

        if (trace === undefined)
            throw this.unavailable(
                `no recorded ${config.protocol} ${config.mode} session is bundled with this build.`);

        // Refused before anything starts, rather than delivered as a stream of errors. The codecs
        // this build carries are SupportedAppProtocol and ISO 15118-2; a -20 trace would replay as
        // twenty-odd error events naming payload types, which is not a session and would look like a
        // fault rather than a missing feature. `replay` itself does not special-case it — a frame it
        // cannot place becomes an error, as in every back end — because that judgement belongs here.
        if (config.protocol !== "iso15118-2")
            throw this.unavailable(
                `this build decodes ISO 15118-2 and the SupportedAppProtocol handshake. The -20 `
              + `codecs are not generated for TypeScript yet, so a ${config.protocol} session would `
              + `arrive as errors rather than messages.`);

        const events    = replay(trace, () => performance.now());
        const sessionId = `web-${this.nextId++}`;

        let cancelled = false;
        this.running.set(sessionId, () => { cancelled = true; });

        // Deliberately not awaited: `start` resolves with the id, and the events arrive afterwards —
        // the same shape the native side has, where the session runs on its own thread.
        void (async () => {

            for (const event of events) {

                if (cancelled) {
                    // The stream still has to end. A consumer that received no ending would wait for
                    // one for ever, which is worse than a session that failed.
                    this.notifyListeners("v2gEvent", { event: JSON.stringify({
                        seq:      event.seq,
                        atMillis: Math.round(performance.now()),
                        kind:     "error",
                        detail:   "the session was stopped.",
                    }) });

                    this.notifyListeners("v2gEvent", { event: JSON.stringify({
                        seq:       event.seq + 1,
                        atMillis:  Math.round(performance.now()),
                        kind:      "sessionFinished",
                        exchanges: trace.exchanges.length,
                        outcome:   "failed",
                    }) });

                    break;
                }

                // As text, because that is what the bridge carries — see definitions.ts. Here it
                // costs nothing and keeps one shape rather than two.
                this.notifyListeners("v2gEvent", { event: JSON.stringify(event) });

                if (this.pace > 0) await new Promise(resume => setTimeout(resume, this.pace));
            }

            this.running.delete(sessionId);

        })();

        return { sessionId };
    }


    /** Idempotent: stopping a finished or unknown session is not an error. */
    public async stop(options: { sessionId: string }): Promise<void> {
        this.running.get(options.sessionId)?.();
        this.running.delete(options.sessionId);
    }

}
