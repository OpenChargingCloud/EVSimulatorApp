package cloud.charging.v2g.evcc

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The handshake reads *which* protocol was accepted, not only that one was — and since the
 * multi-protocol offer, that answer decides which state machine runs next.
 *
 * The single-offer refusal mirrors `EvccReadsTheOfferTests.Sap_AnAcceptedSchemaWeNeverOfferedIsRefused`
 * (found in the C# sweep of 2026-08-03). The `*-sapboth` traces hold the multi-offer end to end: the
 * request's two entries, the station's answered SchemaID, and the session that has to follow it.
 */
class SapHandshakeTest {

    private val recordedAt = { 1_767_225_600uL }

    private fun both(mode: PowerMode) = listOf(
        SapOffer(ProtocolVariant.Iso15118_20, mode),
        SapOffer(ProtocolVariant.Iso15118_2,  mode))


    @Test
    fun anAcceptedSchemaWeNeverOfferedIsRefused() {

        val station = ScriptedStation(ScriptedStation.sapOk(schemaID = 7u))

        val thrown = assertFailsWith<SessionAborted> {
            SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_2, PowerMode.Ac)
        }

        assertContains(thrown.message!!, "SchemaID 7")
    }


    /** The answered SchemaID selects among the offers — 2 names the priority-2 entry, protocol and
     *  mode alike. */
    @Test
    fun theAnsweredSchemaIdSelectsAmongTheOffers() {

        val station = ScriptedStation(ScriptedStation.sapOk(schemaID = 2u))

        val accepted = SapHandshake.runEvccSide(station.stream, both(PowerMode.Ac))

        assertEquals(SapOffer(ProtocolVariant.Iso15118_2, PowerMode.Ac), accepted)
    }


    @Test
    fun anAcceptedSchemaOutsideAMultiOfferIsRefused() {

        val station = ScriptedStation(ScriptedStation.sapOk(schemaID = 7u))

        val thrown = assertFailsWith<SessionAborted> {
            SapHandshake.runEvccSide(station.stream, both(PowerMode.Dc))
        }

        assertContains(thrown.message!!, "SchemaID 7")
        assertContains(thrown.message!!, "1, 2")   // …and names what was on offer
    }


    // ── the negotiated sessions, held to the corpus ───────────────────────
    //
    // The dispatch below — run whichever machine the accepted offer names — is the capability these
    // traces exist for: the state machine is chosen AFTER the handshake, and a port that chose it
    // before would replay one of the two traces wrongly.

    private fun runNegotiated(name: String, mode: PowerMode): Pair<TraceReplay, SapOffer> {

        val replay = TraceReplay(SessionTrace.load(name))
        val stream = V2GTPStream(replay.input, replay.output)

        val accepted = SapHandshake.runEvccSide(stream, both(mode))

        when (accepted.protocol) {
            ProtocolVariant.Iso15118_2  -> Evcc2(stream, accepted.mode, pollDelay = { }).run()
            ProtocolVariant.Iso15118_20 -> (if (accepted.mode == PowerMode.Dc)
                                                Evcc20Dc(stream, recordedAt, pollDelay = { })
                                            else Evcc20Ac(stream, recordedAt, pollDelay = { })).run()
        }

        return replay to accepted
    }


    /** The station speaks only -2 and answers SchemaID 2 — the priority-2 entry — so the -2 session
     *  must run. Also the one recording in which the answered SchemaID is not 1, which is what shows
     *  a station echoing the accepted entry rather than a literal. */
    @Test
    fun offeringBothAtAnIso2OnlyStationRunsTheIso2Session() {

        val (replay, accepted) = runNegotiated("iso2-ac-eim-sapboth", PowerMode.Ac)

        assertEquals(ProtocolVariant.Iso15118_2, accepted.protocol,
            "the station accepted the priority-2 entry; only its answered SchemaID says so")
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges")
    }


    @Test
    fun offeringBothAtAnIso20StationRunsTheIso20Session() {

        val (replay, accepted) = runNegotiated("iso20-dc-eim-sapboth", PowerMode.Dc)

        assertEquals(ProtocolVariant.Iso15118_20, accepted.protocol)
        assertTrue(replay.complete,
            "the session stopped after ${replay.replayed} recorded exchanges")
    }

}
