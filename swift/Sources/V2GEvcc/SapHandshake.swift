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

/// One protocol the EVCC is prepared to run, in a SupportedAppProtocol offer: the variant, and for
/// -20 the power mode that picks the application namespace (…-20:DC / …-20:AC).
///
/// The list order handed to ``SapHandshake/runEvccSide(_:_:)-swift.type.method`` is the EV's
/// preference: entry 0 is offered at Priority 1 (the highest) with SchemaID 1, entry 1 at Priority 2
/// with SchemaID 2, and so on. The field is `variant` rather than `protocol` for the same reason
/// `SessionTrace` reads `protocolName`: `protocol` is Swift's keyword, not ours.
public struct SapOffer: Equatable, Sendable {
    public let variant: ProtocolVariant
    public let mode: PowerMode
    public init(_ variant: ProtocolVariant, _ mode: PowerMode = .dc) {
        self.variant = variant
        self.mode = mode
    }
}

/// The SupportedAppProtocol handshake every session opens with, before either side switches to the
/// negotiated -2/-20 codec. A port of the C# `SapHandshake`, EVCC side only — the station half has
/// no home on a phone.
///
/// Two shapes: the single-protocol overload negotiates a fixed protocol, for a caller that knows in
/// advance which one it is testing. The list overload is the real thing — every protocol the EV can
/// run in **one** request, the state machine chosen *after* the handshake from whichever entry the
/// station picked. That is the case a multiplexing station (EVerest's `IsoMux`) exists for, and it
/// is held to the `*-sapboth` traces.
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

    // Version numbers per protocol: ISO 15118-2:2013 MsgDef is protocol version 2.0, the -20 sets
    // are 1.0. A live Josev SECC matches namespace AND major version — offering -2 as "1.0" gets
    // Failed_NoNegotiation.
    private static func versionFor(_ variant: ProtocolVariant) -> UInt32 {
        variant == .iso15118_2 ? 2 : 1
    }

    /// Offers exactly `wanted`, and throws `SessionAborted` if the station rejects it.
    public static func runEvccSide(_ stream: V2GTPStream,
                                   _ wanted: ProtocolVariant,
                                   _ mode: PowerMode = .dc) throws {
        _ = try runEvccSide(stream, [SapOffer(wanted, mode)])
    }

    /// The multi-protocol offer: every entry in one request, best first, and the state machine is
    /// chosen **after** the handshake — the caller runs whichever came back.
    ///
    /// - Returns: the offer the station accepted, mapped back through the answered SchemaID.
    public static func runEvccSide(_ stream: V2GTPStream, _ offers: [SapOffer]) throws -> SapOffer {

        guard (1...20).contains(offers.count) else {
            throw SessionAborted("a SupportedAppProtocol offer carries 1..20 entries.")
        }

        let request = SupportedAppProtocolReq(appProtocol: offers.enumerated().map { i, offer in
            AppProtocolType(protocolNamespace: namespaceFor(offer.variant, offer.mode),
                            versionNumberMajor: versionFor(offer.variant), versionNumberMinor: 0,
                            schemaID: UInt8(i + 1), priority: UInt8(i + 1))
        })

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
        // nobody until the C# sweep of 2026-08-03: harmless while the offer was a single entry, and
        // a silent protocol mismatch now that it is not — the whole point of a multi-protocol offer
        // is that the answer decides which state machine runs next.
        guard let schemaId = response.schemaID.map(Int.init), (1...offers.count).contains(schemaId) else {
            throw SessionAborted(
                "SAP: the SECC accepted SchemaID \(response.schemaID.map(String.init) ?? "<none>"), "
              + "which is not among the offered "
              + "(\(request.appProtocol.map { String($0.schemaID) }.joined(separator: ", "))).")
        }

        return offers[schemaId - 1]
    }
}
