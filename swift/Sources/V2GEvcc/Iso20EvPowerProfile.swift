import Foundation
import ExiIso20Common

/// The `EVPowerProfile` an EVCC sends in `PowerDeliveryReq(Start)` — the port of the C#
/// `Iso20EvPowerProfile`, rule for rule, because the session corpus holds its bytes.
///
/// Until 2026-11-27 the profile was a constant: `TimeAnchor` 0 and one 3600 s entry at 10 kW, active
/// an hour in 1970 and nowhere near the offered schedule. EVerest 2026.10.0 refused every Scheduled
/// `-20` session for it with `FAILED_EVPowerProfileInvalid`, and was right to:
///
/// - `[V2G20-1861]` — `TimeAnchor` is when the first entry becomes active, in **microseconds** of SECC
///   time (Table 103; a `PowerSchedule`'s anchor, Table 112, is in **milliseconds**).
/// - `[V2G20-1529]`, `[V2G20-1536]` — SECC time is the station's header `TimeStamp`.
/// - `[V2G20-1559]` — no entry may exceed the selected schedule, and `[V2G20-1811]` makes the schedule
///   zero before its first and after its last entry.
///
/// So the anchor is the station's latest `TimeStamp`, in whole seconds, and the entries follow the first
/// tuple's charging schedule from then on: one per remaining slot at the lower of the car's target and
/// the slot, a zero entry before the schedule starts, nothing past its end — and a single zero entry if
/// it has already ended. Dynamic mode keeps one hour at the target power, anchored the same way. The car
/// only charges, so a discharging schedule (a lower bound) cannot be undercut and is not consulted.
public enum Iso20EvPowerProfile {

    /// The car's target power when nothing caps it: 10 kW (10 × 10³ W).
    public static var targetPower: RationalNumberType { RationalNumberType(exponent: 3, value: 10) }

    /// The horizon of a profile that has no schedule to follow.
    public static let unboundedDuration: UInt32 = 3600

    private static let microsecondsPerSecond: UInt64 = 1_000_000
    private static let millisecondsPerSecond: UInt64 = 1_000

    private static var zero: RationalNumberType { RationalNumberType(exponent: 0, value: 0) }

    /// The profile for `PowerDeliveryReq(Start)`; `seccTimeSeconds` is the station's latest `TimeStamp`.
    public static func build(_ scheduleRes: ScheduleExchangeRes, seccTimeSeconds: UInt64,
                             dynamic: Bool) -> EVPowerProfileType {

        let tuple = dynamic ? nil : scheduleRes.scheduled_SEResControlMode?.scheduleTuple.first

        return EVPowerProfileType(
            timeAnchor: seccTimeSeconds * microsecondsPerSecond,
            dynamic_EVPPTControlMode: dynamic ? Dynamic_EVPPTControlModeType() : nil,
            // PowerToleranceAcceptance is schema-optional but Josev's model requires it — its SECC
            // rejects an absent one, and a live run needed it set.
            scheduled_EVPPTControlMode: dynamic ? nil : Scheduled_EVPPTControlModeType(
                selectedScheduleTupleID: tuple?.scheduleTupleID ?? 1,
                powerToleranceAcceptance: .PowerToleranceConfirmed),
            eVPowerProfileEntries: EVPowerProfileEntryListType(eVPowerProfileEntry:
                tuple.map { follow($0.chargingSchedule.powerSchedule, nowSeconds: seccTimeSeconds) }
                    ?? [PowerScheduleEntryType(duration: unboundedDuration, power: targetPower)]))
    }

    /// The entries that follow `schedule` from `nowSeconds` on.
    public static func follow(_ schedule: PowerScheduleType, nowSeconds: UInt64) -> [PowerScheduleEntryType] {

        var entries:   [PowerScheduleEntryType] = []
        var slotStart = schedule.timeAnchor / millisecondsPerSecond
        var cursor    = nowSeconds

        // [V2G20-1811]: before the schedule's first entry its power is zero.
        if cursor < slotStart {
            entries.append(PowerScheduleEntryType(duration: UInt32(slotStart - cursor), power: zero))
            cursor = slotStart
        }

        for slot in schedule.powerScheduleEntries.powerScheduleEntry {
            let slotEnd = slotStart + UInt64(slot.duration)
            if slotEnd > cursor {
                entries.append(PowerScheduleEntryType(duration: UInt32(slotEnd - cursor), power: capped(slot)))
                cursor = slotEnd
            }
            slotStart = slotEnd
        }

        // [V2G20-1811] again: the schedule has ended, and after its last entry its power is zero.
        if entries.isEmpty {
            entries.append(PowerScheduleEntryType(duration: unboundedDuration, power: zero))
        }

        return entries
    }

    /// The car's target power, capped at what `slot` allows; a slot with per-phase values also caps the
    /// total at three times its weakest phase, an absent phase counting as zero (`[V2G20-1817]`,
    /// `[V2G20-1819]`).
    public static func capped(_ slot: PowerScheduleEntryType) -> RationalNumberType {

        var limit = slot.power

        if slot.power_L2 != nil || slot.power_L3 != nil {
            let weakest = [slot.power, slot.power_L2 ?? zero, slot.power_L3 ?? zero].map(watts).min()!
            if 3 * weakest < watts(limit) {
                limit = fromWatts(3 * weakest)
            }
        }

        return watts(targetPower) <= watts(limit) ? targetPower : limit
    }

    public static func watts(_ value: RationalNumberType) -> Double {
        Double(value.value) * pow(10.0, Double(value.exponent))
    }

    /// The largest representable value not above `watts`, so a cap only rounds towards compliance.
    public static func fromWatts(_ watts: Double) -> RationalNumberType {
        var exponent = 0
        while abs(watts / pow(10.0, Double(exponent))) > Double(Int16.max) {
            exponent += 1
        }
        return RationalNumberType(exponent: Int8(exponent),
                                  value: Int16((watts / pow(10.0, Double(exponent))).rounded(.down)))
    }
}
