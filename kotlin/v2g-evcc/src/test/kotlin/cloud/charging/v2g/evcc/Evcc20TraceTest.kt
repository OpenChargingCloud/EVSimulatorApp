package cloud.charging.v2g.evcc

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

import cloud.charging.v2g.iso20.dc.DC_ChargeLoopRes
import cloud.charging.v2g.iso20.common.ChargingSession
import cloud.charging.v2g.iso20.common.ResponseCode
import cloud.charging.v2g.metering.MeterSignature
import cloud.charging.v2g.tp.V2GTPDecodeResult
import cloud.charging.v2g.tp.V2GTPDispatcher

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

    // ── the -20 service renegotiation ─────────────────────────────────────

    /**
     * [V2G20-1477], and the first recorded -20 session in which the station changes its mind
     * mid-charge. Byte-exactness is the whole test: a renegotiation is a *sequence*, and getting the
     * order or the re-entry point wrong produces a session that still runs to completion.
     */
    @Test
    fun `the service renegotiation session matches the recording byte for byte`() {
        val replay = replay("iso20-dc-eim-renegotiate", PowerMode.Dc)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    /**
     * The half of [V2G20-1477] that is easiest to get wrong in the direction that still works.
     *
     * A renegotiation re-enters at **ServiceDiscovery**, not at the top of the session: the service is
     * selected again, charge parameters and the schedule are negotiated again — and authorization is
     * **not** repeated. An implementation that restarted the whole session would also charge
     * successfully, and would be telling the station it might be a different car. That claim is about
     * bytes which are *absent*, so only a recorded session can hold it.
     */
    @Test
    fun `a renegotiation re-selects the service but does not re-authorize`() {

        val counts = SessionTrace.load("iso20-dc-eim-renegotiate")
            .exchanges.groupingBy { it.request.message }.eachCount()

        assertEquals(1, counts["AuthorizationSetupReq"], "authorization must happen exactly once")
        assertEquals(1, counts["AuthorizationReq"])

        assertEquals(2, counts["ServiceDiscoveryReq"], "the service is selected again")
        assertEquals(2, counts["ServiceSelectionReq"])
        assertEquals(2, counts["DC_ChargeParameterDiscoveryReq"])
        assertEquals(2, counts["ScheduleExchangeReq"])

        // Two SessionStopReq: the renegotiation's, which does not stop the session, and the real one.
        assertEquals(2, counts["SessionStopReq"])

        // …and welding detection only at the real end, not at the renegotiation.
        assertEquals(1, counts["DC_WeldingDetectionReq"])
    }

    private fun replay(name: String, mode: PowerMode, preferDynamic: Boolean = false): TraceReplay {

        val trace  = SessionTrace.load(name)
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_20, mode)

        val evcc = if (mode == PowerMode.Dc) Evcc20Dc(stream, recordedAt, pollDelay = { })
                   else                      Evcc20Ac(stream, recordedAt, pollDelay = { })
        evcc.preferDynamicControlMode = preferDynamic
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

    @Test
    fun theMeteredDcSessionMatchesTheRecordingByteForByte() {
        val replay = replay("iso20-dc-eim-meter", PowerMode.Dc)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    /**
     * The vehicle's own counter lands where the station's signed reading does — the -20 case, where
     * the EV's figure is half its own and half the station's: it measures the inlet voltage it
     * reported, the station reports the current. 400 V x 120 A for three minutes is 2400 Wh.
     */
    @Test
    fun theVehiclesOwnCounterAgreesWithTheStationsSignedReading() {

        val trace  = SessionTrace.load("iso20-dc-eim-meter")
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        val evcc = Evcc20Dc(stream, recordedAt, pollDelay = { })
        evcc.run()

        assertEquals(2_400uL, evcc.meter.energyWh)

        val last = trace.exchanges.last { it.response.carriesMeterSignature }
        val decoded = V2GTPDispatcher.decode(last.response.bytes)
        val loop = (decoded as V2GTPDecodeResult.Decoded).message as DC_ChargeLoopRes
        assertEquals(loop.meterInfo!!.chargedEnergyReadingWh, evcc.meter.energyWh,
            "the vehicle's counter and the station's last signed reading disagree")
    }

    /**
     * The station's signed reading in -20, and the byte that never travels.
     *
     * `MeterSignature` here and `SigMeterReading` in -2 are the same 64-byte slot over the same
     * payload layout, differing in one thing: the protocol byte, 20 against 2. It is not transmitted,
     * so nothing on the wire can reveal a port that hard-codes the wrong one — the -2 corpus would
     * stay green and every -20 reading would verify against the wrong octets. Two recorded sessions
     * are the only place that shows, which is why both exist.
     */
    @Test
    fun theRecordedMeterReadingVerifiesAndIsNotAMinusTwoReading() {

        val trace = SessionTrace.load("iso20-dc-eim-meter")
        val key   = SignedFrame.publicKey(trace.meterKey!!.x, trace.meterKey.y)

        val metered = trace.exchanges.filter { it.response.carriesMeterSignature }
        assertTrue(metered.isNotEmpty(), "the metered -20 corpus records no reading")

        for (exchange in metered) {

            val decoded = V2GTPDispatcher.decode(exchange.response.bytes)
            val loop    = (decoded as V2GTPDecodeResult.Decoded).message as DC_ChargeLoopRes
            val info    = loop.meterInfo!!

            assertEquals("VAN*M*4711", info.meterID)

            assertTrue(
                MeterSignature.verify(info.meterSignature!!, 20, loop.header.sessionID, info.meterID,
                                      info.chargedEnergyReadingWh, info.meterTimestamp?.toLong(), key),
                "exchange ${exchange.index}: the -20 reading C# recorded does not verify here")

            // …and the same bytes must NOT verify as a -2 reading. If they did, the protocol byte
            // would not be doing its job and a reading could be carried across protocols.
            assertTrue(
                !MeterSignature.verify(info.meterSignature!!, 2, loop.header.sessionID, info.meterID,
                                       info.chargedEnergyReadingWh, info.meterTimestamp?.toLong(), key),
                "a -20 reading verified as a -2 one — the protocol byte is not in the signed payload")
        }
    }

    // ── Dynamic control mode ──────────────────────────────────────────────
    //
    // Recorded 2026-08-03, the day the C# EVCC learned the mode, precisely so the ports could not
    // claim it unchecked: the mode touches the parameter set, ScheduleExchange, the EVPowerProfile
    // and the charge loop, and every one of them is in these bytes.

    @Test
    fun theDcDynamicSessionMatchesTheRecordingByteForByte() {
        val replay = replay("iso20-dc-eim-dynamic", PowerMode.Dc, preferDynamic = true)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    @Test
    fun theAcDynamicSessionMatchesTheRecordingByteForByte() {
        val replay = replay("iso20-ac-eim-dynamic", PowerMode.Ac, preferDynamic = true)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges — it ended early, " +
            "which sends no wrong bytes and would otherwise pass")
    }

    /**
     * The negative, without which the two tests above would also pass on a flag that is read
     * nowhere — if Scheduled and Dynamic produced the same bytes, the Dynamic traces would prove
     * nothing. Diverges at ServiceSelectionReq, the first message the mode reaches (the ControlMode
     * = 2 parameter set).
     */
    @Test
    fun withoutTheFlagTheDynamicTraceDiverges() {
        val thrown = assertFailsWith<TraceMismatch> {
            replay("iso20-dc-eim-dynamic", PowerMode.Dc, preferDynamic = false)
        }
        assertContains(thrown.message!!, "ServiceSelectionReq")
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

    // ── pause and resume ──────────────────────────────────────────────────

    /** -20: the pause. Its successor is the shortest complete -20 session in the corpus. */
    @Test
    fun `the -20 pause session matches the recording`() {

        val trace  = SessionTrace.load("iso20-dc-eim-pause")
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        val evcc = Evcc20Dc(stream, recordedAt, pollDelay = { }).apply {
            stopMode            = ChargingSession.Pause
            seccLeafCertificate = ResumeMaterial.seccLeaf
        }
        evcc.run()

        assertTrue(replay.complete, "replayed ${replay.replayed} of ${trace.exchanges.size} exchanges")
        assertNotNull(evcc.sessionBinding,
            "the paused session carries no binding, so nothing could claim it afterwards")
    }

    /**
     * -20: the resume, and the one that matters. A rejoined session negotiates nothing — it opens at
     * ChargeParameterDiscovery — so a port that replayed its opening sequence would charge perfectly
     * and diverge on the second frame.
     */
    @Test
    fun `the -20 resume session matches the recording`() {

        val paused = SessionTrace.load("iso20-dc-eim-pause")
        val trace  = SessionTrace.load("iso20-dc-eim-resume")
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        val pausedId = sessionIdOf(paused, 2)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        val evcc = Evcc20Dc(stream, recordedAt, pollDelay = { }).apply {
            seccLeafCertificate = ResumeMaterial.seccLeaf
        }
        // DC = service 2 (Table 204): carried over rather than renegotiated, which is the point.
        evcc.resumeFrom(ResumableSession(
            pausedId, SessionBinding20.compute(pausedId, ResumeMaterial.seccLeaf), 2u))
        evcc.run()

        assertTrue(replay.complete, "replayed ${replay.replayed} of ${trace.exchanges.size} exchanges")
        assertEquals(ResponseCode.OK_OldSessionJoined, evcc.sessionSetupCode)
        assertFalse(evcc.resumeRefused)
        assertEquals(true, evcc.resumedStationVerified,
            "the binding was presentable on both sides, so this is the case that had to be checked")
        assertEquals(2u.toUShort(), evcc.selectedEnergyServiceId,
            "a resumed session does not renegotiate the service — it carries the one it had")
    }

    /**
     * -20: the resume the station refuses, because it has no binding to check. The car drops everything
     * the pause carried and runs the full opening sequence — which is what makes this recording
     * eighteen exchanges where the accepted one is thirteen.
     */
    @Test
    fun `a refused -20 resume starts over completely`() {

        val paused = SessionTrace.load("iso20-dc-eim-pause")
        val trace  = SessionTrace.load("iso20-dc-eim-resume-refused")
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        val evcc = Evcc20Dc(stream, recordedAt, pollDelay = { })
        // Offered without a binding on either side — the shape of every plain-TCP resume.
        evcc.resumeFrom(ResumableSession(sessionIdOf(paused, 2), null, 2u))
        evcc.run()

        assertTrue(replay.complete, "replayed ${replay.replayed} of ${trace.exchanges.size} exchanges")
        assertEquals(ResponseCode.OK_NewSessionEstablished, evcc.sessionSetupCode)
        assertTrue(evcc.resumeRefused, "the car cannot tell it was refused, and that is the only record")
        assertNull(evcc.resumedStationVerified)
    }

    // ── contract provisioning ─────────────────────────────────────────────

    /**
     * -20 installation: no service to discover, one signed element instead of four, a P-521 key —
     * and the request placed before the first AuthorizationReq.
     *
     * The position is the whole difference from -2, where provisioning is a value-added service that
     * has to be discovered and selected first. Only a recorded session can state that.
     */
    @Test
    fun `the -20 certificate installation session matches the recording`() {

        val trace  = SessionTrace.load("iso20-dc-eim-certinstall")
        val replay = TraceReplay(trace)
        val stream = V2GTPStream(replay.input, replay.output)

        SapHandshake.runEvccSide(stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        val evcc = Evcc20Dc(stream, recordedAt, pollDelay = { })
            .apply { certInstallRequest = OemMaterial.iso20Install }
        evcc.run()

        assertTrue(replay.complete, "replayed ${replay.replayed} of ${trace.exchanges.size} exchanges")
        assertNotNull(evcc.installedContractKey,
            "no key came out — on -20 that means AES-GCM refused the tag, not that a check was skipped")
        assertEquals(1, evcc.installedContractVerdict?.references,
            "-20 signs the whole SignedInstallationData as one element")
        assertTrue(evcc.installedContractSignatureOk)

        // The exchange runs before authorization, so the session still authorizes by EIM afterwards:
        // installing a contract is not using one.
        assertEquals("eim", evcc.authorizationMode)
    }

    /** The order, named rather than left implicit in the byte comparison. */
    @Test
    fun `-20 asks for a contract before it authorizes and before it discovers anything`() {

        val messages = SessionTrace.load("iso20-dc-eim-certinstall").exchanges.map { it.request.message }
        val provisioning  = messages.indexOf("CertificateInstallationReq")
        val authorization = messages.indexOf("AuthorizationReq")
        val discovery     = messages.indexOf("ServiceDiscoveryReq")

        assertTrue(provisioning > 0, "the trace records no provisioning request")
        assertTrue(provisioning < authorization,
            "-20 asks for a contract before it authorizes — that is what the flag in " +
            "AuthorizationSetupRes is for")
        assertTrue(provisioning < discovery,
            "…and before it has discovered a single service, which is the whole difference from -2")
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
