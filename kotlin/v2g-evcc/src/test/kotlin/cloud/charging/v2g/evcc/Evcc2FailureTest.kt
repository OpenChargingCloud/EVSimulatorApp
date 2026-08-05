package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso2.PaymentServiceSelectionResType
import cloud.charging.v2g.iso2.ResponseCode
import cloud.charging.v2g.iso2.SessionSetupResType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What this -2 EVCC does when the station answers with a `FAILED` code.
 *
 * The -2 half of the gap eVDriveFlow exposed in the -20 EVCC on 2026-08-01
 * (`../../docs/interop-runs/2026-08-01-edf-iso20-dc-notls/`, finding 3). Same hole,
 * invisible for the same reason: our own SECC never answers FAILED, so the trace corpus — this port's
 * whole oracle — contains no such response.
 */
class Evcc2FailureTest {

    // The transport is never touched: refuseOnFailure inspects a decoded body and nothing else.
    private fun evcc(): Evcc2 =
        Evcc2(V2GTPStream(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()),
              PowerMode.Ac, pollDelay = { })


    /**
     * The ordering the `ordinal >= FAILED` comparison rests on.
     *
     * -2 has two families, not three: four `OK*` values and then `FAILED` onwards, with no `WARNING`.
     */
    @Test
    fun theResponseCodeFamiliesAreContiguousAndOrdered() {
        for (code in ResponseCode.entries) {
            if (code.name.startsWith("FAILED"))
                assertTrue(code.ordinal >= ResponseCode.FAILED.ordinal, "${code.name} sorts below FAILED")
            else
                assertTrue(code.ordinal < ResponseCode.FAILED.ordinal,
                           "${code.name} is not a failure but sorts at or above FAILED")
        }
    }


    @Test
    fun aFailedResponseEndsTheSession() {
        val thrown = assertFailsWith<SessionAborted> {
            evcc().refuseOnFailure(SessionSetupResType(ResponseCode.FAILED, "DE*ABC*E1", null))
        }

        assertContains(thrown.message!!, "SessionSetupResType")
        assertContains(thrown.message!!, "FAILED")
    }


    /** The reflective read has to find the code on every response type, not just the first one tried —
     *  a lookup that missed it would be the fail-open shape this check exists to avoid. */
    @Test
    fun theCodeIsFoundOnMoreThanOneResponseType() {
        assertFailsWith<SessionAborted> {
            evcc().refuseOnFailure(PaymentServiceSelectionResType(ResponseCode.FAILED_ServiceSelectionInvalid))
        }
    }


    @Test
    fun anOkResponseIsLetThrough() {
        evcc().refuseOnFailure(SessionSetupResType(ResponseCode.OK_NewSessionEstablished, "DE*ABC*E1", null))
    }

}
