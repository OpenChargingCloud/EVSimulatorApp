import ExiIso20AC
import ExiIso20Common
import ExiIso20DC
import V2GKeystore
import V2GTP

/// One `MeteringConfirmationReq` the vehicle signed and sent, and what the station said to it.
public struct MeteringConfirmationSent: Equatable {
    /// The meter the confirmed reading came from.
    public let meterId: String
    /// The reading the vehicle confirmed it had received.
    public let chargedWh: UInt64
    /// What the vehicle's own meter had counted at the same iteration.
    public let vehicleWh: Double
    /// Whether the station's receipt was confirmed with it ([V2G20-2236]).
    public let withReceipt: Bool
    /// The station's answer.
    public let responseCode: ExiIso20Common.ResponseCode
}


/// The vehicle's half of the -20 `MeteringConfirmation` exchange. A port of the C#
/// `StateMachines.Iso20.MeteringConfirmation`, minus the station's checks; the Kotlin twin is
/// `MeteringConfirmation.kt`.
///
/// The station asks by putting `MeterInfo` into a charge-loop response and setting `EVSENotification`
/// to `MeteringConfirmation` ([V2G20-1083]), and only when the vehicle authorised with Plug & Charge
/// ([V2G20-2704]): the signature is made with the contract key ([V2G20-1808]). The vehicle answers on
/// the side stream, V2GTP payload type ``payloadType`` ([V2G20-1084]), and the station answers on the
/// same type ([V2G20-1085]).
///
/// What is signed is the EXI fragment of the whole `MeteringConfirmationReq` with an empty signature
/// slot, under the reference `#id1` that names `SignedMeteringData` — the fragment cbV2G's
/// `iso20_exiFragment` carries for this message set. No independent stack signs or verifies one today,
/// so that choice is the reference encoder's and has not been met on the wire.
public enum MeteringConfirmation {

    /// The `Id` the vehicle gives `SignedMeteringData`, and so the reference's URI.
    public static let referenceId = "id1"

    /// V2GTP payload type `MeteringConfirmationPayloadID` (Table 14).
    public static let payloadType: UInt16 = 0x8102

    /// The V2GTP header in front of every frame, which a side-stream answer is read past.
    static let v2gtpHeaderSize = V2GTP.headerSize

    /// The octets a `MeteringConfirmationReq` is signed over: the request with `header`'s signature
    /// slot empty, whatever it holds. A fresh header rather than `header` with its signature cleared,
    /// because the generated header is a class and clearing it would clear the caller's too.
    public static func signedOctets(_ header: ExiIso20Common.MessageHeaderType,
                                    _ data: SignedMeteringDataType) -> [UInt8] {
        CommonMessagesCodec.encodeFragment_MeteringConfirmationReq(MeteringConfirmationReqType(
            header: ExiIso20Common.MessageHeaderType(sessionID: header.sessionID, timeStamp: header.timeStamp),
            signedMeteringData: data))
    }

    /// The request, signed with the contract key in the form this car signs its `AuthorizationReq`
    /// with, under the same `suite`.
    static func sign(_ header: ExiIso20Common.MessageHeaderType, _ data: SignedMeteringDataType,
                     _ contractKey: any V2GSigner, _ suite: XmlDsigSuite) throws -> MeteringConfirmationReq {
        let signature = try XmlDsigInterop.sign20(data.id, signedOctets(header, data), contractKey, suite)
        return MeteringConfirmationReq(
            header: ExiIso20Common.MessageHeaderType(sessionID: header.sessionID, timeStamp: header.timeStamp,
                                                     signature: signature),
            signedMeteringData: data)
    }


    // ── the reading and receipt, moved from the charge loop's message set into CommonMessages ──
    //
    // Each -20 message set generates its own MeterInfoType and ReceiptType from the same schema, and
    // they are mutually unassignable — the duplication SessionContext manages for the header. So these
    // are field-for-field copies that look like boilerplate and are not.

