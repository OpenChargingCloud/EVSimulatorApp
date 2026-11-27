package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso20.common.Authorization
import cloud.charging.v2g.iso20.common.AuthorizationReq
import cloud.charging.v2g.iso20.common.AuthorizationRes
import cloud.charging.v2g.iso20.common.AuthorizationSetupRes
import cloud.charging.v2g.iso20.common.CommonMessagesCodec
import cloud.charging.v2g.iso20.common.MessageHeaderType
import cloud.charging.v2g.iso20.common.PnC_ASResAuthorizationModeType
import cloud.charging.v2g.iso20.common.ResponseCode
import cloud.charging.v2g.iso20.common.ServiceDiscoveryReq
import cloud.charging.v2g.iso20.common.SessionSetupRes
import cloud.charging.v2g.tp.MessageSet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
 * (`../../docs/interop-runs/2026-08-01-edf-iso20-dc-notls/`).
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


    // ── AuthorizationRes, where a WARNING does end it ───────────────────────────────────────────

    private val sessionId = ByteArray(8) { 0x22 }
    private fun header() = MessageHeaderType(sessionId, 1_767_225_600u, null)
    private fun res(msg: Any): ByteArray =
        ScriptedStation.framed(MessageSet.Iso20CommonMessages, CommonMessagesCodec.encodeAny(msg))

    /** A Plug & Charge station up to its verdict on the contract, which is [verdict]. */
    private fun pncStation(verdict: ResponseCode) = ScriptedStation(
        ScriptedStation.sapOk(),
        res(SessionSetupRes(header(), ResponseCode.OK_NewSessionEstablished, "DE*ABC*E1")),
        res(AuthorizationSetupRes(header(), ResponseCode.OK,
            listOf(Authorization.PnC), certificateInstallationService = false,
            eIM_ASResAuthorizationMode = null,
            pnC_ASResAuthorizationMode = PnC_ASResAuthorizationModeType(ByteArray(16) { 7 }, null))),
        res(AuthorizationRes(header(), verdict, cloud.charging.v2g.iso20.common.Processing.Finished)))

    /**
     * EVerest 2026.10.0, 2026-11-27: `WARNING_CertificateValidationError` and `Finished` for a P-256
     * contract. Our car went on to ServiceDiscovery and was refused with `FAILED_SequenceError`.
     * [V2G20-1583] gives four options after such a warning, and that is not one of them.
     */
    @Test
    fun aWarningAtAuthorizationEndsTheSession() {

        val station = pncStation(ResponseCode.WARNING_CertificateValidationError)
        SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)

        val thrown = assertFailsWith<SessionAborted> {
            Evcc20Dc(station.stream, { 1_767_225_600uL }, pollDelay = { })
                .apply { pnc = PncMaterial.options }.run()
        }
        assertContains(thrown.message!!, "AuthorizationRes")
        assertContains(thrown.message!!, "WARNING_CertificateValidationError")

        val requests = station.sessionRequestPayloads().map { CommonMessagesCodec.decodeAny(it) }
        assertTrue(requests.none { it is ServiceDiscoveryReq }, "the car asked for services after the warning")
        assertTrue(requests.last() is AuthorizationReq)
    }

    /** The response that is not a warning still goes on: the stop is a range, and OK is below it. */
    @Test
    fun anOkAtAuthorizationDoesNotStop() {
        evcc().stopOnAuthorizationWarning(AuthorizationRes(header(), ResponseCode.OK,
            cloud.charging.v2g.iso20.common.Processing.Finished))
    }

    /**
     * The -20 `AuthorizationReq` declares `ecdsa-sha512` over a SHA-512 digest ([V2G20-2473],
     * [V2G20-2475]), and its signature verifies under SHA-512. Josev's SHA-256 pair stays available
     * as [XmlDsigSuite.JosevSha256]. EVerest 2026.10.0 refused the SHA-256 form with
     * `FAILED_SignatureError`.
     */
    @Test
    fun thePncSignatureIsEcdsaSha512ByDefault() {

        val station = pncStation(ResponseCode.WARNING_CertificateValidationError)
        SapHandshake.runEvccSide(station.stream, ProtocolVariant.Iso15118_20, PowerMode.Dc)
        assertFailsWith<SessionAborted> {
            Evcc20Dc(station.stream, { 1_767_225_600uL }, pollDelay = { })
                .apply { pnc = PncMaterial.options }.run()
        }

        val req = station.sessionRequestPayloads().map { CommonMessagesCodec.decodeAny(it) }
            .filterIsInstance<AuthorizationReq>().single()
        val signature = req.header.signature!!

        assertEquals("http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512", signature.signedInfo.signatureMethod.algorithm)
        assertEquals("http://www.w3.org/2001/04/xmlenc#sha512", signature.signedInfo.reference.single().digestMethod.algorithm)
        assertContentEquals(
            MessageDigest.getInstance("SHA-512").digest(
                CommonMessagesCodec.encodeFragment_PnC_AReqAuthorizationMode(req.pnC_AReqAuthorizationMode!!)),
            signature.signedInfo.reference.single().digestValue)

        val publicKey = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(PncMaterial.signer.publicKeyDer))
        val octets    = XmlDsigInterop.standaloneOctets(signature.signedInfo)
        assertTrue(XmlDsigInterop.verify(octets, signature.signatureValue.value, publicKey,
                                         XmlDsigSuite.Iso20.signatureMethod))
        assertTrue(!XmlDsigInterop.verify(octets, signature.signatureValue.value, publicKey,
                                          XmlDsigSuite.JosevSha256.signatureMethod),
                   "the -20 signature also verified under SHA-256")
    }

}
