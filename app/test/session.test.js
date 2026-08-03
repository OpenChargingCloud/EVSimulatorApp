// @ts-check
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { rowsFor, detailFor, statusOf, hexLines,
         frameFor, timingsFor, signatureFor, exportsFor, digestCheckFor,
         ONGOING_BUDGET_MS } from "../src/session.js";

/**
 * The session screen, over the recorded event streams.
 *
 * The corpus is the one C# generates from the six recorded sessions and three other back ends are
 * held to, so this is the same events a phone would receive — reached from the last place they go.
 */

const repositoryRoot = (() => {
    let directory = dirname(fileURLToPath(import.meta.url));
    for (;;) {
        try { readFileSync(join(directory, "libs/Vanaheimr.V2G.Exi/CLAUDE.md")); return directory; }
        catch { /* keep walking */ }
        const parent = dirname(directory);
        if (parent === directory) throw new Error("repository root not found");
        directory = parent;
    }
})();

/** @type {Record<string, any[]>} */
const sessions = JSON.parse(readFileSync(
    join(repositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors/Bridge.events.json"),
    "utf8")).sessions;


test("every recorded session renders one row per event, and no gaps", () => {

    let checked = 0;

    for (const [name, events] of Object.entries(sessions)) {

        const rows = rowsFor(events);

        assert.equal(rows.length, events.length, `${name}: a complete stream grew or shrank`);
        assert.equal(rows.filter(r => r.tone === "gap").length, 0, `${name}: a gap in a complete stream`);

        assert.equal(rows[0]?.tone, "started", name);
        assert.equal(rows[rows.length - 1]?.tone, "finished", name);

        const status = statusOf(events);
        assert.equal(status.running, false, name);
        assert.equal(status.outcome, "completed", name);
        assert.equal(status.lost, 0, name);

        checked += rows.length;
    }

    assert.ok(checked >= 190, `only ${checked} rows`);
});


/**
 * A dropped event is reported rather than silently shortening the session.
 *
 * `seq` is documented in four back ends as being there so that "a consumer that sees a gap has lost
 * events", and until this screen existed **nothing anywhere looked**. A loss on a bridge is silent by
 * construction — the listener is simply not called — so a screen that rendered what it received would
 * show a shorter session with no sign that it was shorter.
 */
test("a lost event becomes a row of its own", () => {

    const whole = Object.values(sessions)[0] ?? [];

    const missingOne  = whole.filter((/** @type {any} */ _, /** @type {number} */ i) => i !== 3);
    const missingFour = whole.filter((/** @type {any} */ _, /** @type {number} */ i) => i < 3 || i > 6);

    const one = rowsFor(missingOne).filter(r => r.tone === "gap");
    assert.equal(one.length, 1);
    assert.equal(one[0]?.title, "1 event was lost");
    assert.equal(one[0]?.subtitle, "nothing arrived for seq 3");

    const four = rowsFor(missingFour).filter(r => r.tone === "gap");
    assert.equal(four.length, 1);
    assert.equal(four[0]?.title, "4 events were lost");
    assert.equal(four[0]?.subtitle, "nothing arrived for seq 3–6");

    assert.equal(statusOf(missingFour).lost, 1);

    // And the rest of the session still renders: a gap is a note in the record, not the end of it.
    assert.equal(rowsFor(missingFour).length, missingFour.length + 1);
});


test("a stream that repeats or rewinds a sequence number is not silently smoothed over", () => {

    const whole = Object.values(sessions)[0] ?? [];
    const rewound = [whole[0], whole[1], whole[2], whole[1], whole[3]];

    // seq 1 arrives twice: the second one does not lower the expectation, so seq 3 is still expected
    // where it is and no phantom gap appears — but nothing is dropped either.
    const rows = rowsFor(rewound);
    assert.equal(rows.length, rewound.length, "an out-of-order event was swallowed");
    assert.equal(rows.filter(r => r.tone === "gap").length, 0);
});


test("an opened message shows both halves — the document and the frame", () => {

    let messages = 0;

    for (const events of Object.values(sessions)) {
        for (const event of events) {

            const detail = detailFor(event);

            if (event.kind !== "message") continue;

            assert.notEqual(detail.json,  null, `event ${event.seq}: no document`);
            assert.notEqual(detail.frame, null, `event ${event.seq}: no frame`);

            // The claim and its evidence, both readable. A screen that showed only the JSON-LD would
            // be telling the user the bytes are right there without ever showing them.
            assert.ok(/** @type {string} */ (detail.json).includes("\"@type\""));

            const frame = /** @type {any} */ (detail.frame);
            const shown = frame.header.map((/** @type {any} */ f) => f.bytes).join("")
                        + frame.body.replace(/\s/g, "");
            assert.equal(shown, event.exi, `event ${event.seq}: the frame shown is not the frame`);

            messages++;
        }
    }

    assert.ok(messages >= 130, `only ${messages} messages`);
});


test("an event kind this build does not know is shown, not dropped", () => {

    const rows = rowsFor([
        { seq: 0, atMillis: 0, kind: "sessionStarted", name: "x", protocol: "iso15118-2", mode: "ac" },
        { seq: 1, atMillis: 1, kind: "somethingNewer" },
    ]);

    assert.equal(rows.length, 2);
    assert.equal(rows[1]?.tone, "error");
    assert.match(/** @type {string} */ (rows[1]?.title), /somethingNewer/);
});


test("a frame is shown sixteen bytes to the line", () => {
    assert.equal(hexLines("00112233445566778899aabbccddeeff00"),
                 "00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff\n00");
});


// ── the annotated frame ───────────────────────────────────────────────────────────────────────

test("every recorded frame's header reads back as a well-formed V2GTP header", () => {

    let frames = 0;

    for (const [name, events] of Object.entries(sessions)) {
        for (const event of events) {

            if (event.kind !== "message") continue;

            const frame = frameFor(event.exi);

            assert.deepEqual(frame.problems, [],
                             `${name} seq ${event.seq}: ${frame.problems.join("; ")}`);
            assert.equal(frame.header.length, 4);
            assert.equal(frame.bodyBytes, event.exi.length / 2 - 8);

            // The payload type in the header is the one the event claims — two derivations of one
            // value, which is the same discipline the bridge applies to a message's name.
            const shown = /** @type {any} */ (frame.header[2]).value;
            assert.ok(shown.startsWith(event.payloadType),
                      `${name} seq ${event.seq}: header says ${shown}, event says ${event.payloadType}`);

            frames++;
        }
    }

    assert.ok(frames >= 130, `only ${frames} frames`);
});


test("the header checks bite: a wrong length, a wrong inverse and an unknown type are all named", () => {

    // 8-byte header declaring two payload bytes, and two following. Sound.
    assert.deepEqual(frameFor("01fe80010000000212ab").problems, []);

    // The declared length disagrees with what is there — the check a stream reader lives or dies by.
    const short = frameFor("01fe8001000000ff12ab");
    assert.equal(short.problems.length, 1);
    assert.match(short.problems[0], /declares 255.*2 byte/);

    // The version's inverse is checked against the version, not against a constant.
    const inverse = frameFor("01ff80010000000212ab");
    assert.equal(inverse.problems.length, 1);
    assert.match(inverse.problems[0], /0xfe/);

    // 0x8009 is not dispatched by anything here.
    assert.match(frameFor("01fe80090000000212ab").problems[0], /payload type/);

    // Too short to be a frame at all, and said so rather than rendered as one.
    const stub = frameFor("01fe");
    assert.equal(stub.header.length, 0);
    assert.match(stub.problems[0], /shorter than/);
});


// ── timing ────────────────────────────────────────────────────────────────────────────────────

/**
 * A poll loop is one phase, however many times it repeats.
 *
 * Asserted on a constructed loop rather than on the corpus, and that is the honest way round: these
 * recordings are loopback sessions against our own SECC, which answers `Finished` at once, so the
 * longest run in the whole corpus is two. A live station is the opposite — the EVerest -20 DC run
 * under `docs/interop-runs/` spends 78 of its 100 exchanges in `DC_CableCheck` — and that is the
 * case this collapsing exists for. Asserting it against the corpus alone would have passed on code
 * that collapsed nothing.
 */
test("consecutive polls of one message collapse into one phase", () => {

    /** @param {number} n */
    const pollLoop = (n) => {
        const events = [{ seq: 0, atMillis: 0, kind: "sessionStarted",
                          name: "x", protocol: "iso15118-20", mode: "dc" }];
        for (let i = 0; i < n; i++) {
            events.push({ seq: events.length, atMillis: i * 100, kind: "message", direction: "out",
                          messageName: "DC_CableCheckReq", payloadType: "0x8004", exi: "01fe80040000000012" });
            events.push({ seq: events.length, atMillis: i * 100 + 50, kind: "message", direction: "in",
                          messageName: "DC_CableCheckRes", payloadType: "0x8004", exi: "01fe80040000000012" });
        }
        events.push({ seq: events.length, atMillis: n * 100, kind: "message", direction: "out",
                      messageName: "DC_PreChargeReq", payloadType: "0x8004", exi: "01fe80040000000012" });
        return events;
    };

    const timings = timingsFor(pollLoop(78));

    assert.equal(timings.phases.length, 2, "78 polls and a pre-charge are two phases, not 79");
    assert.equal(timings.phases[0]?.count, 78);
    assert.equal(timings.phases[0]?.name, "DC_CableCheckReq");
    assert.equal(timings.phases[1]?.count, 1);
});


test("across the corpus, every request lands in exactly one phase", () => {

    for (const [name, events] of Object.entries(sessions)) {

        const timings = timingsFor(events);
        const outbound = events.filter(
            (/** @type {any} */ e) => e.kind === "message" && e.direction === "out");

        assert.ok(timings.phases.length > 0, name);
        assert.ok(timings.phases.length <= outbound.length, name);

        const counted = timings.phases.reduce(
            (/** @type {number} */ n, /** @type {any} */ p) => n + p.count, 0);
        assert.equal(counted, outbound.length,
                     `${name}: ${counted} requests accounted for, ${outbound.length} sent`);

        for (const phase of timings.phases) {
            assert.ok(phase.share >= 0 && phase.share <= 1, `${name}: share out of range`);
            assert.ok(phase.millis >= 0, `${name}: negative duration`);
        }
    }
});


test("a poll loop that outstays the Ongoing budget is called out", () => {

    const events = [
        { seq: 0, atMillis: 0, kind: "sessionStarted", name: "x", protocol: "iso15118-2", mode: "dc" },
        { seq: 1, atMillis: 0,      kind: "message", direction: "out", messageName: "CableCheckReq",
          payloadType: "0x8001", exi: "01fe80010000000012" },
        { seq: 2, atMillis: 90_000, kind: "message", direction: "in",  messageName: "CableCheckRes",
          payloadType: "0x8001", exi: "01fe80010000000012" },
    ];

    const timings = timingsFor(events);

    assert.equal(timings.anyOverBudget, true);
    assert.equal(timings.phases[0]?.overBudget, true);
    assert.equal(timings.budgetMillis, ONGOING_BUDGET_MS);

    // …and the corpus, whose clock steps a millisecond per reading, is nowhere near it.
    for (const events of Object.values(sessions))
        assert.equal(timingsFor(events).anyOverBudget, false);
});


// ── the signature ─────────────────────────────────────────────────────────────────────────────

test("the recorded PnC sessions show a signature, and the EIM ones show none", () => {

    let signed = 0;

    for (const [name, events] of Object.entries(sessions)) {
        for (const event of events) {

            const view = signatureFor(event);
            if (!view.present) continue;

            assert.ok(name.includes("pnc"), `${name}: a signature in a session that is not PnC`);

            const labels = view.facts.map((/** @type {any} */ f) => f.label);
            for (const wanted of ["Signature method", "Covers", "Digest", "Signature"])
                assert.ok(labels.includes(wanted), `${name} seq ${event.seq}: no "${wanted}"`);

            // The reference points at an element that is really in this message. Everything else on
            // the screen is the signature's own claim; this is the one part checked.
            assert.deepEqual(view.problems, [],
                             `${name} seq ${event.seq}: ${view.problems.join("; ")}`);

            // And the screen says what it did not check, rather than implying it did.
            assert.ok(view.limits.length > 0);

            signed++;
        }
    }

    assert.ok(signed >= 3, `only ${signed} signed messages — the PnC corpus should carry more`);
    assert.equal(signatureFor(
        { seq: 0, atMillis: 0, kind: "message", json: { header: {} } }).present, false);
});


test("a signature covering an Id that is not in the message is refused, not decorated", () => {

    // The digest and the algorithms are perfectly well-formed; the reference is not.
    const event = {
        seq: 1, atMillis: 0, kind: "message", direction: "out", messageName: "AuthorizationReqType",
        json: {
            header: {
                signature: {
                    signedInfo: {
                        signatureMethod: { algorithm: "…#ecdsa-sha256" },
                        reference: [{ uri: "#id9", digestValue: "00" }],
                    },
                    signatureValue: { value: "ab" },
                },
            },
            body: { bodyElement: { "@type": "AuthorizationReqType", id: "id1" } },
        },
    };

    const view = signatureFor(event);

    assert.equal(view.present, true);
    assert.equal(view.problems.length, 1);
    assert.match(view.problems[0], /#id9/);
    assert.match(view.problems[0], /id1/);   // …and says what the message does carry
});


// ── the digest, re-derived ────────────────────────────────────────────────────────────────────

/**
 * The deriver is injected, so these are the *decisions* — what counts as a match, and what an
 * absent answer means — checked without a codec and without a browser. That the computation itself
 * agrees with C# is `typescript/test/digest.test.ts`, which reproduces the recorded digests.
 */
test("a digest that matches its frame is reported as covering the content, and no more", async () => {

    const signed = (sessions["iso2-ac-pnc"] ?? []).find(
        (/** @type {any} */ e) => e?.json?.header?.signature);
    assert.notEqual(signed, undefined);

    const claimed = signed.json.header.signature.signedInfo.reference[0].digestValue.toLowerCase();

    const check = await digestCheckFor(signed, async () => claimed);

    assert.equal(check.verdict, "match");
    assert.equal(check.derived, claimed);
    // The sentence has to keep the two questions apart: this says nothing about who signed.
    assert.match(check.explanation, /covers exactly the content/);
    assert.match(check.explanation, /separate question/);
});


test("a digest that does not match is a mismatch, not a shrug", async () => {

    const signed = (sessions["iso2-ac-pnc"] ?? []).find(
        (/** @type {any} */ e) => e?.json?.header?.signature);

    const check = await digestCheckFor(signed, async () => "00".repeat(32));

    assert.equal(check.verdict, "mismatch");
    assert.match(check.explanation, /does not cover the content/);
});


test("not being able to check is not the same as checking and failing", async () => {

    const signed = (sessions["iso2-ac-pnc"] ?? []).find(
        (/** @type {any} */ e) => e?.json?.header?.signature);

    // No bundle at all.
    const noBundle = await digestCheckFor(signed, null);
    assert.equal(noBundle.verdict, "unchecked");
    assert.notEqual(noBundle.claimed, null, "the claimed digest is still shown");

    // A bundle that cannot re-encode this message — a -20 body, or one with no fragment encoder.
    const noEncoder = await digestCheckFor(signed, async () => null);
    assert.equal(noEncoder.verdict, "unchecked");
    assert.match(noEncoder.explanation, /ISO 15118-2 only/);

    // An unsigned message has nothing to check and says so without alarm.
    const plain = (sessions["iso2-ac-eim"] ?? []).find(
        (/** @type {any} */ e) => e.kind === "message");
    const none = await digestCheckFor(plain, async () => "whatever");
    assert.equal(none.verdict, "unchecked");
    assert.match(none.explanation, /no signature/);
});


test("every unsigned message in the corpus stays unchecked, whatever the deriver says", async () => {

    let unsigned = 0;

    for (const events of Object.values(sessions)) {
        for (const event of events) {

            if (event.kind !== "message" || event?.json?.header?.signature) continue;

            // A deriver that answers confidently must not turn an unsigned message into a verdict.
            const check = await digestCheckFor(event, async () => "aa".repeat(32));
            assert.equal(check.verdict, "unchecked", `seq ${event.seq}`);
            unsigned++;
        }
    }

    assert.ok(unsigned >= 100, `only ${unsigned} unsigned messages`);
});


// ── export ────────────────────────────────────────────────────────────────────────────────────

test("a session exports as the corpus trace shape, request paired with response", () => {

    for (const [name, events] of Object.entries(sessions)) {

        const bundle = exportsFor(events, name);
        const trace  = JSON.parse(
            /** @type {any} */ (bundle.files.find((/** @type {any} */ f) => f.name.endsWith(".trace.json"))).text);

        const outbound = events.filter((/** @type {any} */ e) => e.kind === "message" && e.direction === "out");

        assert.equal(trace.schemaVersion, 2, name);
        assert.equal(trace.exchanges.length, outbound.length, name);

        for (const exchange of trace.exchanges) {
            assert.ok(exchange.request.frame.length > 0, name);
            assert.notEqual(exchange.response, null, `${name}: an unanswered request`);
        }

        // The first exchange is always the handshake, in every recorded session.
        assert.match(trace.exchanges[0].request.message, /SupportedAppProtocol/, name);
    }
});


test("a signed session says its export is not corpus-grade, rather than looking like one", () => {

    const pnc = exportsFor(sessions["iso2-ac-pnc"] ?? [], "iso2-ac-pnc");
    assert.ok(pnc.caveats.some((/** @type {string} */ c) => c.includes("signed")),
              "a session with signatures exported silently");

    const eim = exportsFor(sessions["iso2-ac-eim"] ?? [], "iso2-ac-eim");
    assert.deepEqual(eim.caveats, [], "an EIM session has nothing to warn about");
});


test("a session with holes exports with the holes declared", () => {

    const whole   = sessions["iso2-ac-eim"] ?? [];
    const missing = whole.filter((/** @type {any} */ _, /** @type {number} */ i) => i !== 4);

    const bundle = exportsFor(missing, "clipped");

    assert.ok(bundle.caveats.some((/** @type {string} */ c) => c.includes("lost")));
});
