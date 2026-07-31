package cloud.charging.v2g.evcc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
