package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso20.common.MessageHeaderType
import cloud.charging.v2g.iso20.common.ResponseCode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

import cloud.charging.v2g.iso20.dc.DC_CableCheckRes
import cloud.charging.v2g.iso20.dc.Processing

/**
 * What this EVCC does when the station answers with a `FAILED` code.
 *
 * **Found live, not by reasoning, and not by this suite.** Until 2026-08-01 no -20 EVCC in this
 * repository read a response code at all, in any of the three languages. eVDriveFlow answered
 * `DC_CableCheckRes` with `FAILED` and the car went on charging
 * (`../docs/interop-runs/2026-08-01-edf-iso20-dc-notls/`).
 *
 * The trace corpus — this port's entire oracle — could not have shown it: every recorded response is
 * one our own SECC produced, and our own SECC never says FAILED. So the corpus is silent here by
 * construction, and this test is the station it does not contain.
 */
class Evcc20FailureTest {

    private fun evcc(): Evcc20Dc =
        Evcc20Dc(V2GTPStream(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()),
                 clock = { 1_767_225_600uL }, pollDelay = { })

    private fun cableCheck(code: cloud.charging.v2g.iso20.dc.ResponseCode) =
        DC_CableCheckRes(cloud.charging.v2g.iso20.dc.MessageHeaderType(ByteArray(8), 1_767_225_600uL, null),
                         code, Processing.Finished)


    /**
     * The ordering the `ordinal >= FAILED` comparison rests on.
     *
     * The check is a range test, sound only while the schema keeps its three families contiguous and in
     * order. A regenerated enum that interleaved them would quietly turn failures into successes — the
     * very shape of bug this file exists because of.
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

        // The DC message set carries its own copy, generated separately from the same schema.
        assertTrue(cloud.charging.v2g.iso20.dc.ResponseCode.FAILED.ordinal == ResponseCode.FAILED.ordinal)
    }


    @Test
    fun aFailedResponseEndsTheSession() {
        val thrown = assertFailsWith<SessionAborted> {
            evcc().refuseOnFailure(cableCheck(cloud.charging.v2g.iso20.dc.ResponseCode.FAILED))
        }

        // Both halves have to be in the message: which message failed, and with what.
        assertContains(thrown.message!!, "DC_CableCheckRes")
        assertContains(thrown.message!!, "FAILED")
    }


    /**
     * A WARNING is not a failure. The specification has three families because `WARNING*` means
     * "something is off and the session continues"; aborting on it would turn an expiring certificate
     * into a refused charge.
     */
    @Test
    fun aWarningDoesNotEndTheSession() {
        evcc().refuseOnFailure(cableCheck(cloud.charging.v2g.iso20.dc.ResponseCode.WARNING_CertificateExpired))
    }

}
