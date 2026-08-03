import Foundation

/// How much charging one charge-loop iteration stands for.
///
/// A property of the simulation's charge loop rather than of either meter, which is why it lives on
/// its own: the vehicle's ``EvMeter`` and the station's meter have to agree about it, or their two
/// readings are not measurements of one process and comparing them means nothing.
///
/// The number is arbitrary and therefore stated rather than buried. A simulator's loop runs three
/// iterations where a real session runs for an hour, so something has to declare what an iteration is
/// worth; one minute puts a 48 kW DC session at 800 Wh per sample, a figure a person can check in
/// their head.
///
/// It is deliberately not a clock reading: the session corpus is recorded with a pinned clock so
/// -20's per-message timestamps stay stable, so anything integrating over wall time would count zero
/// there and something different on every live run.
public enum ChargeLoopSample {

    /// What one charge-loop iteration stands for: one minute.
    public static let periodHours: Double = 1.0 / 60.0

    /// The energy one iteration at `watts` represents, in watt-hours.
    public static func wattHours(_ watts: Double) -> Double { watts * periodHours }

    /// What a meter register actually takes on for one iteration: whole watt-hours, signed.
    ///
    /// **Rounded per sample**, which is worse arithmetic and the right answer: the figure this is
    /// compared against lives in `MeterReading` / `ChargedEnergyReadingWh`, an `xs:unsignedLong`
    /// register that holds nothing finer. A model more precise than the register it is checked
    /// against reports differences it invented itself — measured in C# as an AC session coming out
    /// 549 Wh at the station and 548 in the vehicle.
    public static func registerWattHours(_ watts: Double) -> Double {
        // Foundation's `rounded()` is half-away-from-zero, which is what C# asks for by name and
        // Kotlin has to be talked into. Same rule in three languages, or the export case parts ways.
        wattHours(watts).rounded()
    }
}


/// The vehicle's own energy counter: what the EV thinks it took, kept independently of what the
/// station says it delivered.
///
/// ## Why the EV needs one
///
/// Without it every energy figure the app can show is the station's, and the EV's signed
/// `MeteringReceiptReq` is a countersignature on someone else's number — the vehicle attesting to a
/// reading it has no way to dispute. `docs/CONCEPT.md` §4.2 asks for exactly this, and §4.3 makes it
/// the first of three legs. It is also the only leg needing no cryptography at all, which is worth
/// noticing: a disagreement it finds is the kind no signature can protect against.
///
/// ## Sampled, not clock-driven
///
/// Each charge-loop iteration *is* a sample and carries a declared duration (``ChargeLoopSample``).
/// Deterministic, replayable, and the same number in all three back ends — which is what lets
/// `EvccTraceTests` hold this port to C#'s figure at all.
///
/// ## What it is not
///
/// Not a battery model, not an efficiency model, and not a measurement: no losses, no ramping, no
/// sensor noise. It counts what the EV declared at its inlet. Agreement with a station's signed
/// reading is two implementations of one arithmetic agreeing — good for catching a wrong field or a
/// wrong unit, and no evidence whatsoever about a real meter.
public final class EvMeter {

    /// What one sample stands for, in hours.
    public let samplePeriodHours: Double

    /// How many samples were taken — one per charge-loop iteration.
    public private(set) var samples = 0

    /// The running total, signed, so an exporting session can go back down.
    public private(set) var energy: Double = 0

    /// The energy counted so far, in watt-hours — the form the wire carries.
    public var energyWh: UInt64 { energy <= 0 ? 0 : UInt64(energy) }

    public init(samplePeriodHours: Double = ChargeLoopSample.periodHours) {
        precondition(samplePeriodHours > 0,
                     "a sample has to cover some time, or nothing is ever counted")
        self.samplePeriodHours = samplePeriodHours
    }

    /// Counts one charge-loop iteration at `watts`.
    ///
    /// Negative power subtracts: a bidirectional session exporting to the grid is energy leaving the
    /// battery, and a counter clamping at zero would report a V2H session as pure consumption.
    public func sample(_ watts: Double) {
        // Whole watt-hours per sample, matching `ChargeLoopSample.registerWattHours` for the default
        // period — see there for why the *less* precise rule is the right one.
        energy += (watts * samplePeriodHours).rounded()
        samples += 1
    }

    /// Counts one iteration at `volts` × `amperes`.
    public func sample(volts: Double, amperes: Double) { sample(volts * amperes) }
}
