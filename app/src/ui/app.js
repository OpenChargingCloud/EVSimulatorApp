// @ts-check

import { startScreen, sheetScreen, manualScreen, sessionScreen } from "./screens.js";
import { scanner } from "./scan.js";
import { plugin } from "./plugin.js";

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

    const api = await plugin();

    if (api === null) {
        sheetScreenProblem(
            "This build has no session bundle, so it cannot open a session. "
          + "Run `npm run build` in app/.");
        return;
    }

    /** @type {BridgeEvent[]} */
    const events = [];

    try {
        // A parsed BridgeEvent, not the text the bridge carries: `plugin()` returns the *adapted*
        // plugin, which has already unwrapped the payload and validated it. See plugin.js for what
        // reaching around that cost once.
        const handle = await api.addListener("v2gEvent", (/** @type {BridgeEvent} */ event) => {
            events.push(event);
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

    const api = await plugin();

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
