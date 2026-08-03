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
 * One protocol the EVCC is prepared to run, in a SupportedAppProtocol offer: the variant, and for
 * -20 the power mode that picks the application namespace (…-20:DC / …-20:AC).
 *
 * The list order handed to [SapHandshake.runEvccSide] is the EV's preference: entry 0 is offered at
 * Priority 1 (the highest) with SchemaID 1, entry 1 at Priority 2 with SchemaID 2, and so on.
 */
data class SapOffer(val protocol: ProtocolVariant, val mode: PowerMode = PowerMode.Dc)

/**
 * The SupportedAppProtocol handshake every session opens with, before either side switches to the
 * negotiated -2/-20 codec. A port of the C# `SapHandshake`, EVCC side only — the station half has
 * no home on a phone.
 *
 * Two shapes: the single-protocol overload negotiates a fixed protocol, for a caller that knows in
 * advance which one it is testing. The list overload is the real thing — every protocol the EV can
 * run in **one** request, the state machine chosen *after* the handshake from whichever entry the
 * station picked. That is the case a multiplexing station (EVerest's `IsoMux`) exists for, and held
 * to the `*-sapboth` traces.
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

    // Version numbers per protocol: ISO 15118-2:2013 MsgDef is protocol version 2.0, the -20 sets
    // are 1.0. A live Josev SECC matches namespace AND major version — offering -2 as "1.0" gets
    // Failed_NoNegotiation.
    private fun versionFor(variant: ProtocolVariant) =
        if (variant == ProtocolVariant.Iso15118_2) 2u else 1u

    /** Offers exactly [wanted] and throws [SessionAborted] if the station rejects it. */
    fun runEvccSide(stream: V2GTPStream, wanted: ProtocolVariant, mode: PowerMode = PowerMode.Dc) {
        runEvccSide(stream, listOf(SapOffer(wanted, mode)))
    }

    /**
     * The multi-protocol offer: every entry in one request, best first, and the state machine is
     * chosen **after** the handshake — the caller runs whichever came back.
     *
     * @return the offer the station accepted, mapped back through the answered SchemaID.
     */
    fun runEvccSide(stream: V2GTPStream, offers: List<SapOffer>): SapOffer {

        require(offers.size in 1..20) { "a SupportedAppProtocol offer carries 1..20 entries" }

        val request = SupportedAppProtocolReq(offers.mapIndexed { i, offer ->
            AppProtocolType(namespaceFor(offer.protocol, offer.mode),
                            versionFor(offer.protocol), 0u,
                            schemaID = (i + 1).toUByte(), priority = (i + 1).toUByte())
        })

        stream.writeRawFrame(V2GTP.PAYLOAD_TYPE_APP_PROTOCOL, SupportedAppProtocolCodec.encode(request))

        val (frame, _) = stream.readRawFrame()
        val response = SupportedAppProtocolCodec.decodeAny(frame.copyOfRange(V2GTP.HEADER_SIZE, frame.size))

        if (response !is SupportedAppProtocolRes)
            throw SessionAborted("SAP: expected a SupportedAppProtocolRes.")

        if (response.responseCode != ResponseCode.OK_SuccessfulNegotiation &&
            response.responseCode != ResponseCode.OK_SuccessfulNegotiationWithMinorDeviation)
            throw SessionAborted("SAP: SECC rejected the protocol offer (${response.responseCode}).")

        // The SchemaID says *which* of the offered protocols was accepted, and it was read by
        // nobody until the C# sweep of 2026-08-03: harmless while the offer was a single entry, and
        // a silent protocol mismatch now that it is not — the whole point of a multi-protocol offer
        // is that the answer decides which state machine runs next.
        val schemaId = response.schemaID?.toInt()
        if (schemaId == null || schemaId < 1 || schemaId > offers.size)
            throw SessionAborted(
                "SAP: the SECC accepted SchemaID ${response.schemaID ?: "<none>"}, which is not " +
                "among the offered (${request.appProtocol.joinToString(", ") { it.schemaID.toString() }}).")

        return offers[schemaId - 1]
    }
}
