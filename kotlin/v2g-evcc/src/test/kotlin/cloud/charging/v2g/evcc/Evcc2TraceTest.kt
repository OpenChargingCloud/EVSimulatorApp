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

    private fun replay(name: String, mode: PowerMode): TraceReplay {

        val trace  = SessionTrace.load(name)
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        // No wall-clock waiting: the poll loops are driven by the recorded responses, and a real
        // delay here would only make the suite slower, never more truthful.
        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_2, mode)
        Evcc2(stream, mode, pollDelay = { }).run()

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

    @Test
    fun theMeteredAcSessionMatchesTheRecordingByteForByte() {
        val replay = replay("iso2-ac-eim-meter", PowerMode.Ac)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
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

        for (exchange in metered) {

            val decoded = V2GTPDispatcher.decode(exchange.response.bytes)
            val message = (decoded as V2GTPDecodeResult.Decoded).message as V2G_Message
            val status  = message.body.bodyElement as ChargingStatusResType
            val info    = status.meterInfo!!

            assertEquals("VAN*M*4711", info.meterID)
            assertEquals(4200uL, info.meterReading)

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