    /// A DC charge-loop reading, as CommonMessages carries it.
    static func toCommon(_ m: ExiIso20DC.MeterInfoType) -> ExiIso20Common.MeterInfoType {
        ExiIso20Common.MeterInfoType(
            meterID: m.meterID, chargedEnergyReadingWh: m.chargedEnergyReadingWh,
            bPT_DischargedEnergyReadingWh: m.bPT_DischargedEnergyReadingWh,
            capacitiveEnergyReadingVARh: m.capacitiveEnergyReadingVARh,
            bPT_InductiveEnergyReadingVARh: m.bPT_InductiveEnergyReadingVARh,
            meterSignature: m.meterSignature, meterStatus: m.meterStatus, meterTimestamp: m.meterTimestamp)
    }

    /// An AC charge-loop reading, as CommonMessages carries it.
    static func toCommon(_ m: ExiIso20AC.MeterInfoType) -> ExiIso20Common.MeterInfoType {
        ExiIso20Common.MeterInfoType(
            meterID: m.meterID, chargedEnergyReadingWh: m.chargedEnergyReadingWh,
            bPT_DischargedEnergyReadingWh: m.bPT_DischargedEnergyReadingWh,
            capacitiveEnergyReadingVARh: m.capacitiveEnergyReadingVARh,
            bPT_InductiveEnergyReadingVARh: m.bPT_InductiveEnergyReadingVARh,
            meterSignature: m.meterSignature, meterStatus: m.meterStatus, meterTimestamp: m.meterTimestamp)
    }

    /// A DC charge-loop receipt, as CommonMessages carries it.
    static func toCommon(_ r: ExiIso20DC.ReceiptType) -> ExiIso20Common.ReceiptType {
        ExiIso20Common.ReceiptType(
            timeAnchor: r.timeAnchor,
            energyCosts: r.energyCosts.map { cost($0.amount, $0.costPerUnit) },
            occupancyCosts: r.occupancyCosts.map { cost($0.amount, $0.costPerUnit) },
            additionalServicesCosts: r.additionalServicesCosts.map { cost($0.amount, $0.costPerUnit) },
            overstayCosts: r.overstayCosts.map { cost($0.amount, $0.costPerUnit) },
            taxCosts: r.taxCosts.map {
                DetailedTaxType(taxRuleID: $0.taxRuleID, amount: rational($0.amount.exponent, $0.amount.value))
            })
    }

    /// An AC charge-loop receipt, as CommonMessages carries it.
    static func toCommon(_ r: ExiIso20AC.ReceiptType) -> ExiIso20Common.ReceiptType {
        ExiIso20Common.ReceiptType(
            timeAnchor: r.timeAnchor,
            energyCosts: r.energyCosts.map { cost($0.amount, $0.costPerUnit) },
            occupancyCosts: r.occupancyCosts.map { cost($0.amount, $0.costPerUnit) },
            additionalServicesCosts: r.additionalServicesCosts.map { cost($0.amount, $0.costPerUnit) },
            overstayCosts: r.overstayCosts.map { cost($0.amount, $0.costPerUnit) },
            taxCosts: r.taxCosts.map {
                DetailedTaxType(taxRuleID: $0.taxRuleID, amount: rational($0.amount.exponent, $0.amount.value))
            })
    }

    private static func cost(_ amount: ExiIso20DC.RationalNumberType,
                             _ costPerUnit: ExiIso20DC.RationalNumberType) -> ExiIso20Common.DetailedCostType {
        ExiIso20Common.DetailedCostType(amount: rational(amount.exponent, amount.value),
                                        costPerUnit: rational(costPerUnit.exponent, costPerUnit.value))
    }

    private static func cost(_ amount: ExiIso20AC.RationalNumberType,
                             _ costPerUnit: ExiIso20AC.RationalNumberType) -> ExiIso20Common.DetailedCostType {
        ExiIso20Common.DetailedCostType(amount: rational(amount.exponent, amount.value),
                                        costPerUnit: rational(costPerUnit.exponent, costPerUnit.value))
    }

    private static func rational(_ exponent: Int8, _ value: Int16) -> ExiIso20Common.RationalNumberType {
        ExiIso20Common.RationalNumberType(exponent: exponent, value: value)
    }
}
