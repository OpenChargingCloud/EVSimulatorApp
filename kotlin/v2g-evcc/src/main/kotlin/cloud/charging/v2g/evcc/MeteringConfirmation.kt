package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso20.common.CommonMessagesCodec
import cloud.charging.v2g.iso20.common.DetailedCostType
import cloud.charging.v2g.iso20.common.DetailedTaxType
import cloud.charging.v2g.iso20.common.MessageHeaderType
import cloud.charging.v2g.iso20.common.MeterInfoType
import cloud.charging.v2g.iso20.common.MeteringConfirmationReq
import cloud.charging.v2g.iso20.common.MeteringConfirmationReqType
import cloud.charging.v2g.iso20.common.RationalNumberType
import cloud.charging.v2g.iso20.common.ReceiptType
import cloud.charging.v2g.iso20.common.ResponseCode
import cloud.charging.v2g.iso20.common.SignedMeteringDataType
import cloud.charging.v2g.keystore.V2GSigner

import cloud.charging.v2g.iso20.ac.DetailedCostType as AcDetailedCostType
import cloud.charging.v2g.iso20.ac.MeterInfoType as AcMeterInfoType
import cloud.charging.v2g.iso20.ac.ReceiptType as AcReceiptType
import cloud.charging.v2g.iso20.dc.DetailedCostType as DcDetailedCostType
import cloud.charging.v2g.iso20.dc.MeterInfoType as DcMeterInfoType
import cloud.charging.v2g.iso20.dc.ReceiptType as DcReceiptType

/**
 * One `MeteringConfirmationReq` the vehicle signed and sent, and what the station said to it.
 *
 * @param meterId the meter the confirmed reading came from
 * @param chargedWh the reading the vehicle confirmed it had received
 * @param vehicleWh what the vehicle's own meter had counted at the same iteration
 * @param withReceipt whether the station's receipt was confirmed with it ([V2G20-2236])
 * @param responseCode the station's answer
 */
data class MeteringConfirmationSent(
    val meterId: String,
    val chargedWh: ULong,
    val vehicleWh: Double,
    val withReceipt: Boolean,
    val responseCode: ResponseCode,
)


/**
 * The vehicle's half of the -20 `MeteringConfirmation` exchange. A port of the C#
 * `StateMachines.Iso20.MeteringConfirmation`, minus the station's checks.
 *
 * The station asks by putting `MeterInfo` into a charge-loop response and setting `EVSENotification`
 * to `MeteringConfirmation` ([V2G20-1083]), and only when the vehicle authorised with Plug & Charge
 * ([V2G20-2704]): the signature is made with the contract key ([V2G20-1808]). The vehicle answers on
 * the side stream, V2GTP payload type [PAYLOAD_TYPE] ([V2G20-1084]), and the station answers on the
 * same type ([V2G20-1085]).
 *
 * What is signed is the EXI fragment of the whole `MeteringConfirmationReq` with an empty signature
 * slot, under the reference `#id1` that names `SignedMeteringData` — the fragment cbV2G's
 * `iso20_exiFragment` carries for this message set. No independent stack signs or verifies one today,
 * so that choice is the reference encoder's and has not been met on the wire.
 */
object MeteringConfirmation {

    /** The `Id` the vehicle gives `SignedMeteringData`, and so the reference's URI. */
    const val REFERENCE_ID = "id1"

    /** V2GTP payload type `MeteringConfirmationPayloadID` (Table 14). */
    val PAYLOAD_TYPE: UShort = 0x8102u

    /** The octets a `MeteringConfirmationReq` is signed over: the request with [header]'s signature
     *  slot empty, whatever it holds. */
    fun signedOctets(header: MessageHeaderType, data: SignedMeteringDataType): ByteArray =
        CommonMessagesCodec.encodeFragment_MeteringConfirmationReq(
            MeteringConfirmationReqType(header.copy(signature = null), data))

    /** The request, signed with the contract key in the form this car signs its `AuthorizationReq`
     *  with, under the same [suite]. */
    fun sign(header: MessageHeaderType, data: SignedMeteringDataType, contractKey: V2GSigner,
             suite: XmlDsigSuite): MeteringConfirmationReq =
        MeteringConfirmationReq(
            header.copy(signature = XmlDsigInterop.sign20(
                data.id, signedOctets(header, data), contractKey, suite)),
            data)


    // ── the reading and receipt, moved from the charge loop's message set into CommonMessages ──
    //
    // Each -20 message set generates its own MeterInfoType and ReceiptType from the same schema, and
    // they are mutually unassignable — the duplication SessionContext manages for the header. So these
    // are field-for-field copies that look like boilerplate and are not.

    /** A DC charge-loop reading, as CommonMessages carries it. */
    fun toCommon(m: DcMeterInfoType) = MeterInfoType(
        m.meterID, m.chargedEnergyReadingWh, m.bPT_DischargedEnergyReadingWh, m.capacitiveEnergyReadingVARh,
        m.bPT_InductiveEnergyReadingVARh, m.meterSignature, m.meterStatus, m.meterTimestamp)

    /** An AC charge-loop reading, as CommonMessages carries it. */
    fun toCommon(m: AcMeterInfoType) = MeterInfoType(
        m.meterID, m.chargedEnergyReadingWh, m.bPT_DischargedEnergyReadingWh, m.capacitiveEnergyReadingVARh,
        m.bPT_InductiveEnergyReadingVARh, m.meterSignature, m.meterStatus, m.meterTimestamp)

    /** A DC charge-loop receipt, as CommonMessages carries it. */
    fun toCommon(r: DcReceiptType) = ReceiptType(
        r.timeAnchor, cost(r.energyCosts), cost(r.occupancyCosts), cost(r.additionalServicesCosts),
        cost(r.overstayCosts),
        r.taxCosts.map { DetailedTaxType(it.taxRuleID, RationalNumberType(it.amount.exponent, it.amount.value)) })

    /** An AC charge-loop receipt, as CommonMessages carries it. */
    fun toCommon(r: AcReceiptType) = ReceiptType(
        r.timeAnchor, cost(r.energyCosts), cost(r.occupancyCosts), cost(r.additionalServicesCosts),
        cost(r.overstayCosts),
        r.taxCosts.map { DetailedTaxType(it.taxRuleID, RationalNumberType(it.amount.exponent, it.amount.value)) })

    private fun cost(c: DcDetailedCostType?) = c?.let {
        DetailedCostType(RationalNumberType(it.amount.exponent, it.amount.value),
                         RationalNumberType(it.costPerUnit.exponent, it.costPerUnit.value))
    }

    private fun cost(c: AcDetailedCostType?) = c?.let {
        DetailedCostType(RationalNumberType(it.amount.exponent, it.amount.value),
                         RationalNumberType(it.costPerUnit.exponent, it.costPerUnit.value))
    }
}
