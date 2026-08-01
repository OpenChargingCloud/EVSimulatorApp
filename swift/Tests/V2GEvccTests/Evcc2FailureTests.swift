import XCTest

import ExiIso2

@testable import V2GEvcc

/// What this -2 EVCC does when the station answers with a `FAILED` code.
///
/// The -2 half of the gap eVDriveFlow exposed in the -20 EVCC on 2026-08-01
/// (`libs/Vanaheimr.V2G.Exi/docs/interop-runs/2026-08-01-edf-iso20-dc-notls/`, finding 3). Same hole,
/// invisible for the same reason: our own SECC never answers FAILED, so the trace corpus — this port's
/// whole oracle — contains no such response.
final class Evcc2FailureTests: XCTestCase {

    private func evcc() -> Evcc2 {
        // The transport is never touched: refuseOnFailure inspects a decoded body and nothing else.
        Evcc2(V2GTPStream(SilentTransport()), .ac)
    }


    /// The ordering the `rawValue >= FAILED` comparison rests on.
    ///
    /// -2 has two families, not three: four `OK*` values and then `FAILED` onwards, with no `WARNING`.
    func testResponseCodeFamiliesAreContiguousAndOrdered() {
        for code in ExiIso2.ResponseCode.allCases {
            let name = String(describing: code)
            if name.hasPrefix("FAILED") {
                XCTAssertGreaterThanOrEqual(code.rawValue, ExiIso2.ResponseCode.FAILED.rawValue, name)
            } else {
                XCTAssertLessThan(code.rawValue, ExiIso2.ResponseCode.FAILED.rawValue,
                                  "\(name) is not a failure but sorts at or above FAILED")
            }
        }
    }


    func testAFailedResponseEndsTheSession() {
        let body = SessionSetupResType(responseCode: .FAILED, eVSEID: "DE*ABC*E1")

        XCTAssertThrowsError(try evcc().refuseOnFailure(body)) { error in
            let message = (error as? SessionAborted)?.description ?? "\(error)"
            XCTAssertTrue(message.contains("SessionSetupResType"), message)
            XCTAssertTrue(message.contains("FAILED"), message)
        }
    }


    /// The reflective read has to find the code on every response type, not just the first one tried —
    /// a `Mirror` that missed it would be the fail-open shape this check exists to avoid.
    func testTheCodeIsFoundOnMoreThanOneResponseType() {
        XCTAssertThrowsError(try evcc().refuseOnFailure(
            PaymentServiceSelectionResType(responseCode: .FAILED_ServiceSelectionInvalid)))
    }


    func testAnOkResponseIsLetThrough() throws {
        try evcc().refuseOnFailure(SessionSetupResType(responseCode: .OK_NewSessionEstablished,
                                                       eVSEID: "DE*ABC*E1"))
    }

}
