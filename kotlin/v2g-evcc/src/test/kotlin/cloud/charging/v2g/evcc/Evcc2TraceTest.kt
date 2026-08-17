package cloud.charging.v2g.evcc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

import cloud.charging.v2g.iso2.ChargingStatusResType
import cloud.charging.v2g.iso2.V2G_Message
import cloud.charging.v2g.metering.MeterSignature
import cloud.charging.v2g.tp.V2GTPDecodeResult
import cloud.charging.v2g.tp.V2GTPDispatcher

/**
 * The Kotlin EVCC against the C# one, byte for byte.
 *
 * This is the whole gate for the -2 port. Unlike the codec, a state machine has no reference
 * implementation and no vector corpus for *behaviour*, so "it runs to completion" would prove
 * nothing about what it actually said. Replaying a recorded session and requiring identical requests
 * turns the question into the same one the codec answers: are these bytes the right bytes.
 *
 * What it cannot do is catch a bug the C# EVCC has too — see `SessionTrace.cs`. C# is the
 * implementation that earned the live-interop fixes against Josev, which makes it a defensible
 * reference and not a correct one.
 */
class Evcc2TraceTest {

    private fun replay(name: String, mode: PowerMode, configure: (Evcc2) -> Unit = { }): TraceReplay {

        val trace  = SessionTrace.load(name)
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        // No wall-clock waiting: the poll loops are driven by the recorded responses, and a real
        // delay here would only make the suite slower, never more truthful.
        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_2, mode)
        Evcc2(stream, mode, pollDelay = { }).also(configure).run()

