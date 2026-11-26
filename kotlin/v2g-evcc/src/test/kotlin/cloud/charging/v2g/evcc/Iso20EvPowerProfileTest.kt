package cloud.charging.v2g.evcc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

import cloud.charging.v2g.iso20.common.ChargingScheduleType
import cloud.charging.v2g.iso20.common.MessageHeaderType
import cloud.charging.v2g.iso20.common.PowerScheduleEntryListType
import cloud.charging.v2g.iso20.common.PowerScheduleEntryType
import cloud.charging.v2g.iso20.common.PowerScheduleType
import cloud.charging.v2g.iso20.common.Processing
import cloud.charging.v2g.iso20.common.RationalNumberType
import cloud.charging.v2g.iso20.common.ResponseCode
import cloud.charging.v2g.iso20.common.ScheduleExchangeRes
import cloud.charging.v2g.iso20.common.ScheduleTupleType
import cloud.charging.v2g.iso20.common.Scheduled_SEResControlModeType

/**
 * The port of the C# `Iso20EvPowerProfileTests`: the anchor in SECC microseconds, the slots followed
 * and capped, zero outside the schedule. The session corpus pins the bytes; these pin the rules, so a
 * divergence shows up as a rule and not only as a differing frame.
 */
class Iso20EvPowerProfileTest {

    private val now: ULong = 1_767_225_600u   // 2026-01-01T00:00:00Z

    private fun kw(kw: Int) = RationalNumberType(3, kw.toShort())

    private fun slot(seconds: UInt, power: RationalNumberType,
                     l2: RationalNumberType? = null, l3: RationalNumberType? = null) =
        PowerScheduleEntryType(seconds, power, l2, l3)

    private fun offer(anchorSeconds: ULong, vararg slots: PowerScheduleEntryType) =
        ScheduleExchangeRes(
            MessageHeaderType(ByteArray(8), now, null), ResponseCode.OK, Processing.Finished, false,
            null,
            Scheduled_SEResControlModeType(listOf(
                ScheduleTupleType(7u,
                    ChargingScheduleType(
                        PowerScheduleType(anchorSeconds * 1000u, null, null, PowerScheduleEntryListType(slots.toList())),
                        null, null),
                    null))))

    @Test
    fun `the anchor is the station's TimeStamp in microseconds`() {
        val profile = Iso20EvPowerProfile.build(offer(now - 60u, slot(3600u, kw(22))), now, dynamic = false)

        assertEquals(now * 1_000_000u, profile.timeAnchor)
        assertEquals(7u, profile.scheduled_EVPPTControlMode!!.selectedScheduleTupleID)
    }

    @Test
    fun `it follows the slots from now, capped at each`() {
        val entries = Iso20EvPowerProfile.build(offer(now - 600u, slot(3600u, kw(22)), slot(3600u, kw(5))),
                                                now, dynamic = false).eVPowerProfileEntries.eVPowerProfileEntry

        assertEquals(listOf(3000u, 3600u), entries.map { it.duration })
        assertEquals(Iso20EvPowerProfile.targetPower, entries[0].power)
        assertEquals(kw(5), entries[1].power)
    }

    @Test
    fun `before the schedule starts it projects zero`() {
        val entries = Iso20EvPowerProfile.build(offer(now + 30u, slot(600u, kw(22))), now, dynamic = false)
            .eVPowerProfileEntries.eVPowerProfileEntry

        assertEquals(listOf(30u, 600u), entries.map { it.duration })
        assertEquals(0.0, Iso20EvPowerProfile.watts(entries[0].power))
    }

    @Test
    fun `a schedule that has ended leaves one zero entry`() {
        val entries = Iso20EvPowerProfile.build(offer(0u, slot(3600u, RationalNumberType(0, 100))), now, dynamic = false)
            .eVPowerProfileEntries.eVPowerProfileEntry

        assertEquals(1, entries.size)
        assertEquals(0.0, Iso20EvPowerProfile.watts(entries[0].power))
    }

    @Test
    fun `a per-phase slot caps the total at three times its weakest phase`() {
        assertEquals(3_000.0, Iso20EvPowerProfile.watts(Iso20EvPowerProfile.capped(slot(3600u, kw(11), kw(1), kw(11)))))
        assertEquals(0.0,     Iso20EvPowerProfile.watts(Iso20EvPowerProfile.capped(slot(3600u, kw(11), kw(11), null))))
    }

    @Test
    fun `dynamic mode keeps the car's own curve, anchored in SECC time`() {
        val profile = Iso20EvPowerProfile.build(offer(now, slot(60u, kw(1))), now, dynamic = true)

        assertEquals(now * 1_000_000u, profile.timeAnchor)
        assertNull(profile.scheduled_EVPPTControlMode)
        assertNotNull(profile.dynamic_EVPPTControlMode)
        assertEquals(Iso20EvPowerProfile.targetPower, profile.eVPowerProfileEntries.eVPowerProfileEntry.single().power)
    }
}
