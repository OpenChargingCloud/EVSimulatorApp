// @ts-check

import { startScreen, sheetScreen, manualScreen, sessionScreen } from "./screens.js";
import { scanner } from "./scan.js";

/**
 * The wiring: which screen is showing, and what the plugin is doing.
 *
 * Deliberately the only stateful file in the application. Everything it calls is a pure function of
 * its arguments, which is why the interesting half of this UI is testable at all.
 *
 * @module
 */

/**
 * @typedef {import("../sheet.js").SessionConfig} SessionConfig
 * @typedef {import("../session.js").BridgeEvent} BridgeEvent
 */

/**
 * The three commands, and no more.
 *
 * Resolved at run time rather than imported, because the plugin only exists inside the Capacitor
 * shell: opened in a plain browser this application still scans, parses, warns and refuses — which is
 * most of what it does — and says plainly that it cannot connect.
 *
 * @returns {any}
 */
function plugin() {
    const capacitor = /** @type {any} */ (globalThis).Capacitor;
    return capacitor?.Plugins?.EvSimulator ?? null;
}


const root = /** @type {Element} */ (document.getElementById("app"));

/** @type {{sessionId: string | null, remove: null | (() => Promise<void>)}} */
const live = { sessionId: null, remove: null };


function showStart() {
    startScreen(root, {
        scanner: scanner(),
        onCode:  text => showSheet(text),
        onManual: showManual,
    });
}

/** @param {string} text */
function showSheet(text) {
    sheetScreen(root, text, { onConnect: connect, onCancel: showStart });
}

function showManual() {
    manualScreen(root, { onConnect: connect, onCancel: showStart });
}


/**
 * Starts a session and follows it.
 *
 * Every event is appended and the screen redrawn from the whole list rather than the newest one,
 * because the list is what `rowsFor` needs to notice a gap — a renderer that appended one row per
 * event could not see that a `seq` was missing, which is the one thing the field exists for.
 *
 * @param {SessionConfig} config
 */
async function connect(config) {

    const api = plugin();

    if (api === null) {
        sheetScreenProblem("This build is running outside the app shell, so it cannot open a session.");
        return;
    }

    /** @type {BridgeEvent[]} */
    const events = [];

    try {
        const handle = await api.addListener("v2gEvent", (/** @type {any} */ payload) => {
            // The payload is the text the four back ends agree on; see capacitor/src/definitions.ts
            // for why it crosses as text rather than as an object.
            try {
                events.push(JSON.parse(payload.event));
            } catch {
                return;                     // an unreadable event is dropped, and the gap will show
            }
            sessionScreen(root, events, { onStop: stop, onBack: showStart });
        });

        live.remove = () => handle.remove();

        const { sessionId } = await api.start(config);
        live.sessionId = sessionId;

        sessionScreen(root, events, { onStop: stop, onBack: showStart });

    } catch (problem) {
        // A rejected `start` means no session exists — see LiveSessionRunner. So this is the sheet's
        // refusal arriving late, not a session that failed, and it belongs on the screen the user is
        // already looking at.
        sheetScreenProblem(String(problem?.message ?? problem));
    }
}


async function stop() {

    const api = plugin();

    if (api !== null && live.sessionId !== null)
        await api.stop({ sessionId: live.sessionId });

    // Removing the listener is not tidiness: a page that navigated away without it would leave a
    // session running on a phone with nothing reading it.
    if (live.remove !== null) await live.remove();

    live.sessionId = null;
    live.remove = null;
}


/** @param {string} text */
function sheetScreenProblem(text) {
    const node = document.createElement("p");
    node.className = "problem";
    node.textContent = text;
    root.appendChild(node);
}


showStart();
