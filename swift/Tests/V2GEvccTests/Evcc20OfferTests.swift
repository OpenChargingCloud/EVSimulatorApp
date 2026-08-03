import XCTest
import ExiIso20Common
import V2GDispatch
@testable import V2GEvcc

/// What this -20 EVCC does with an offer that differs from ours — the Swift half of C#'s
/// `EvccReadsTheOfferTests` (-20 rows) and the refusal from `Evcc20DynamicModeTests`.
///
/// Every station here is a ``ScriptedStation`` whose catalogue disagrees with what this EVCC would
/// pick, because the trace corpus structurally cannot contain one: our own station supplies
/// exactly what our own EVCC assumes. The C# sweep of 2026-08-03 found the fallbacks these tests
/// now pin — `offered[0]` across message sets, and EIM assumed without being offered.
final class Evcc20OfferTests: XCTestCase {

    private let sessionId  = [UInt8](repeating: 0x22, count: 8)
    private let recordedAt: () -> UInt64 = { 1_767_225_600 }

    private func header() -> MessageHeaderType {
        MessageHeaderType(sessionID: sessionId, timeStamp: 1_767_225_600)
    }

    private func sessionSetup() -> [UInt8] {
        ScriptedStation.framed(.iso20CommonMessages, CommonMessagesCodec.encode(
            SessionSetupRes(header: header(), responseCode: .OK_NewSessionEstablished, eVSEID: "DE*ABC*E1")))
    }

    private func authSetupEim() -> [UInt8] {
        ScriptedStation.framed(.iso20CommonMessages, CommonMessagesCodec.encode(
            AuthorizationSetupRes(header: header(), responseCode: .OK,
                                  authorizationServices: [.EIM],
                                  certificateInstallationService: false,
                                  eIM_ASResAuthorizationMode: EIM_ASResAuthorizationModeType())))
    }

    private func authFinished() -> [UInt8] {
        ScriptedStation.framed(.iso20CommonMessages, CommonMessagesCodec.encode(
            AuthorizationRes(header: header(), responseCode: .OK, eVSEProcessing: .Finished)))
    }

    private func discovery(_ serviceIds: [UInt16]) -> [UInt8] {
        ScriptedStation.framed(.iso20CommonMessages, CommonMessagesCodec.encode(
            ServiceDiscoveryRes(header: header(), responseCode: .OK,
                                serviceRenegotiationSupported: false,
                                energyTransferServiceList: ServiceListType(
                                    service: serviceIds.map { ServiceType(serviceID: $0, freeService: true) }))))
    }


    /// A station that offers Plug & Charge and nothing else — legal, and the EV has to hear it at
    /// AuthorizationSetup rather than send an EIM request the station just said it cannot answer.
    func testAnEimCarAtAPncOnlyStationIsRefusedByName() throws {

        let station = ScriptedStation([
            ScriptedStation.sapOk(),
            sessionSetup(),
            ScriptedStation.framed(.iso20CommonMessages, CommonMessagesCodec.encode(
                AuthorizationSetupRes(header: header(), responseCode: .OK,
                                      authorizationServices: [.PnC],
                                      certificateInstallationService: false,
                                      pnC_ASResAuthorizationMode: PnC_ASResAuthorizationModeType(
                                          genChallenge: [UInt8](repeating: 0x20, count: 16))))),
        ])

        try SapHandshake.runEvccSide(station.stream, .iso15118_20, .dc)
        XCTAssertThrowsError(try Evcc20Dc(station.stream, clock: recordedAt).run()) { error in
            let text = String(describing: error)
            XCTAssertTrue(text.contains("EIM"), text)
            XCTAssertTrue(text.contains("PnC"), text)   // …and says what was on offer instead
        }
    }


    /// A DC car at an AC-only station. The old fallback took `offered[0]` — the AC service — and
    /// then sent the next request on the DC message set, refused two exchanges later for a reason
    /// that no longer names the cause.
    func testADcCarAtAnAcOnlyStationIsRefusedByName() throws {

        let station = ScriptedStation([
            ScriptedStation.sapOk(), sessionSetup(), authSetupEim(), authFinished(),
            discovery([1]),
        ])

        try SapHandshake.runEvccSide(station.stream, .iso15118_20, .dc)
        XCTAssertThrowsError(try Evcc20Dc(station.stream, clock: recordedAt).run()) { error in
            let text = String(describing: error)
            XCTAssertTrue(text.contains("DC"), text)
            // The refusal names the catalogue — the old code silently took service 1 and then
            // sent DC messages against it.
            XCTAssertTrue(text.contains("offered 1"), text)
        }
    }


    /// The fallback the previous test does *not* remove: MCS ids ride the DC message set, so a DC
    /// car at a megawatt-only charger takes service 8 rather than refusing — fall back within the
    /// message set you speak.
    func testADcCarTakesTheMegawattServiceWhenNothingElseIsOffered() throws {

        let station = ScriptedStation([
            ScriptedStation.sapOk(), sessionSetup(), authSetupEim(), authFinished(),
            discovery([8]),
        ])

        try SapHandshake.runEvccSide(station.stream, .iso15118_20, .dc)
        XCTAssertThrowsError(try Evcc20Dc(station.stream, clock: recordedAt).run())
        // the script ends here; the ServiceDetailReq is already written

        let detail = try XCTUnwrap(try station.sessionRequestPayloads()
            .map { try CommonMessagesCodec.decodeAny($0) }
            .compactMap { $0 as? ServiceDetailReq }
            .first)

        XCTAssertEqual(detail.serviceID, 8)
    }


    /// A station that only offers Scheduled must produce a named refusal, not a session that
    /// negotiates one mode and then asks in the other: the parameter set the EV selects is what
    /// the station answers in kind against for the rest of the session.
    func testDynamicAgainstAScheduledOnlyStationIsRefusedByName() throws {

        let station = ScriptedStation([
            ScriptedStation.sapOk(), sessionSetup(), authSetupEim(), authFinished(),
            discovery([2]),
            ScriptedStation.framed(.iso20CommonMessages, CommonMessagesCodec.encode(
                ServiceDetailRes(header: header(), responseCode: .OK, serviceID: 2,
                                 serviceParameterList: ServiceParameterListType(parameterSet: [
                                     ParameterSetType(parameterSetID: 1, parameter: [
                                         ParameterType(name: "ControlMode", intValue: 1)
                                     ])
                                 ])))),
        ])

        try SapHandshake.runEvccSide(station.stream, .iso15118_20, .dc)

        let evcc = Evcc20Dc(station.stream, clock: recordedAt)
        evcc.preferDynamicControlMode = true

        XCTAssertThrowsError(try evcc.run()) { error in
            let text = String(describing: error)
            XCTAssertTrue(text.contains("Dynamic"), text)
            // The error names what was missing, because that is what a live run is read from.
            XCTAssertTrue(text.contains("ControlMode"), text)
        }
    }
}
