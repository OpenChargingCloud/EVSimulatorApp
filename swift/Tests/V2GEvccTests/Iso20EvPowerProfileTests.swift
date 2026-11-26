import XCTest
import ExiIso20Common
@testable import V2GEvcc

/// The port of the C# `Iso20EvPowerProfileTests`: the anchor in SECC microseconds, the slots followed
/// and capped, zero outside the schedule. The session corpus pins the bytes; these pin the rules.
final class Iso20EvPowerProfileTests: XCTestCase {

    private let now: UInt64 = 1_767_225_600   // 2026-01-01T00:00:00Z

    private func kw(_ kw: Int16) -> RationalNumberType { RationalNumberType(exponent: 3, value: kw) }

    private func slot(_ seconds: UInt32, _ power: RationalNumberType,
                      _ l2: RationalNumberType? = nil, _ l3: RationalNumberType? = nil) -> PowerScheduleEntryType {
        PowerScheduleEntryType(duration: seconds, power: power, power_L2: l2, power_L3: l3)
    }

    private func offer(_ anchorSeconds: UInt64, _ slots: PowerScheduleEntryType...) -> ScheduleExchangeRes {
        ScheduleExchangeRes(
            header: MessageHeaderType(sessionID: [UInt8](repeating: 0, count: 8), timeStamp: now),
            responseCode: .OK, eVSEProcessing: .Finished, goToPause: false,
            scheduled_SEResControlMode: Scheduled_SEResControlModeType(scheduleTuple: [
                ScheduleTupleType(scheduleTupleID: 7, chargingSchedule: ChargingScheduleType(
                    powerSchedule: PowerScheduleType(timeAnchor: anchorSeconds * 1000,
                        powerScheduleEntries: PowerScheduleEntryListType(powerScheduleEntry: slots))))
            ]))
    }

    private func watts(_ value: RationalNumberType) -> Double { Iso20EvPowerProfile.watts(value) }

    func testTheAnchorIsTheStationsTimeStampInMicroseconds() {
        let profile = Iso20EvPowerProfile.build(offer(now - 60, slot(3600, kw(22))), seccTimeSeconds: now, dynamic: false)

        XCTAssertEqual(profile.timeAnchor, now * 1_000_000)
        XCTAssertEqual(profile.scheduled_EVPPTControlMode?.selectedScheduleTupleID, 7)
    }

    func testItFollowsTheSlotsFromNowCappedAtEach() {
        let entries = Iso20EvPowerProfile.build(offer(now - 600, slot(3600, kw(22)), slot(3600, kw(5))),
                                                seccTimeSeconds: now, dynamic: false).eVPowerProfileEntries.eVPowerProfileEntry

        XCTAssertEqual(entries.map(\.duration), [3000, 3600])
        XCTAssertEqual(watts(entries[0].power), 10_000)
        XCTAssertEqual(watts(entries[1].power), 5_000)
    }

    func testBeforeTheScheduleStartsItProjectsZero() {
        let entries = Iso20EvPowerProfile.build(offer(now + 30, slot(600, kw(22))),
                                                seccTimeSeconds: now, dynamic: false).eVPowerProfileEntries.eVPowerProfileEntry

        XCTAssertEqual(entries.map(\.duration), [30, 600])
        XCTAssertEqual(watts(entries[0].power), 0)
    }

    func testAScheduleThatHasEndedLeavesOneZeroEntry() {
        let entries = Iso20EvPowerProfile.build(offer(0, slot(3600, RationalNumberType(exponent: 0, value: 100))),
                                                seccTimeSeconds: now, dynamic: false).eVPowerProfileEntries.eVPowerProfileEntry

        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(watts(entries[0].power), 0)
    }

    func testAPerPhaseSlotCapsTheTotalAtThreeTimesItsWeakestPhase() {
        XCTAssertEqual(watts(Iso20EvPowerProfile.capped(slot(3600, kw(11), kw(1), kw(11)))), 3_000)
        XCTAssertEqual(watts(Iso20EvPowerProfile.capped(slot(3600, kw(11), kw(11), nil))), 0)
    }

    func testDynamicModeKeepsTheCarsOwnCurveAnchoredInSeccTime() {
        let profile = Iso20EvPowerProfile.build(offer(now, slot(60, kw(1))), seccTimeSeconds: now, dynamic: true)

        XCTAssertEqual(profile.timeAnchor, now * 1_000_000)
        XCTAssertNil(profile.scheduled_EVPPTControlMode)
        XCTAssertNotNil(profile.dynamic_EVPPTControlMode)
        XCTAssertEqual(profile.eVPowerProfileEntries.eVPowerProfileEntry.map { watts($0.power) }, [10_000])
    }
}