        return replay
    }

    @Test
    fun theAcSessionMatchesTheRecordingByteForByte() {
        val replay = replay("iso2-ac-eim", PowerMode.Ac)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    @Test
    fun theDcSessionMatchesTheRecordingByteForByte() {
        val replay = replay("iso2-dc-eim", PowerMode.Dc)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    /**
     * Recorded 2026-08-16; red until [EvBattery] was ported on 2026-08-17.
     *
     * Every other recording here charges for a fixed count of cycles; this one charges until the
     * battery reaches its target state of charge, which is what the C# car has done since
     * 2026-08-08. The pack settings have to match the recording exactly, because they are what the
     * bytes are: 60 kWh from 20 % to a 22 % target is two cycles at 800 Wh each, and every
     * `EVRESSSOC` on the way is this arithmetic rounded to a percent.
     */
    @Test
    fun theDcSessionWithABatteryMatchesTheRecordingByteForByte() {
        val replay = replay("iso2-dc-eim-battery", PowerMode.Dc) {
            it.battery = EvBattery(60.0, 20.0).apply { targetSoC = 22.0; maxIterations = 100 }
        }
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — the recorded car " +
            "charges to a target state of charge, this one to a cycle count")
    }

    /**
     * The battery's own arithmetic, read back from the pack rather than from the wire.
     *
     * The bytes above already pin it, but only implicitly: a reader looking at the trace sees
     * `EVRESSSOC` go 20, 21, 23 and has to reconstruct why. This states the why, and it is the
     * assertion that would survive if the recording were ever re-taken.
     */
    @Test
    fun theBatteryStopsBecauseItReachedItsTarget() {
        val pack = EvBattery(60.0, 20.0).apply { targetSoC = 22.0; maxIterations = 100 }

        assertEquals(1200.0, pack.energyNeededWh, "22 % of 60 kWh less the 20 % it starts with")
        pack.add(800.0)                                   // one minute at 48 kW, the DC loop's rate
        assertEquals(ChargeStop.Running, pack.stop, "21.3 % has not reached 22 % yet")
        pack.add(800.0)
        assertEquals(ChargeStop.TargetSoC, pack.stop, "22.7 % has")
        assertEquals(2, pack.iterations)
    }

    /**
     * Recorded 2026-08-16. Renegotiation ([V2G2-841]) is ported, but on DC it returns through
     * CableCheck and PreCharge rather than straight back to the charge loop, and nothing had ever
     * held this port to that return path.
     */
    @Test
    fun theDcRenegotiationMatchesTheRecordingByteForByte() {
        val replay = replay("iso2-dc-eim-renegotiate", PowerMode.Dc) { it.renegotiate = true }
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — the DC return path " +
            "through CableCheck and PreCharge is where to look")
    }

    /**
     * The smart-charging outcome, read back from the state machine rather than from the wire. The
     * bytes already pin it — `PowerDeliveryReq(Start)` carries both the tuple id and the profile —
     * but a failure there says "byte 31 differs", and this says which decision went wrong.
     */
    @Test
    fun theEvChoosesATupleAndShapesAProfileToIt() {

        val trace  = SessionTrace.load("iso2-ac-eim")
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_2, PowerMode.Ac)
        val evcc = Evcc2(stream, PowerMode.Ac, pollDelay = { })
        evcc.run()

        val tariff = evcc.tariff
        assertTrue(tariff != null, "ChargeParameterDiscovery finished without a verdict")
        assertTrue(tariff.tuplesOffered > 0)
        assertTrue(tariff.profileEntries > 0, "a chosen tuple with no profile entries would send an empty profile")
        assertEquals(trace.exchanges.size, evcc.exchanges + 1,
            "every exchange but the SAP handshake belongs to the -2 state machine")
    }

    /**
     * Plug & Charge, -2: PaymentDetails with the contract chain, a signed AuthorizationReq, and a
     * signed MeteringReceiptReq for every receipt the station demands — three signed requests in one
     * session, each matching the recording once its signature is substituted and each verifying on
     * its own.
     *
     * `meteringReceiptsSent` is asserted because it is the one thing a session-level check can see
     * that a byte comparison cannot express: a station only asks for receipts under Contract, so a
     * non-zero count is the clearest single sign that PnC really ran rather than quietly falling
     * back to EIM.
     */
    @Test
    fun theAcPncSessionMatchesTheRecording() {

        val trace  = SessionTrace.load("iso2-ac-pnc")
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_2, PowerMode.Ac)
        val evcc = Evcc2(stream, PowerMode.Ac, pollDelay = { }).apply { pnc = PncMaterial.options }
        evcc.run()

        assertTrue(replay.complete, "replayed ${replay.replayed} of ${trace.exchanges.size} exchanges")
        assertEquals("pnc-signed", evcc.authorizationMode,
            "the session completed but authorized via EIM — then nothing signed was compared")
        assertTrue(evcc.meteringReceiptsSent > 0,
            "no metering receipt was sent, so the second signed message type never ran")
    }

    /**
     * **The signature C# produced verifies under this port's own verification path.**
     *
     * This closes a blind spot the two tests above cannot, and it is worth spelling out because the
     * gap is not obvious. A replayed signature is *substituted away* before the byte comparison, and
     * the verification of a produced signature uses this port's own `standaloneOctets`. So a Kotlin
     * standalone-xmldsig encoder that disagreed with C#'s would sign over X, verify over X, and pass
     * both checks — while producing a signature no other implementation accepts. The mirrored bug,
     * in the one place the corpus does not otherwise reach.
     *
     * Verifying the **recorded** frame breaks the symmetry: those bytes were signed by C# over C#'s
     * octets, so they verify here only if the two encoders agree. That is the whole point of the
     * separate `exi-xmldsig` module, checked rather than assumed.
     */
    @Test
    fun theRecordedSignatureVerifiesUnderThisPortsOwnEncoder() {

        for (name in listOf("iso2-ac-pnc", "iso20-dc-pnc")) {

            val trace = SessionTrace.load(name)
            val key   = SignedFrame.publicKey(trace.signingKey!!.x, trace.signingKey.y)
            val signed = trace.exchanges.filter { it.request.isSigned }

            assertTrue(signed.isNotEmpty(), "$name records no signed request")

            for (exchange in signed)
                assertTrue(SignedFrame.verifiesWith(exchange.request.bytes, key),
                    "$name exchange ${exchange.index} (${exchange.request.message}): the signature C# " +
                    "recorded does not verify here. The two standalone-xmldsig encoders disagree, " +
                    "which no other check in this suite can see.")
        }
    }

    // ── the signed tariff offer ───────────────────────────────────────────

    /**
     * The Mobility Operator's public key, read out of `Tariff.signature.vectors.json`.
     *
     * The recorded session and that corpus are signed by the *same* key on purpose, so this is one
     * operator identity rather than two. It is not carried in the trace itself: the schema has places
     * for the PnC and meter keys because those sessions are unreadable without them, and a third would
     * give one key two homes and a way to disagree with itself.
     */
    private fun tariffVerifyKey(): java.security.PublicKey {

        var dir = java.io.File(".").absoluteFile
        while (!java.io.File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")

        val file = java.io.File(dir, "vectors/Tariff.signature.vectors.json")
        require(file.isFile) { "tariff corpus not found at $file" }

        val key = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonArray("cases")
            .map { it.asJsonObject }
            .first { it.get("name").asString == "signed-msgdef" }
            .getAsJsonObject("verifyKey")

        val params = java.security.AlgorithmParameters.getInstance("EC")
            .apply { init(java.security.spec.ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(java.security.spec.ECParameterSpec::class.java)

        return java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.ECPublicKeySpec(
                java.security.spec.ECPoint(
                    java.math.BigInteger(key.get("x").asString, 16),
                    java.math.BigInteger(key.get("y").asString, 16)),
                params))
    }

    @Test
    fun theSignedTariffSessionMatchesTheRecordingByteForByte() {
        val replay = replay("iso2-ac-eim-tariff", PowerMode.Ac)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    /**
     * **What the wire cannot show.** The bytes above prove this port *read* a signed two-tuple offer
     * the way the C# EVCC did; they say nothing about the verdict, because the EV never tells the
     * station what it concluded. Only the fields below do, and only with the operator's key in hand.
     */
    @Test
    fun theSignedTariffOfferVerifiesAndTheCheaperTupleWins() {

        var evcc: Evcc2? = null
        replay("iso2-ac-eim-tariff", PowerMode.Ac) { evcc = it; it.tariffVerifyKey = tariffVerifyKey() }
        val tariff = evcc!!.tariff!!

        assertEquals(2, tariff.tuplesOffered, "the station offered a choice, not a formality")
        assertTrue(tariff.signaturePresent)
        assertTrue(tariff.digestOk, "each SalesTariff must digest to its own Reference")
        assertTrue(tariff.signatureOk)
        assertEquals("iso2-msgdef", tariff.signatureGrammar,
            "our own station signs under ISO's grammar; xmldsig-standalone here would mean the " +
            "recording was made against a Josev-shaped signer")

        // Tuple 2 averages EPriceLevel 1.5 against tuple 1's 2.5, and carries two PMax steps.
        assertEquals(2u.toUByte(), tariff.chosenTupleId, "a price-aware EV takes the cheaper tuple")
        assertEquals(2, tariff.profileEntries)
    }

    /**
     * Without the key the digest half is still answered — and must be, because it is the half that
     * catches a tariff edited after signing. Reporting it as unknown would throw away the only check
     * an EV without an operator key can still make.
     */
    @Test
    fun withoutTheOperatorKeyTheDigestIsStillChecked() {

        var evcc: Evcc2? = null
        replay("iso2-ac-eim-tariff", PowerMode.Ac) { evcc = it }
        val tariff = evcc!!.tariff!!

        assertTrue(tariff.signaturePresent)
        assertTrue(tariff.digestOk)
        assertEquals(false, tariff.signatureOk, "no key was offered, so nothing was established")
        assertEquals("none", tariff.signatureGrammar)
    }

    @Test
    fun theMeteredAcSessionMatchesTheRecordingByteForByte() {
        val replay = replay("iso2-ac-eim-meter", PowerMode.Ac)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    /**
     * **The vehicle's own counter lands where the station's signed reading does.**
     *
     * Two counters, two state machines, and neither looks at the other's total: the EV multiplies
     * the power it committed to in its ChargingProfile, the station the profile it accepted, and
     * both book the same declared sample period. 552 Wh is 11,040 W — 3 × 230 V × 16 A, what an
     * ordinary European charge point delivers — for three minutes, and it is the last `MeterReading`
     * in this very recording, so this is the port's arithmetic held against C#'s rather than against
     * itself.
     *
     * **What this stopped checking on 2026-08-07.** While the station offered a round 11 kW the
     * sample was 183.33 Wh, which rounds to 183 three times and not to 550 — so a port integrating
     * precisely and rounding once was one watt-hour out, silently, in a number a driver is billed
     * on, and it showed up right here. The offer is now a physical number that divides exactly, so
     * this trace no longer distinguishes the two rules. C# checks the rule directly (`EvMeterTests`,
     * `Secc20SignedMeterTests`); this port has no metering test of its own, and until it has one,
     * nothing here holds it to per-sample rounding.
     */
    @Test
    fun theVehiclesOwnCounterAgreesWithTheStationsSignedReading() {

        val trace  = SessionTrace.load("iso2-ac-eim-meter")
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_2, PowerMode.Ac)
        val evcc = Evcc2(stream, PowerMode.Ac, pollDelay = { })
        evcc.run()

        assertEquals(3, evcc.meter.samples, "three charge-loop iterations, three samples")
        assertEquals(552uL, evcc.meter.energyWh)

        // …and that figure is the station's, read back out of the recording rather than restated.
        val last = trace.exchanges.last { it.response.carriesMeterSignature }
        val decoded = V2GTPDispatcher.decode(last.response.bytes)
        val status = ((decoded as V2GTPDecodeResult.Decoded).message as V2G_Message)
                         .body.bodyElement as ChargingStatusResType
        assertEquals(status.meterInfo!!.meterReading, evcc.meter.energyWh,
            "the vehicle's counter and the station's last signed reading disagree, and they are " +
            "counting the same three iterations of the same charge loop")
    }

    /**
     * **The reading C# recorded verifies under this port's own payload layout.**
     *
     * The same argument as [theRecordedSignatureVerifiesUnderThisPortsOwnEncoder], one signer along
     * and with a sharper edge: ISO 15118 defines the `SigMeterReading` *field* and says nothing about
     * what the signature covers, so the payload is this project's own convention. Three ports of one
     * convention, each tested against itself, agree perfectly and can be wrong together.
     *
     * [MeterSignatureTest] already holds this port to a C#-generated vector corpus, which fixes the
     * layout. What it cannot fix is everything between the wire and that call: whether the reading,
     * the timestamp and above all the **session id** are pulled from the right places. Those come
     * from two different parts of the frame here — MeterInfo and the message header — and reading
     * either from the wrong place produces a verification that fails on good data or, worse, one
     * that never varies.
     */
    @Test
    fun theRecordedMeterReadingVerifiesUnderThisPortsOwnLayout() {

        val trace = SessionTrace.load("iso2-ac-eim-meter")
        val key   = SignedFrame.publicKey(trace.meterKey!!.x, trace.meterKey.y)

        val metered = trace.exchanges.filter { it.response.carriesMeterSignature }
        assertTrue(metered.size >= 3, "the metered corpus records ${metered.size} readings")

        var previous = 0uL

        for (exchange in metered) {

            val decoded = V2GTPDispatcher.decode(exchange.response.bytes)
            val message = (decoded as V2GTPDecodeResult.Decoded).message as V2G_Message
            val status  = message.body.bodyElement as ChargingStatusResType
            val info    = status.meterInfo!!

            assertEquals("VAN*M*4711", info.meterID)
            // The reading climbs through the session — it is a register counting what the loop
            // delivered, not a constant. A meter that never advanced would still sign correctly and
            // would be exactly the failure this line exists to notice.
            assertTrue(info.meterReading!! > previous, "the reading did not advance: $info")
            previous = info.meterReading!!

            assertTrue(
                MeterSignature.verify(info.sigMeterReading!!, 2, message.header.sessionID,
                                      info.meterID, info.meterReading!!, info.tMeter, key),
                "exchange ${exchange.index}: the reading C# recorded does not verify here — the two " +
                "meter payload layouts disagree, or this port reads the session id from the wrong place")

            // The session binding, checked rather than assumed: the same reading under another
            // session id must not verify, or the field would prove genuine-but-not-yours.
            val elsewhere = message.header.sessionID.copyOf().also { it[0] = (it[0] + 1).toByte() }
            assertTrue(
                !MeterSignature.verify(info.sigMeterReading!!, 2, elsewhere, info.meterID,
                                       info.meterReading!!, info.tMeter, key),
                "a reading verified under a session id it was not signed for")
        }
    }

    /**
     * A Common Name that cannot be an eMAID is refused before the session opens.
     *
     * ISO 15118-2 constrains `eMAIDType` to 14–15 characters, and nothing enforced it: the generated
     * codec does not apply string-length facets, so a 19-character CN travelled in this repository's
     * own recorded PnC session, accepted by all three back ends, until Swift got an X.509 reader
     * that checked the length the schema states.
     *
     * The rule is **-2's**, not a certificate profile's — ISO 15118-20 never sends the eMAID from the
     * certificate, so [Evcc20Base] deliberately does not check.
     */
    @Test
    fun aCommonNameThatCannotBeAnEmaidIsRefusedBeforeTheSessionOpens() {

        // The trace's own credential is fine; only its identity is swapped for a bad one. The stream
        // would fail on first use, so reaching it at all would be a different error — which is how
        // this test knows the check ran before any I/O.
        val replay = TraceReplay(SessionTrace.load("iso2-ac-pnc"))
        val evcc = Evcc2(V2GTPStream(replay.input, replay.output), PowerMode.Ac, pollDelay = { })

        evcc.pnc = PncEvccOptions(PncMaterial.certificateWithUnusableEmaid,
                                  listOf(PncMaterial.certificateWithUnusableEmaid),
                                  PncMaterial.signer)

        val aborted = assertFailsWith<SessionAborted> { evcc.run() }
        assertTrue(aborted.message!!.contains("14 or 15"), aborted.message!!)
    }

    /**
     * The harness fails when it should. Without this, the tests above being green could equally mean
     * the comparison never compares anything — the failure mode a corpus check is most prone to, and
     * the one that is invisible from a passing suite.
     */
    @Test
    fun anAlteredRequestIsRejected() {

        val trace  = SessionTrace.load("iso2-ac-eim")
        val replay = TraceReplay(trace)

        // Exchange 0 is the SAP handshake; tamper with the first real session message, so this
        // proves a *session* frame is compared and not merely the opening one.
        replay.output.write(trace.exchanges[0].request.bytes)

        val tampered = trace.exchanges[1].request.bytes.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        val mismatch = assertFailsWith<TraceMismatch> { replay.output.write(tampered) }

        assertTrue(mismatch.message!!.contains("exchange 1"), mismatch.message!!)
        assertTrue(mismatch.message!!.contains("first difference at"), mismatch.message!!)
    }

    /** A session that ends early is the other way a replay passes without meaning anything. */
    @Test
    fun anEarlyEndingSessionIsNotComplete() {

        val trace  = SessionTrace.load("iso2-ac-eim")
        val replay = TraceReplay(trace)

        replay.output.write(trace.exchanges[0].request.bytes)

        assertEquals(1, replay.replayed)
        assertTrue(!replay.complete)
    }
}
