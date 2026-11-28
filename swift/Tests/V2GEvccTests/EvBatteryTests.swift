import XCTest

@testable import V2GEvcc

/// A pack asked for more than it holds gives what it has and counts that, the mirror of a full pack that
/// counts only what it took.
///
/// The C# car counted the whole ask until 2026-11-28, and so "delivered" -38.86 kWh from 30 kWh against
/// EVerest's exporting station (`../../docs/interop-runs/`, in ISO15118ConformanceTests). This car has no
/// discharging session yet, so nothing here reached that line, but it is the same model.
final class EvBatteryTests: XCTestCase {

    func testItGivesBackNoMoreThanItHolds() {
        let battery = EvBattery(capacityKWh: 1.0, startSoCPercent: 50)

        battery.add(-300)
        battery.add(-300)

        XCTAssertEqual(battery.energyWh,    0)
        XCTAssertEqual(battery.deliveredWh, -500, "it counted what it never held")

        battery.add(300)

        XCTAssertEqual(battery.deliveredWh, -200)
    }

}
