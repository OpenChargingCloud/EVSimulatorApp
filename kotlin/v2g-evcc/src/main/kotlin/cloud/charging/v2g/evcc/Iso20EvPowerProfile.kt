package cloud.charging.v2g.evcc

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

import cloud.charging.v2g.iso20.common.Dynamic_EVPPTControlModeType
import cloud.charging.v2g.iso20.common.EVPowerProfileEntryListType
import cloud.charging.v2g.iso20.common.EVPowerProfileType
import cloud.charging.v2g.iso20.common.PowerScheduleEntryType
import cloud.charging.v2g.iso20.common.PowerScheduleType
import cloud.charging.v2g.iso20.common.PowerToleranceAcceptance
import cloud.charging.v2g.iso20.common.RationalNumberType
import cloud.charging.v2g.iso20.common.ScheduleExchangeRes
import cloud.charging.v2g.iso20.common.Scheduled_EVPPTControlModeType

/**
 * The `EVPowerProfile` an EVCC sends in `PowerDeliveryReq(Start)` — the port of the C#
 * `Iso20EvPowerProfile`, rule for rule, because the session corpus holds its bytes.
 *
 * Until 2026-11-27 the profile was a constant: `TimeAnchor` 0 and one 3600 s entry at 10 kW, active
 * an hour in 1970 and nowhere near the offered schedule. EVerest 2026.10.0 refused every Scheduled
 * `-20` session for it with `FAILED_EVPowerProfileInvalid`, and was right to:
 *
 *  - `[V2G20-1861]` — `TimeAnchor` is when the first entry becomes active, in **microseconds** of
 *    SECC time (Table 103; a `PowerSchedule`'s anchor, Table 112, is in **milliseconds**).
 *  - `[V2G20-1529]`, `[V2G20-1536]` — SECC time is the station's header `TimeStamp`.
 *  - `[V2G20-1559]` — no entry may exceed the selected schedule, and `[V2G20-1811]` makes the schedule
 *    zero before its first and after its last entry.
 *
 * So the anchor is the station's latest `TimeStamp`, in whole seconds, and the entries follow the first
 * tuple's charging schedule from then on: one per remaining slot at the lower of the car's target and
 * the slot, a zero entry before the schedule starts, nothing past its end — and a single zero entry if
 * it has already ended. Dynamic mode keeps one hour at the target power, anchored the same way. The car
 * only charges, so a discharging schedule (a lower bound) cannot be undercut and is not consulted.
 */
object Iso20EvPowerProfile {

    /** The car's target power when nothing caps it: 10 kW (10 × 10³ W). */
    val targetPower = RationalNumberType(3, 10)

    /** The horizon of a profile that has no schedule to follow. */
    const val UNBOUNDED_DURATION: UInt = 3600u

    private const val MICROSECONDS_PER_SECOND: ULong = 1_000_000u
    private const val MILLISECONDS_PER_SECOND: ULong = 1_000u

    private val zero = RationalNumberType(0, 0)

    /** The profile for `PowerDeliveryReq(Start)`; [seccTimeSeconds] is the station's latest `TimeStamp`. */
    fun build(scheduleRes: ScheduleExchangeRes, seccTimeSeconds: ULong, dynamic: Boolean): EVPowerProfileType {

        val tuple = if (dynamic) null else scheduleRes.scheduled_SEResControlMode?.scheduleTuple?.firstOrNull()

        return EVPowerProfileType(
            timeAnchor = seccTimeSeconds * MICROSECONDS_PER_SECOND,
            dynamic_EVPPTControlMode = if (dynamic) Dynamic_EVPPTControlModeType() else null,
            // PowerToleranceAcceptance is schema-optional but Josev's model requires it — its SECC
            // rejects an absent one, and a live run needed it set.
            scheduled_EVPPTControlMode =
                if (dynamic) null
                else Scheduled_EVPPTControlModeType(
                    tuple?.scheduleTupleID ?: 1u, PowerToleranceAcceptance.PowerToleranceConfirmed),
            eVPowerProfileEntries = EVPowerProfileEntryListType(
                if (tuple == null) listOf(PowerScheduleEntryType(UNBOUNDED_DURATION, targetPower, null, null))
                else follow(tuple.chargingSchedule.powerSchedule, seccTimeSeconds)))
    }

    /** The entries that follow [schedule] from [nowSeconds] on. */
    fun follow(schedule: PowerScheduleType, nowSeconds: ULong): List<PowerScheduleEntryType> {

        val entries   = mutableListOf<PowerScheduleEntryType>()
        var slotStart = schedule.timeAnchor / MILLISECONDS_PER_SECOND
        var cursor    = nowSeconds

        // [V2G20-1811]: before the schedule's first entry its power is zero.
        if (cursor < slotStart) {
            entries += PowerScheduleEntryType((slotStart - cursor).toUInt(), zero, null, null)
            cursor = slotStart
        }

        for (slot in schedule.powerScheduleEntries.powerScheduleEntry) {
            val slotEnd = slotStart + slot.duration
            if (slotEnd > cursor) {
                entries += PowerScheduleEntryType((slotEnd - cursor).toUInt(), capped(slot), null, null)
                cursor = slotEnd
            }
            slotStart = slotEnd
        }

        // [V2G20-1811] again: the schedule has ended, and after its last entry its power is zero.
        if (entries.isEmpty())
            entries += PowerScheduleEntryType(UNBOUNDED_DURATION, zero, null, null)

        return entries
    }

    /** The car's target power, capped at what [slot] allows; a slot with per-phase values also caps the
     *  total at three times its weakest phase, an absent phase counting as zero (`[V2G20-1817]`,
     *  `[V2G20-1819]`). */
    fun capped(slot: PowerScheduleEntryType): RationalNumberType {

        var limit = slot.power

        if (slot.power_L2 != null || slot.power_L3 != null) {
            val weakest = listOf(slot.power, slot.power_L2 ?: zero, slot.power_L3 ?: zero).minOf(::watts)
            if (3 * weakest < watts(limit))
                limit = fromWatts(3 * weakest)
        }

        return if (watts(targetPower) <= watts(limit)) targetPower else limit
    }

    fun watts(value: RationalNumberType): Double = value.value * 10.0.pow(value.exponent.toInt())

    /** The largest representable value not above [watts], so a cap only rounds towards compliance. */
    fun fromWatts(watts: Double): RationalNumberType {
        var exponent = 0
        while (abs(watts / 10.0.pow(exponent)) > Short.MAX_VALUE)
            exponent++
        return RationalNumberType(exponent.toByte(), floor(watts / 10.0.pow(exponent)).toInt().toShort())
    }
}
