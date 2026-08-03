package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso20.common.Authorization
import cloud.charging.v2g.iso20.common.AuthorizationRes
import cloud.charging.v2g.iso20.common.AuthorizationSetupRes
import cloud.charging.v2g.iso20.common.CommonMessagesCodec
import cloud.charging.v2g.iso20.common.EIM_ASResAuthorizationModeType
import cloud.charging.v2g.iso20.common.MessageHeaderType
import cloud.charging.v2g.iso20.common.ParameterSetType
import cloud.charging.v2g.iso20.common.ParameterType
import cloud.charging.v2g.iso20.common.PnC_ASResAuthorizationModeType
import cloud.charging.v2g.iso20.common.Processing
import cloud.charging.v2g.iso20.common.ResponseCode
import cloud.charging.v2g.iso20.common.ServiceDetailReq
import cloud.charging.v2g.iso20.common.ServiceDetailRes
import cloud.charging.v2g.iso20.common.ServiceDiscoveryRes
import cloud.charging.v2g.iso20.common.ServiceListType
import cloud.charging.v2g.iso20.common.ServiceParameterListType
import cloud.charging.v2g.iso20.common.ServiceType
import cloud.charging.v2g.iso20.common.SessionSetupRes
import cloud.charging.v2g.tp.MessageSet
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What this -20 EVCC does with an offer that differs from ours — the Kotlin half of C#'s
 * `EvccReadsTheOfferTests` (-20 rows) and the refusal from `Evcc20DynamicModeTests`.
 *
 * Every station here is a [ScriptedStation] whose catalogue disagrees with what this EVCC would
 * pick, because the trace corpus structurally cannot contain one: our own station supplies exactly
 * what our own EVCC assumes. The C# sweep of 2026-08-03 found the fallbacks these tests now pin —
 * `offered[0]` across message sets, and EIM assumed without being offered.
 */
class Evcc20OfferTest {

    private val sessionId  = ByteArray(8) { 0x22 }
    private val recordedAt = { 1_767_225_600uL }

    private fun header() = MessageHeaderType(sessionId, 1_767_225_600u, null)

    private fun res(msg: Any): ByteArray =
        ScriptedStation.framed(MessageSet.Iso20CommonMessages, CommonMessagesCodec.encodeAny(msg))

    private fun sessionSetup() = res(SessionSetupRes(header(), ResponseCode.OK_NewSessionEstablished, "DE*ABC*E1"))

    private fun authSetupEim() = res(AuthorizationSetupRes(header(), ResponseCode.OK,
        listOf(Authorization.EIM), certificateInstallationService = false,
        eIM_ASResAuthorizationMode = EIM_ASResAuthorizationModeType(),
        pnC_ASResAuthorizationMode = null))

    private fun authFinished() = res(AuthorizationRes(header(), ResponseCode.OK, Processing.Finished))

    private fun discovery(vararg serviceIds: UShort) = res(ServiceDiscoveryRes(header(), ResponseCode.OK,
        serviceRenegotiationSupported = false,
        energyTransferServiceList = ServiceListType(serviceIds.map { ServiceType(it, freeService = true) }),
        vASList = null))


    /** A station that offers Plug & Charge and nothing else — legal, and the EV has to hear it at
     *  AuthorizationSetup rather than send an EIM request the station just said it cannot answer. */
    @Test
    fun anEimCarAtAPncOnlyStationIsRefusedByName() {

        val station = ScriptedStation(
            ScriptedStation.sapOk(),
            sessionSetup(),
            res(AuthorizationSetupRes(header(), ResponseCode.OK,
                listOf(Authorization.PnC), certificateInstallationService = false,
                eIM_ASResAuthorizationMode = null,
                pnC_ASResAuthorizationMode = PnC_ASResAuthorizationModeType(ByteArray(16), null))))

        SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        val thrown = assertFailsWith<SessionAborted> {
            Evcc20Dc(station.stream, recordedAt, pollDelay = { }).run()
        }

        assertContains(thrown.message!!, "EIM")
        assertContains(thrown.message!!, "PnC")   // …and says what was on offer instead
    }


    /** A DC car at an AC-only station. The old fallback took `offered[0]` — the AC service — and
     *  then sent the next request on the DC message set, refused two exchanges later for a reason
     *  that no longer names the cause. */
    @Test
    fun aDcCarAtAnAcOnlyStationIsRefusedByName() {

        val station = ScriptedStation(
            ScriptedStation.sapOk(), sessionSetup(), authSetupEim(), authFinished(),
            discovery(1u))

        SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        val thrown = assertFailsWith<SessionAborted> {
            Evcc20Dc(station.stream, recordedAt, pollDelay = { }).run()
        }

        assertContains(thrown.message!!, "DC")
        assertContains(thrown.message!!, "offered 1",
            message = "the refusal names the catalogue — the old code silently took service 1 and " +
                      "then sent DC messages against it")
    }


    /** The fallback the previous test does *not* remove: MCS ids ride the DC message set, so a DC
     *  car at a megawatt-only charger takes service 8 rather than refusing — fall back within the
     *  message set you speak. */
    @Test
    fun aDcCarTakesTheMegawattServiceWhenNothingElseIsOffered() {

        val station = ScriptedStation(
            ScriptedStation.sapOk(), sessionSetup(), authSetupEim(), authFinished(),
            discovery(8u))

        SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        assertFailsWith<EOFException> {   // the script ends here; the ServiceDetailReq is already written
            Evcc20Dc(station.stream, recordedAt, pollDelay = { }).run()
        }

        val detail = station.sessionRequestPayloads()
            .map { CommonMessagesCodec.decodeAny(it) }
            .filterIsInstance<ServiceDetailReq>()
            .single()

        assertEquals(8u.toUShort(), detail.serviceID)
    }


    /** A station that only offers Scheduled must produce a named refusal, not a session that
     *  negotiates one mode and then asks in the other: the parameter set the EV selects is what the
     *  station answers in kind against for the rest of the session. */
    @Test
    fun dynamicAgainstAScheduledOnlyStationIsRefusedByName() {

        val station = ScriptedStation(
            ScriptedStation.sapOk(), sessionSetup(), authSetupEim(), authFinished(),
            discovery(2u),
            res(ServiceDetailRes(header(), ResponseCode.OK, 2u,
                ServiceParameterListType(listOf(ParameterSetType(1u, listOf(
                    ParameterType("ControlMode", null, null, null, 1, null, null))))))))

        SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        val thrown = assertFailsWith<SessionAborted> {
            Evcc20Dc(station.stream, recordedAt, pollDelay = { })
                .apply { preferDynamicControlMode = true }
                .run()
        }

        assertContains(thrown.message!!, "Dynamic")
        // The error names what was missing, because that is what a live run is read from.
        assertContains(thrown.message!!, "ControlMode")
    }

}
