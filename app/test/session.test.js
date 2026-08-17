/*
 * Copyright (c) 2021-2026 GraphDefined GmbH <achim.friedland@graphdefined.com>
 * This file is part of EVSimulatorApp
 *
 * Licensed under the Affero GPL license, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.gnu.org/licenses/agpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// @ts-check
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { rowsFor, detailFor, statusOf, hexLines,
         frameFor, timingsFor, signatureFor, exportsFor, digestCheckFor, signerCheckFor,
         meterReadingFor, meterCheckFor, energyFor, backendCheckFor,
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
        try { readFileSync(join(directory, "EVSimulatorApp.slnx")); return directory; }
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

test("the sessions that carry a signature show one, and the rest show none", () => {

    let signed = 0;

    // Two kinds of -2 signature reach this screen, and for a long time only one of them existed in
    // the corpus — so this test asserted "signed implies PnC" and was right by accident. A signed
    // SalesTariff offer (§7.9.2.5) is EIM, rides on a *response*, and covers elements inside the body
    // rather than the body itself. Naming both is what keeps the assertion honest; a session that
    // grows a signature for a third reason should fail here rather than slip through.
    const signedRequests  = (/** @type {string} */ name) => name.includes("pnc");
    const signedOffer     = (/** @type {string} */ name) => name.endsWith("-tariff");

    for (const [name, events] of Object.entries(sessions)) {
        for (const event of events) {

            const view = signatureFor(event);
            if (!view.present) continue;

            assert.ok(signedRequests(name) || signedOffer(name),
                      `${name}: a signature in a session that is neither PnC nor a signed offer`);

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
    assert.match(noEncoder.explanation, /-20 AC and DC sets/);

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


// ── who signed ────────────────────────────────────────────────────────────────────────────────

/**
 * The certificate comes from earlier in the *session*, not from the signed message — so these check
 * the decision the whole-stream parameter exists for. The cryptography itself is
 * `typescript/test/signature.test.ts`, which verifies the recorded signatures for real.
 */
test("the signer check reaches back to the session's PaymentDetailsReq", async () => {

    const events = sessions["iso2-ac-pnc"] ?? [];
    const signed = events.find((/** @type {any} */ e) => e?.json?.header?.signature);

    const details = events.find(
        (/** @type {any} */ e) => String(e.messageName ?? "").startsWith("PaymentDetailsReq"));
    assert.notEqual(details, undefined, "the PnC corpus should carry a PaymentDetailsReq");

    /** @type {string[]} */
    const seen = [];
    const check = await signerCheckFor(signed, events, async (signedHex, certHex) => {
        seen.push(certHex);
        return true;
    });

    assert.equal(check.verdict, "signed-by-contract");
    assert.deepEqual(seen, [details.exi], "the certificate did not come from PaymentDetailsReq");
    // …and the sentence does not overreach into trust.
    assert.match(check.explanation, /separate question/);
});


test("a signature from the wrong key is named as such, not blurred into 'unchecked'", async () => {

    const events = sessions["iso2-ac-pnc"] ?? [];
    const signed = events.find((/** @type {any} */ e) => e?.json?.header?.signature);

    const wrong = await signerCheckFor(signed, events, async () => false);
    assert.equal(wrong.verdict, "wrong-signer");
    assert.match(wrong.explanation, /not made with the key/);

    const cannot = await signerCheckFor(signed, events, async () => null);
    assert.equal(cannot.verdict, "unchecked");
    assert.match(cannot.explanation, /could not re-encode the signed element/);
});


test("a -20 signed message answers for itself, and a -2 one cannot", async () => {

    // Where the contract chain lives differs, and the check has to follow it. -2 sends it in an
    // earlier PaymentDetailsReq; -20 puts it inside the signed AuthorizationReq, in the very
    // fragment the signature covers. Looking for a PaymentDetailsReq in a -20 session finds nothing
    // and would report every -20 signature as unchecked.
    const iso20  = sessions["iso20-dc-pnc"] ?? [];
    const signed = iso20.find((/** @type {any} */ e) => e?.json?.header?.signature);

    assert.notEqual(signed, undefined, "the -20 PnC corpus should carry a signed message");
    assert.equal(signed.payloadType, "0x8002");
    assert.equal(iso20.some((/** @type {any} */ e) =>
                     String(e.messageName ?? "").startsWith("PaymentDetailsReq")), false,
                 "-20 has no PaymentDetailsReq at all — that message is -2's");

    /** @type {string[]} */
    const seen = [];
    const check = await signerCheckFor(signed, iso20, async (signedHex, certHex) => {
        seen.push(certHex);
        return true;
    });

    assert.equal(check.verdict, "signed-by-contract");
    assert.deepEqual(seen, [signed.exi], "the -20 message must be its own certificate source");
});


test("an EIM session has no certificate, and that is not a fault", async () => {

    // Take a signed message into a session that never sent PaymentDetailsReq.
    const pnc    = sessions["iso2-ac-pnc"] ?? [];
    const signed = pnc.find((/** @type {any} */ e) => e?.json?.header?.signature);
    const eim    = sessions["iso2-ac-eim"] ?? [];

    const check = await signerCheckFor(signed, eim, async () => {
        throw new Error("the verifier must not be called without a certificate");
    });

    assert.equal(check.verdict, "unchecked");
    assert.match(check.explanation, /Plug & Charge session sends one/);
});


// ── the station's meter ───────────────────────────────────────────────────────────────────────

test("the station's signed reading verifies against the meter key the session brought", async () => {

    const events   = sessions["iso2-ac-eim-meter"] ?? [];
    const readings = events.filter((/** @type {any} */ e) => meterReadingFor(e).present);

    assert.ok(readings.length >= 3,
              `the metered corpus should carry a reading per charging-status cycle, found ${readings.length}`);

    let climbing = 0n;

    for (const event of readings) {

        const view = meterReadingFor(event);
        assert.equal(view.meterId, "VAN*M*4711");
        assert.equal(view.signature?.length, 128, "64 bytes of raw r‖s, as hex");
        // The reading climbs through the session: a register counting what the loop delivered, not
        // a constant. A meter that never advanced would sign perfectly and still be broken.
        assert.ok(view.readingWh > climbing, `seq ${event.seq}: the reading did not advance`);
        climbing = view.readingWh;

        const check = await meterCheckFor(event, events);
        assert.equal(check.verdict, "signed-by-meter", `seq ${event.seq}: ${check.explanation}`);
        // …and it stops where it should: this says nothing about the meter being the right meter.
        assert.match(check.explanation, /separate question/);
    }
});


test("a reading altered after signing does not verify", async () => {

    const events = sessions["iso2-ac-eim-meter"] ?? [];
    const signed = events.find((/** @type {any} */ e) => meterReadingFor(e).signature !== null);

    // A CPO shaving 100 Wh off the number between the meter and this screen. The signature is
    // untouched, the message still parses, and only the check can tell.
    const shaved = structuredClone(signed);
    shaved.json.body.bodyElement.meterInfo.meterReading = "4100";

    const check = await meterCheckFor(shaved, events);
    assert.equal(check.verdict, "wrong-meter");
    assert.match(check.explanation, /changed after the meter signed it/);

    // The session binding, which is the half a signature over the numbers alone would miss.
    const elsewhere = structuredClone(signed);
    elsewhere.json.header.sessionID = "1111111111111111";
    assert.equal((await meterCheckFor(elsewhere, events)).verdict, "wrong-meter");
});


test("an unsigned reading is named as unsigned, and no key is not a fault of the reading", async () => {

    // Every station in the field: MeterInfo present, SigMeterReading empty.
    const events = sessions["iso2-ac-eim-meter"] ?? [];
    const signed = events.find((/** @type {any} */ e) => meterReadingFor(e).signature !== null);

    const bare = structuredClone(signed);
    delete bare.json.body.bodyElement.meterInfo.sigMeterReading;

    const unsigned = await meterCheckFor(bare, events);
    assert.equal(unsigned.verdict, "unsigned");
    assert.match(unsigned.explanation, /almost every station in the field/);

    // A signed reading with nothing to check it against is a different answer again — and must not
    // read as a pass.
    const noKey = events.filter((/** @type {any} */ e) => e.kind !== "sessionStarted");
    const check = await meterCheckFor(signed, noKey);
    assert.equal(check.verdict, "unchecked");
    assert.match(check.explanation, /decoration/);
});


test("a session with no meter reports none, rather than reporting nothing", async () => {

    for (const [name, events] of Object.entries(sessions)) {
        if (name.endsWith("-meter")) continue;
        for (const event of events)
            assert.equal(meterReadingFor(event).signature, null,
                         `${name}/${event.seq}: a station with no meter signed a reading`);
    }

    // …and the -2 sessions without a meter still carry the plain MeterInfo a receipt needs, so
    // "no signature" and "no reading" stay distinguishable on screen.
    const pnc = sessions["iso2-ac-pnc"] ?? [];
    const withReading = pnc.filter((/** @type {any} */ e) => meterReadingFor(e).present);
    assert.ok(withReading.length > 0, "the PnC session reports a reading, just an unsigned one");
    assert.equal(await meterCheckFor(withReading[0], pnc).then(c => c.verdict), "unsigned");
});


// ── the two counts ────────────────────────────────────────────────────────────────────────────

test("the car's own count and the station's signed reading agree, across both protocols", () => {

    for (const [name, expected] of [["iso2-ac-eim-meter", 552n], ["iso20-dc-eim-meter", 2400n]]) {

        const energy = energyFor(sessions[name] ?? []);

        assert.equal(energy.verdict, "agree", `${name}: ${energy.explanation}`);
        assert.equal(energy.vehicleWh, expected, name);
        assert.equal(energy.stationWh, expected, name);
        assert.equal(energy.samples, 3, `${name}: three charge-loop iterations`);
        // …and the sentence does not claim more than two models agreeing.
        assert.match(energy.explanation, /not evidence about a real meter/);
    }
});


test("the car counts on its own at a station with no meter, which is the ordinary case", () => {

    // Every unmetered session in the corpus: the vehicle still has a number, the station does not.
    for (const [name, events] of Object.entries(sessions)) {

        if (name.endsWith("-meter")) continue;

        const energy = energyFor(events);
        assert.notEqual(energy.verdict, "differ", `${name}: ${energy.explanation}`);

        if (energy.verdict === "one-sided") {
            assert.equal(energy.stationWh, null, name);
            assert.ok(energy.vehicleWh > 0n, `${name}: the vehicle counted nothing`);
            assert.match(energy.explanation, /worth having/);
        }
    }
});


test("a station reporting energy it did not deliver is a difference, not a rounding note", () => {

    const events = structuredClone(sessions["iso2-ac-eim-meter"] ?? []);
    const last   = [...events].reverse().find(e => e?.json?.body?.bodyElement?.meterInfo);

    // A station billing for 1 kWh it never delivered. Every signature in the session still checks
    // out — this is the disagreement no signature can catch.
    last.json.body.bodyElement.meterInfo.meterReading = "1552";

    const energy = energyFor(events);
    assert.equal(energy.verdict, "differ");
    assert.equal(energy.deviationWh, 1000n);
    assert.match(energy.explanation, /a signature cannot\s+catch/);
});


test("the car's count comes from what the car said, not from the station's answer", () => {

    // Rewrite every figure the station reports in the -2 DC session. The vehicle's count is derived
    // from its own CurrentDemandReq, so it must not move — if it did, this comparison would be an
    // elaborate way of comparing a number with itself.
    const events = structuredClone(sessions["iso2-dc-eim"] ?? []);
    const before = energyFor(events).vehicleWh;

    for (const event of events) {
        const body = event?.json?.body?.bodyElement;
        if (body?.eVSEPresentCurrent) body.eVSEPresentCurrent.value = 1;
        if (body?.eVSEPresentVoltage) body.eVSEPresentVoltage.value = 1;
    }

    assert.ok(before > 0n, "the DC session counted nothing to begin with");
    assert.equal(energyFor(events).vehicleWh, before);
});


test("the two counts are paired at the same instant, not at the end of the session", () => {

    // The Plug & Charge session, where an unmetered station reports MeterInfo exactly once — with
    // the receipt it demands, early. The car goes on counting afterwards, so its final total is a
    // different moment from the station's only reading, and comparing those two produced a 368 Wh
    // "disagreement" with nothing wrong in the session at all.
    const energy = energyFor(sessions["iso2-ac-pnc"] ?? []);

    assert.equal(energy.verdict, "agree", energy.explanation);
    assert.equal(energy.vehicleWh, energy.stationWh, "the paired figures must be the compared ones");
    assert.ok(energy.vehicleTotalWh > energy.vehicleWh,
              "the car kept counting after the station stopped reporting — that is the whole point "
            + "of keeping the total separate");
});


test("the -20 station's signed reading is read and checked too, not quietly skipped", async () => {

    // MeterInfo sits in a different place in -20, and the protocol number in the signed payload is
    // 20 rather than 2 — read either the -2 way and every -20 reading silently disappears or fails.
    const events   = sessions["iso20-dc-eim-meter"] ?? [];
    const readings = events.filter((/** @type {any} */ e) => meterReadingFor(e).present);

    assert.ok(readings.length >= 3, `only ${readings.length} -20 readings were found`);

    for (const event of readings) {
        assert.equal(meterReadingFor(event).protocol, 20);
        const check = await meterCheckFor(event, events);
        assert.equal(check.verdict, "signed-by-meter", `seq ${event.seq}: ${check.explanation}`);
    }
});


// ── what the station told its backend ─────────────────────────────────────────────────────────

// The OCPP transaction corpus is recorded by the C# session tests and vendored into `vectors/` with
// the rest of the corpus, so these backend-vs-car checks always run.
/** @type {Record<string, any>} */
const transactions = JSON.parse(readFileSync(
    join(repositoryRoot, "vectors/Session.ocpp-transactions.json"),
    "utf8")).transactions;


test("the signed readings the backend got are the ones this car saw", async () => {

    for (const name of ["iso2-ac-eim-meter", "iso20-dc-eim-meter"]) {

        const check = backendCheckFor(sessions[name] ?? [], transactions[name]);

        assert.equal(check.verdict, "consistent", `${name}: ${check.explanation}`);
        assert.equal(check.signedValues, 3, name);
        assert.equal(check.matched, 3, name);
        assert.equal(check.backendWh, energyFor(sessions[name] ?? []).stationWh, name);

        // …and the sentence refuses to call a station-produced record independent.
        assert.match(check.explanation, /not from its operator's backend/);
    }
});


test("a station that reports one figure and shows another is caught, with no key at all", () => {

    // The fraud the two records exist to make visible: the backend is given a signed reading the
    // driver was never shown. Every signature in both records is perfectly valid.
    const name   = "iso2-ac-eim-meter";
    const record = structuredClone(transactions[name]);

    record.meterValues[2].sampledValue[0].value = "1552";
    record.meterValues[2].sampledValue[0].signedMeterValue.signedMeterData =
        "00".repeat(64);   // signed by the meter for the backend, never shown to the car

    const check = backendCheckFor(sessions[name] ?? [], record);

    assert.equal(check.verdict, "reported-differently");
    assert.match(check.explanation, /never\s+showed this car/);
});


test("a backend record for another session is refused rather than compared", () => {

    const record = structuredClone(transactions["iso2-ac-eim-meter"]);
    record.v2gSessionId = "1111111111111111";

    const check = backendCheckFor(sessions["iso2-ac-eim-meter"] ?? [], record);

    assert.equal(check.verdict, "unbound");
    assert.equal(check.backendWh, null, "nothing may be reported from an unbound record");
    assert.match(check.explanation, /nothing was compared/);
});


test("no backend record is a plain absence, and says where one would come from", () => {

    const check = backendCheckFor(sessions["iso2-ac-eim"] ?? [], null);

    assert.equal(check.verdict, "none");
    assert.match(check.explanation, /fetched from the CSMS/);
});


test("an unsigned backend record reports its energy and claims nothing more", () => {

    // The ordinary station: it meters, it bills, it does not sign — and the car saw no reading at
    // all in this EIM session, which is exactly why the backend's account is worth looking at.
    const check = backendCheckFor(sessions["iso2-ac-eim"] ?? [], transactions["iso2-ac-eim"]);

    assert.equal(check.verdict, "unsigned");
    assert.equal(check.backendWh, 552n);
    assert.equal(check.signedValues, 0);
    assert.equal(energyFor(sessions["iso2-ac-eim"] ?? []).stationWh, null,
                 "this session showed the car no reading — the backend's is the only one");
});


// ── export ────────────────────────────────────────────────────────────────────────────────────

test("a session exports as the corpus trace shape, request paired with response", () => {

    for (const [name, events] of Object.entries(sessions)) {

        const bundle = exportsFor(events, name);
        const trace  = JSON.parse(
            /** @type {any} */ (bundle.files.find((/** @type {any} */ f) => f.name.endsWith(".trace.json"))).text);

        const outbound = events.filter((/** @type {any} */ e) => e.kind === "message" && e.direction === "out");

        assert.equal(trace.schemaVersion, 3, name);
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
