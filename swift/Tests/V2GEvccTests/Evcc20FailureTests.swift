import XCTest

import ExiIso20Common
import ExiIso20DC

@testable import V2GEvcc

/// What this EVCC does when the station answers with a `FAILED` code.
///
/// **Found live, not by reasoning, and not by this suite.** Until 2026-08-01 no -20 EVCC in this
/// repository read a response code at all, in any of the three languages. eVDriveFlow answered
/// `DC_CableCheckRes` with `FAILED` and the car went on charging
/// (`libs/Vanaheimr.V2G.Exi/docs/interop-runs/2026-08-01-edf-iso20-dc-notls/`).
///
/// The trace corpus — this port's entire oracle — could not have shown it: every recorded response is
/// one our own SECC produced, and our own SECC never says FAILED. The corpus is silent here by
/// construction, and this file is the station it does not contain.


final class Evcc20FailureTests: XCTestCase {

    private func evcc() -> Evcc20Dc {
        // The transport is never touched: refuseOnFailure inspects a decoded message and nothing else.
        Evcc20Dc(V2GTPStream(SilentTransport()), clock: { 1_767_225_600 })
    }

    private func cableCheck(_ code: ExiIso20DC.ResponseCode) -> DC_CableCheckRes {
        DC_CableCheckRes(header: ExiIso20DC.MessageHeaderType(sessionID: [UInt8](repeating: 0, count: 8),
                                                              timeStamp: 1_767_225_600,
                                                              signature: nil),
                         responseCode: code,
                         eVSEProcessing: .Finished)
    }


    /// The ordering the `rawValue >= FAILED` comparison rests on.
    ///
    /// The check is a range test, sound only while the schema keeps its three families contiguous and
    /// in order. A regenerated enum that interleaved them would quietly turn failures into successes —
    /// the very shape of bug this file exists because of.
    func testResponseCodeFamiliesAreContiguousAndOrdered() {
        for code in ExiIso20Common.ResponseCode.allCases {
            let name = String(describing: code)
            if name.hasPrefix("FAILED") {
                XCTAssertGreaterThanOrEqual(code.rawValue, ExiIso20Common.ResponseCode.FAILED.rawValue,
                                            "\(name) sorts below FAILED")
            } else {
                XCTAssertLessThan(code.rawValue, ExiIso20Common.ResponseCode.FAILED.rawValue,
                                  "\(name) is not a failure but sorts at or above FAILED")
            }
        }

        // The DC message set carries its own copy, generated separately from the same schema.
        XCTAssertEqual(ExiIso20DC.ResponseCode.FAILED.rawValue,
                       ExiIso20Common.ResponseCode.FAILED.rawValue)
    }


    func testAFailedResponseEndsTheSession() {
        XCTAssertThrowsError(try evcc().refuseOnFailure(cableCheck(.FAILED))) { error in
            let message = (error as? SessionAborted)?.description ?? "\(error)"
            // Both halves have to be in the message: which message failed, and with what.
            XCTAssertTrue(message.contains("DC_CableCheckRes"), message)
            XCTAssertTrue(message.contains("FAILED"), message)
        }
    }


    /// A WARNING is not a failure. The specification has three families because `WARNING*` means
    /// "something is off and the session continues"; aborting on it would turn an expiring certificate
    /// into a refused charge.
    func testAWarningDoesNotEndTheSession() throws {
        try evcc().refuseOnFailure(cableCheck(.WARNING_CertificateExpired))
    }

}
