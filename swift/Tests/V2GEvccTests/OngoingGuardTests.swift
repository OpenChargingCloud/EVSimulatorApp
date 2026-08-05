import XCTest

@testable import V2GEvcc

/// The deadline that ends a phase the station keeps answering with `Ongoing`.
///
/// Written because a live peer needed it: EVerest's `EvseV2G` answered 1170 authorization polls with
/// `OK`/`Ongoing` and this EVCC had nothing that would ever stop
/// (`../../docs/interop-runs/2026-08-02-everest-iso2-dc-notls/`). The trace corpus
/// cannot contain such a station, because our own SECC always finishes.
final class OngoingGuardTests: XCTestCase {

    func testAPhaseInsideItsLimitIsLeftAlone() throws {
        var now: UInt64 = 0
        let guard_ = OngoingGuard("Authorization", limitMillis: 60_000, nowMillis: { now })

        try guard_.tick()
        now = 59_000
        try guard_.tick()
    }


    func testAPhaseThatOutlivesItsLimitEndsTheSession() {
        var now: UInt64 = 0
        let guard_ = OngoingGuard("Authorization", limitMillis: 60_000, nowMillis: { now })

        now = 61_000

        XCTAssertThrowsError(try guard_.tick()) { error in
            let message = (error as? SessionAborted)?.description ?? "\(error)"
            // Both halves belong in the message: which phase, and how long it actually waited.
            XCTAssertTrue(message.contains("Authorization"), message)
            XCTAssertTrue(message.contains("61"), message)
            XCTAssertTrue(message.contains("Ongoing"), message)
        }
    }

}
