import XCTest
import ExiIso2
import V2GDispatch
@testable import V2GEvcc

/// Which energy transfer mode this -2 EVCC asks for, and where it gets the answer — the Swift half
/// of C#'s `Evcc2EnergyTransferModeTests` and `EvccReadsTheOfferTests` (-2 rows).
///
/// It used to be a constant, in this port exactly as in C#: `AC_three_phase_core` for AC,
/// `DC_extended` for DC, and `ServiceID = 1` in the service selection. That passed against every
/// trace, because every trace is a session with our own station — which offers exactly the mode
/// and the id the constants named. EVerest's AC SIL advertises single-phase and answers a
/// three-phase request with `FAILED_WrongEnergyTransferMode`, correctly, seven messages in
/// (`docs/interop-runs/2026-08-03-everest-ac/`). Each test here is a ``ScriptedStation`` whose
/// offer differs from ours, because that is the only place these behaviours can be seen from.
final class Evcc2EnergyTransferModeTests: XCTestCase {

    private let sessionId = [UInt8](repeating: 0x11, count: 8)

    private func res(_ body: BodyBaseType) -> [UInt8] {
        ScriptedStation.framed(.iso15118_2, Iso15118_2Codec.encode(
            V2G_Message(header: MessageHeaderType(sessionID: sessionId),
                        body: BodyType(bodyElement: body))))
    }

    private func discovery(_ offered: [EnergyTransferMode], serviceId: UInt16 = 1) -> ServiceDiscoveryResType {
        ServiceDiscoveryResType(
            responseCode: .OK,
            paymentOptionList: PaymentOptionListType(paymentOption: [.ExternalPayment]),
            chargeService: ChargeServiceType(
                serviceID: serviceId, serviceCategory: .EVCharging, freeService: true,
                supportedEnergyTransferMode: SupportedEnergyTransferModeType(energyTransferMode: offered)))
    }

    /// Runs an AC session against a script that ends after Authorization: the EVCC then writes its
    /// ChargeParameterDiscoveryReq — the message under test — and finds the station gone.
    private func chargeParameterDiscoveryFor(_ offered: [EnergyTransferMode]) throws -> ChargeParameterDiscoveryReqType {

        let station = ScriptedStation([
            ScriptedStation.sapOk(),
            res(SessionSetupResType(responseCode: .OK_NewSessionEstablished, eVSEID: "DE*ABC*E1")),
            res(discovery(offered)),
            res(PaymentServiceSelectionResType(responseCode: .OK)),
            res(AuthorizationResType(responseCode: .OK, eVSEProcessing: .Finished)),
        ])

        try SapHandshake.runEvccSide(station.stream, .iso15118_2, .ac)
        XCTAssertThrowsError(try Evcc2(station.stream, .ac).run()) { error in
            XCTAssertTrue(String(describing: error).contains("connection closed"),
                          String(describing: error))
        }

        let bodies = try station.sessionRequestPayloads()
            .map { try Iso15118_2Codec.decodeAny($0) }
            .compactMap { ($0 as? V2G_Message)?.body.bodyElement }

        return try XCTUnwrap(bodies.compactMap { $0 as? ChargeParameterDiscoveryReqType }.first)
    }


    func testASinglePhaseStationGetsASinglePhaseRequest() throws {
        let cpd = try chargeParameterDiscoveryFor([.AC_single_phase_core])
        XCTAssertEqual(cpd.requestedEnergyTransferMode, .AC_single_phase_core,
            "the EV asked for what the station advertised, not for what it prefers")
    }


    func testAThreePhaseStationStillGetsThreePhase() throws {
        let cpd = try chargeParameterDiscoveryFor([.AC_single_phase_core, .AC_three_phase_core])
        XCTAssertEqual(cpd.requestedEnergyTransferMode, .AC_three_phase_core,
            "offered both, the EV takes the better one")
    }


    func testAnAcCarAgainstADcOnlyStationIsRefusedByName() throws {

        let station = ScriptedStation([
            ScriptedStation.sapOk(),
            res(SessionSetupResType(responseCode: .OK_NewSessionEstablished, eVSEID: "DE*ABC*E1")),
            res(discovery([.DC_extended])),
        ])

        try SapHandshake.runEvccSide(station.stream, .iso15118_2, .ac)
        XCTAssertThrowsError(try Evcc2(station.stream, .ac).run()) { error in
            let text = String(describing: error)
            XCTAssertTrue(text.contains("AC"), text)
            // The error names what was offered — that is the line that turns "the station
            // refused" into "it is a DC charger".
            XCTAssertTrue(text.contains("DC_extended"), text)
        }
    }


    /// The service id is the station's, not a constant — C#'s
    /// `Iso2_TheSelectedServiceIsTheOneTheStationAdvertised`, against a station numbering its
    /// charge service 7.
    func testTheSelectedServiceIsTheOneTheStationAdvertised() throws {

        let station = ScriptedStation([
            ScriptedStation.sapOk(),
            res(SessionSetupResType(responseCode: .OK_NewSessionEstablished, eVSEID: "DE*ABC*E1")),
            res(discovery([.AC_three_phase_core], serviceId: 7)),
        ])

        try SapHandshake.runEvccSide(station.stream, .iso15118_2, .ac)
        XCTAssertThrowsError(try Evcc2(station.stream, .ac).run())

        let selection = try XCTUnwrap(try station.sessionRequestPayloads()
            .map { try Iso15118_2Codec.decodeAny($0) }
            .compactMap { ($0 as? V2G_Message)?.body.bodyElement as? PaymentServiceSelectionReqType }
            .first)

        XCTAssertEqual(selection.selectedServiceList.selectedService.first?.serviceID, 7,
            "the EV selected the station's ChargeService id, not the 1 it used to hard-code")
    }
}
