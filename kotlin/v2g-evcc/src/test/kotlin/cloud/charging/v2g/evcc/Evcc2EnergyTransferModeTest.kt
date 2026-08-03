package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso2.AuthorizationResType
import cloud.charging.v2g.iso2.BodyBaseType
import cloud.charging.v2g.iso2.BodyType
import cloud.charging.v2g.iso2.ChargeParameterDiscoveryReqType
import cloud.charging.v2g.iso2.ChargeServiceType
import cloud.charging.v2g.iso2.EVSEProcessing
import cloud.charging.v2g.iso2.EnergyTransferMode
import cloud.charging.v2g.iso2.Iso15118_2Codec
import cloud.charging.v2g.iso2.MessageHeaderType
import cloud.charging.v2g.iso2.PaymentOption
import cloud.charging.v2g.iso2.PaymentOptionListType
import cloud.charging.v2g.iso2.PaymentServiceSelectionReqType
import cloud.charging.v2g.iso2.PaymentServiceSelectionResType
import cloud.charging.v2g.iso2.ResponseCode
import cloud.charging.v2g.iso2.ServiceCategory
import cloud.charging.v2g.iso2.ServiceDiscoveryResType
import cloud.charging.v2g.iso2.SessionSetupResType
import cloud.charging.v2g.iso2.SupportedEnergyTransferModeType
import cloud.charging.v2g.iso2.V2G_Message
import cloud.charging.v2g.tp.MessageSet
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Which energy transfer mode this -2 EVCC asks for, and where it gets the answer — the Kotlin half
 * of C#'s `Evcc2EnergyTransferModeTests` and `EvccReadsTheOfferTests` (-2 rows).
 *
 * It used to be a constant, in this port exactly as in C#: `AC_three_phase_core` for AC,
 * `DC_extended` for DC, and `ServiceID = 1` in the service selection. That passed against every
 * trace, because every trace is a session with our own station — which offers exactly the mode and
 * the id the constants named. EVerest's AC SIL advertises single-phase and answers a three-phase
 * request with `FAILED_WrongEnergyTransferMode`, correctly, seven messages in
 * (`docs/interop-runs/2026-08-03-everest-ac/`). Each test here is a [ScriptedStation] whose offer
 * differs from ours, because that is the only place these behaviours can be seen from.
 */
class Evcc2EnergyTransferModeTest {

    private val sessionId = ByteArray(8) { 0x11 }

    private fun res(body: BodyBaseType): ByteArray =
        ScriptedStation.framed(MessageSet.Iso15118_2, Iso15118_2Codec.encode(
            V2G_Message(MessageHeaderType(sessionId, notification = null, signature = null), BodyType(body))))

    private fun discovery(offered: List<EnergyTransferMode>, serviceId: UShort = 1u) =
        ServiceDiscoveryResType(
            ResponseCode.OK,
            PaymentOptionListType(listOf(PaymentOption.ExternalPayment)),
            ChargeServiceType(serviceId, serviceName = null, serviceCategory = ServiceCategory.EVCharging,
                              serviceScope = null, freeService = true,
                              supportedEnergyTransferMode = SupportedEnergyTransferModeType(offered)),
            serviceList = null)

    /** Runs an AC session against a script that ends after Authorization: the EVCC then writes its
     *  ChargeParameterDiscoveryReq — the message under test — and finds the station gone. */
    private fun chargeParameterDiscoveryFor(offered: List<EnergyTransferMode>): ChargeParameterDiscoveryReqType {

        val station = ScriptedStation(
            ScriptedStation.sapOk(),
            res(SessionSetupResType(ResponseCode.OK_NewSessionEstablished, "DE*ABC*E1", null)),
            res(discovery(offered)),
            res(PaymentServiceSelectionResType(ResponseCode.OK)),
            res(AuthorizationResType(ResponseCode.OK, EVSEProcessing.Finished)))

        SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_2, PowerMode.Ac)
        assertFailsWith<EOFException> {
            Evcc2(station.stream, PowerMode.Ac, pollDelay = { }).run()
        }

        return station.sessionRequestPayloads()
            .map { Iso15118_2Codec.decodeAny(it) }
            .filterIsInstance<V2G_Message>()
            .mapNotNull { it.body.bodyElement }
            .filterIsInstance<ChargeParameterDiscoveryReqType>()
            .single()
    }


    @Test
    fun aSinglePhaseStationGetsASinglePhaseRequest() {
        val cpd = chargeParameterDiscoveryFor(listOf(EnergyTransferMode.AC_single_phase_core))
        assertEquals(EnergyTransferMode.AC_single_phase_core, cpd.requestedEnergyTransferMode,
            "the EV asked for what the station advertised, not for what it prefers")
    }


    @Test
    fun aThreePhaseStationStillGetsThreePhase() {
        val cpd = chargeParameterDiscoveryFor(listOf(EnergyTransferMode.AC_single_phase_core,
                                                     EnergyTransferMode.AC_three_phase_core))
        assertEquals(EnergyTransferMode.AC_three_phase_core, cpd.requestedEnergyTransferMode,
            "offered both, the EV takes the better one")
    }


    @Test
    fun anAcCarAgainstADcOnlyStationIsRefusedByName() {

        val station = ScriptedStation(
            ScriptedStation.sapOk(),
            res(SessionSetupResType(ResponseCode.OK_NewSessionEstablished, "DE*ABC*E1", null)),
            res(discovery(listOf(EnergyTransferMode.DC_extended))))

        SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_2, PowerMode.Ac)
        val thrown = assertFailsWith<SessionAborted> {
            Evcc2(station.stream, PowerMode.Ac, pollDelay = { }).run()
        }

        assertContains(thrown.message!!, "AC")
        // The error names what was offered — that is the line that turns "the station refused"
        // into "it is a DC charger".
        assertContains(thrown.message!!, "DC_extended")
    }


    /** The service id is the station's, not a constant — C#'s
     *  `Iso2_TheSelectedServiceIsTheOneTheStationAdvertised`, against a station numbering its
     *  charge service 7. */
    @Test
    fun theSelectedServiceIsTheOneTheStationAdvertised() {

        val station = ScriptedStation(
            ScriptedStation.sapOk(),
            res(SessionSetupResType(ResponseCode.OK_NewSessionEstablished, "DE*ABC*E1", null)),
            res(discovery(listOf(EnergyTransferMode.AC_three_phase_core), serviceId = 7u)))

        SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_2, PowerMode.Ac)
        assertFailsWith<EOFException> {
            Evcc2(station.stream, PowerMode.Ac, pollDelay = { }).run()
        }

        val selection = station.sessionRequestPayloads()
            .map { Iso15118_2Codec.decodeAny(it) }
            .filterIsInstance<V2G_Message>()
            .mapNotNull { it.body.bodyElement }
            .filterIsInstance<PaymentServiceSelectionReqType>()
            .single()

        assertEquals(7u.toUShort(), selection.selectedServiceList.selectedService.single().serviceID,
            "the EV selected the station's ChargeService id, not the 1 it used to hard-code")
    }

}
