package cloud.charging.v2g.evcc

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * The handshake reads *which* protocol was accepted, not only that one was.
 *
 * A station that says OK to a schema the EV never offered is latent today, because our handshake
 * offers exactly one entry — and precisely the thing nobody would re-check on the day a second
 * entry is added (found in the C# sweep of 2026-08-03; mirrors
 * `EvccReadsTheOfferTests.Sap_AnAcceptedSchemaWeNeverOfferedIsRefused`).
 */
class SapHandshakeTest {

    @Test
    fun anAcceptedSchemaWeNeverOfferedIsRefused() {

        val station = ScriptedStation(ScriptedStation.sapOk(schemaID = 7u))

        val thrown = assertFailsWith<SessionAborted> {
            SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_2, PowerMode.Ac)
        }

        assertContains(thrown.message!!, "SchemaID 7")
    }

}
