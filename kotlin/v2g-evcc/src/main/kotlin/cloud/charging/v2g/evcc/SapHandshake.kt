package cloud.charging.v2g.evcc

import cloud.charging.v2g.appprotocol.AppProtocolType
import cloud.charging.v2g.appprotocol.ResponseCode
import cloud.charging.v2g.appprotocol.SupportedAppProtocolCodec
import cloud.charging.v2g.appprotocol.SupportedAppProtocolReq
import cloud.charging.v2g.appprotocol.SupportedAppProtocolRes
import cloud.charging.v2g.tp.V2GTP

/** Which protocol a session speaks. */
enum class ProtocolVariant { Iso15118_2, Iso15118_20 }

/** AC or DC — the two energy-transfer shapes whose middle phases differ. */
enum class PowerMode { Ac, Dc }

/** A session that cannot continue: the peer refused, or answered something the protocol forbids. */
class SessionAborted(message: String) : Exception(message)

/**
 * The SupportedAppProtocol handshake every session opens with, before either side switches to the
 * negotiated -2/-20 codec. A port of the C# `SapHandshake`, EVCC side only — the station half has
 * no home on a phone.
 *
 * Like the C# original this offers exactly one protocol rather than a candidate list: the simulator
 * always knows in advance which protocol it is testing.
 */
object SapHandshake {

    private const val ISO2_NAMESPACE     = "urn:iso:15118:2:2013:MsgDef"
    private const val ISO20_DC_NAMESPACE = "urn:iso:std:iso:15118:-20:DC"
    private const val ISO20_AC_NAMESPACE = "urn:iso:std:iso:15118:-20:AC"

    // The -20 ProtocolNamespace is the mode-specific application namespace (…-20:DC / …-20:AC), NOT
    // …-20:CommonMessages — a live Josev interop run rejected the CommonMessages offer
    // (Failed_NoNegotiation); Josev's own -20 DC EVCC offers …-20:DC.
    private fun namespaceFor(variant: ProtocolVariant, mode: PowerMode) = when (variant) {
        ProtocolVariant.Iso15118_2  -> ISO2_NAMESPACE
        ProtocolVariant.Iso15118_20 -> if (mode == PowerMode.Dc) ISO20_DC_NAMESPACE else ISO20_AC_NAMESPACE
    }

    /**
     * Offers exactly [wanted] and throws [SessionAborted] if the station rejects it.
     */
    fun runEvccSide(stream: V2GTPStream, wanted: ProtocolVariant, mode: PowerMode = PowerMode.Dc) {

        // Version numbers per protocol: ISO 15118-2:2013 MsgDef is protocol version 2.0, the -20
        // sets are 1.0. A live Josev SECC matches namespace AND major version — offering -2 as "1.0"
        // gets Failed_NoNegotiation.
        val major = if (wanted == ProtocolVariant.Iso15118_2) 2u else 1u

        val request = SupportedAppProtocolReq(listOf(
            AppProtocolType(namespaceFor(wanted, mode), major, 0u, schemaID = 1u, priority = 1u)))

        stream.writeRawFrame(V2GTP.PAYLOAD_TYPE_APP_PROTOCOL, SupportedAppProtocolCodec.encode(request))

        val (frame, _) = stream.readRawFrame()
        val response = SupportedAppProtocolCodec.decodeAny(frame.copyOfRange(V2GTP.HEADER_SIZE, frame.size))

        if (response !is SupportedAppProtocolRes)
            throw SessionAborted("SAP: expected a SupportedAppProtocolRes.")

        if (response.responseCode != ResponseCode.OK_SuccessfulNegotiation &&
            response.responseCode != ResponseCode.OK_SuccessfulNegotiationWithMinorDeviation)
            throw SessionAborted("SAP: SECC rejected the protocol offer (${response.responseCode}).")

        // The SchemaID says *which* of the offered protocols was accepted, and it was read by
        // nobody: harmless while the offer is a single entry, and a silent protocol mismatch the
        // moment it is not. Checked now rather than when the second entry is added — that is the
        // point at which nobody would think to look (found in the C# sweep of 2026-08-03).
        if (response.schemaID != request.appProtocol[0].schemaID)
            throw SessionAborted(
                "SAP: the SECC accepted SchemaID ${response.schemaID ?: "<none>"}, which is not " +
                "the ${request.appProtocol[0].schemaID} it was offered.")
    }
}
