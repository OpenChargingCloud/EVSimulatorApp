import ExiAppProtocol
import V2GTP

/// Which protocol a session speaks.
public enum ProtocolVariant: Sendable {
    case iso15118_2
    case iso15118_20
}

/// AC or DC — the two energy-transfer shapes whose middle phases differ.
public enum PowerMode: Sendable {
    case ac
    case dc
}

/// The SupportedAppProtocol handshake every session opens with, before either side switches to the
/// negotiated -2/-20 codec. A port of the C# `SapHandshake`, EVCC side only — the station half has
/// no home on a phone.
///
/// Like the original this offers exactly one protocol rather than a candidate list: the simulator
/// always knows in advance which protocol it is testing.
public enum SapHandshake {

    private static let iso2Namespace    = "urn:iso:15118:2:2013:MsgDef"
    private static let iso20DcNamespace = "urn:iso:std:iso:15118:-20:DC"
    private static let iso20AcNamespace = "urn:iso:std:iso:15118:-20:AC"

    // The -20 ProtocolNamespace is the mode-specific application namespace (…-20:DC / …-20:AC), NOT
    // …-20:CommonMessages — a live Josev interop run rejected the CommonMessages offer
    // (Failed_NoNegotiation); Josev's own -20 DC EVCC offers …-20:DC.
    private static func namespaceFor(_ variant: ProtocolVariant, _ mode: PowerMode) -> String {
        switch variant {
        case .iso15118_2:  return iso2Namespace
        case .iso15118_20: return mode == .dc ? iso20DcNamespace : iso20AcNamespace
        }
    }

    /// Offers exactly `wanted`, and throws `SessionAborted` if the station rejects it.
    public static func runEvccSide(_ stream: V2GTPStream,
                                   _ wanted: ProtocolVariant,
                                   _ mode: PowerMode = .dc) throws {

        // Version numbers per protocol: ISO 15118-2:2013 MsgDef is protocol version 2.0, the -20
        // sets are 1.0. A live Josev SECC matches namespace AND major version — offering -2 as "1.0"
        // gets Failed_NoNegotiation.
        let major: UInt32 = wanted == .iso15118_2 ? 2 : 1

        let request = SupportedAppProtocolReq(appProtocol: [
            AppProtocolType(protocolNamespace: namespaceFor(wanted, mode),
                            versionNumberMajor: major, versionNumberMinor: 0,
                            schemaID: 1, priority: 1)
        ])

        try stream.writeRawFrame(payloadType: V2GTP.payloadTypeAppProtocol,
                                 SupportedAppProtocolCodec.encode(request))

        let (frame, _) = try stream.readRawFrame()
        let decoded = try SupportedAppProtocolCodec.decodeAny(Array(frame[V2GTP.headerSize...]))

        guard let response = decoded as? SupportedAppProtocolRes else {
            throw SessionAborted("SAP: expected a SupportedAppProtocolRes.")
        }

        guard response.responseCode == .OK_SuccessfulNegotiation
           || response.responseCode == .OK_SuccessfulNegotiationWithMinorDeviation else {
            throw SessionAborted("SAP: SECC rejected the protocol offer (\(response.responseCode)).")
        }

        // The SchemaID says *which* of the offered protocols was accepted, and it was read by
        // nobody: harmless while the offer is a single entry, and a silent protocol mismatch the
        // moment it is not. Checked now rather than when the second entry is added — that is the
        // point at which nobody would think to look (found in the C# sweep of 2026-08-03).
        guard response.schemaID == request.appProtocol[0].schemaID else {
            throw SessionAborted(
                "SAP: the SECC accepted SchemaID \(response.schemaID.map(String.init) ?? "<none>"), "
              + "which is not the \(request.appProtocol[0].schemaID) it was offered.")
        }
    }
}
