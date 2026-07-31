package cloud.charging.v2g.evcc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Kotlin -20 EVCC against the C# one, byte for byte — the same gate [Evcc2TraceTest] applies to
 * -2, and it has one thing to catch that the -2 corpus cannot.
 *
 * A -20 session crosses **between message sets**: CommonMessages for setup, authorization, service
 * negotiation, schedule exchange and power delivery; AC or DC for charge-parameter discovery, the
 * charge loop, and (DC) cable check, pre-charge and welding detection. Those are separate grammars
 * with separate V2GTP payload types, so a port that muddles two of them writes the wrong payload
 * type into the frame header and the replay says so before the EXI body is even reached.
 *
 * Measured, by sending the AC charge-parameter discovery on the DC set: it fails at **byte 3**, the
 * low half of the payload type. Not byte 2 — 0x8003 and 0x8004 share their high byte, so the whole
 * distinction between two message sets is seven bits apart on the wire.
 */
class Evcc20TraceTest {

    /**
     * The instant the corpus was recorded. -20 puts a timestamp in **every** message header, so
     * without pinning the clock to the recording's own value not one frame would match. That is not
     * a quirk of the test: it is the reason `SessionContext` takes a clock instead of reading one.
     */
    private val recordedAt: () -> ULong = { 1_767_225_600uL }

    private fun replay(name: String, mode: PowerMode): TraceReplay {

        val trace  = SessionTrace.load(name)
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_20, mode)

        val evcc = if (mode == PowerMode.Dc) Evcc20Dc(stream, recordedAt, pollDelay = { })
                   else                      Evcc20Ac(stream, recordedAt, pollDelay = { })
        evcc.run()

        return replay
    }

    @Test
    fun theAcSessionMatchesTheRecordingByteForByte() {
        val replay = replay("iso20-ac-eim", PowerMode.Ac)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    @Test
    fun theDcSessionMatchesTheRecordingByteForByte() {
        val replay = replay("iso20-dc-eim", PowerMode.Dc)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    /**
     * The session really does leave CommonMessages. Byte-exactness already guarantees it, but only
     * as a consequence — this says it in the terms the bug would be described in, so a future
     * failure reads as "it never entered the DC set" rather than "byte 2 differs".
     */
    @Test
    fun theDcSessionCrossesIntoTheDcMessageSet() {

        val trace = SessionTrace.load("iso20-dc-eim")
        // `and 0xFF`, because Byte is signed in Kotlin and 0x80 would otherwise read as -128.
        val payloadTypes = trace.exchanges.map {
            (it.request.bytes[2].toInt() and 0xFF) to (it.request.bytes[3].toInt() and 0xFF)
        }

        assertTrue(payloadTypes.contains(0x80 to 0x02), "no CommonMessages frame")
        assertTrue(payloadTypes.contains(0x80 to 0x04), "no DC frame — the session never left CommonMessages")
        assertTrue(!payloadTypes.contains(0x80 to 0x03), "an AC frame has no business in a DC session")
    }

    /**
     * Plug & Charge, -20: the signed AuthorizationReq matches the recording once its signature is
     * substituted, and the signature this port produced verifies on its own.
     *
     * The two halves are the point. The substitution makes everything *except* 64 bytes comparable
     * exactly — including SignedInfo, therefore the digest, therefore which octets were signed. The
     * verification covers those 64 bytes, which the substitution deliberately discards. Either alone
     * would pass a port that got the other half wrong.
     */
    @Test
    fun theDcPncSessionMatchesTheRecording() {

        val trace  = SessionTrace.load("iso20-dc-pnc")
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        val evcc = Evcc20Dc(stream, recordedAt, pollDelay = { }).apply { pnc = PncMaterial.options }
        evcc.run()

        assertTrue(replay.complete, "replayed ${replay.replayed} of ${trace.exchanges.size} exchanges")
        assertEquals("pnc-signed", evcc.authorizationMode,
            "the session completed but authorized via EIM — then nothing signed was compared")
    }

    /** Which energy-transfer service the negotiation settled on: DC=2, AC=1 (Table 204). The wire
     *  pins it inside ServiceSelectionReq; this names it. */
    @Test
    fun theNegotiationSettlesOnTheServiceForTheMode() {

        fun selected(name: String, mode: PowerMode): UShort {
            val replay = TraceReplay(SessionTrace.load(name))
            val stream = V2GTPStream(replay.input, replay.output)
            SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_20, mode)
            val evcc = if (mode == PowerMode.Dc) Evcc20Dc(stream, recordedAt, pollDelay = { })
                       else                      Evcc20Ac(stream, recordedAt, pollDelay = { })
            evcc.run()
            return evcc.selectedEnergyServiceId
        }

        assertEquals(2u.toUShort(), selected("iso20-dc-eim", PowerMode.Dc))
        assertEquals(1u.toUShort(), selected("iso20-ac-eim", PowerMode.Ac))
    }
}
