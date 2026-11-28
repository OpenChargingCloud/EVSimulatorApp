package cloud.charging.v2g.evcc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pack asked for more than it holds gives what it has and counts that, the mirror of a full pack that
 * counts only what it took.
 *
 * The C# car counted the whole ask until 2026-11-28, and so "delivered" -38.86 kWh from 30 kWh against
 * EVerest's exporting station (`../../docs/interop-runs/`, in ISO15118ConformanceTests). This car has no
 * discharging session yet, so nothing here reached that line, but it is the same model.
 */
class EvBatteryTest {

    @Test
    fun itGivesBackNoMoreThanItHolds() {
        val battery = EvBattery(1.0, 50.0)

        battery.add(-300.0)
        battery.add(-300.0)

        assertEquals(0.0,    battery.energyWh)
        assertEquals(-500.0, battery.deliveredWh, "it counted what it never held")

        battery.add(300.0)

        assertEquals(-200.0, battery.deliveredWh)
    }

}
