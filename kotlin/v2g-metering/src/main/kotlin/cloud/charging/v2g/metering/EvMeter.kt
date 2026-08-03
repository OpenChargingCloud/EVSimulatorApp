package cloud.charging.v2g.metering

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How much charging one charge-loop iteration stands for.
 *
 * A property of the simulation's charge loop rather than of either meter, which is why it lives on
 * its own: the vehicle's [EvMeter] and the station's meter have to agree about it, or their two
 * readings are not measurements of one process and comparing them means nothing.
 *
 * The number is arbitrary and therefore stated rather than buried. A simulator's loop runs three
 * iterations where a real session runs for an hour, so something has to declare what an iteration is
 * worth; one minute puts a 48 kW DC session at 800 Wh per sample, a figure a person can check in
 * their head.
 *
 * It is deliberately not a clock reading: the session corpus is recorded with a pinned clock so
 * -20's per-message timestamps stay stable, so anything integrating over wall time would count zero
 * there and something different on every live run.
 */
object ChargeLoopSample {

    /** What one charge-loop iteration stands for: one minute. */
    const val PERIOD_HOURS: Double = 1.0 / 60.0

    /** The energy one iteration at [watts] represents, in watt-hours. */
    fun wattHours(watts: Double): Double = watts * PERIOD_HOURS

    /**
     * What a meter register actually takes on for one iteration: whole watt-hours, signed.
     *
     * **Rounded per sample**, which is worse arithmetic and the right answer: the figure this gets
     * compared against lives in `MeterReading` / `ChargedEnergyReadingWh`, an `xs:unsignedLong`
     * register that holds nothing finer. A model more precise than the register it is checked
     * against reports differences it invented itself — measured in C# as an AC session coming out
     * 549 Wh at the station and 548 in the vehicle.
     */
    fun registerWattHours(watts: Double): Double = roundHalfAwayFromZero(wattHours(watts))

    /** Kotlin's `roundToLong` rounds half **up**, which is a different rule for negatives — and C#
     *  uses away-from-zero, so the two would part company on exactly the export case. */
    fun roundHalfAwayFromZero(value: Double): Double =
        if (value < 0) -(abs(value).roundToLong().toDouble()) else value.roundToLong().toDouble()
}


/**
 * The vehicle's own energy counter: what the EV thinks it took, kept independently of what the
 * station says it delivered.
 *
 * ## Why the EV needs one
 *
 * Without it every energy figure the app can show is the station's, and the EV's signed
 * `MeteringReceiptReq` is a countersignature on someone else's number — the vehicle attesting to a
 * reading it has no way to dispute. `docs/CONCEPT.md` §4.2 asks for exactly this, and §4.3 makes it
 * the first of three legs. It is also the only leg needing no cryptography at all, which is worth
 * noticing: a disagreement it finds is the kind no signature can protect against.
 *
 * ## Sampled, not clock-driven
 *
 * Each charge-loop iteration *is* a sample and carries a declared duration ([ChargeLoopSample]).
 * Deterministic, replayable, and the same number in all three back ends — which is what makes
 * `Evcc2TraceTest` able to hold this port to C#'s figure at all.
 *
 * ## What it is not
 *
 * Not a battery model, not an efficiency model, and not a measurement: no losses, no ramping, no
 * sensor noise. It counts what the EV declared at its inlet. Agreement with a station's signed
 * reading is two implementations of one arithmetic agreeing — good for catching a wrong field or a
 * wrong unit, and no evidence whatsoever about a real meter.
 */
class EvMeter(val samplePeriodHours: Double = ChargeLoopSample.PERIOD_HOURS) {

    init {
        require(samplePeriodHours > 0) { "a sample has to cover some time, or nothing is ever counted" }
    }

    /** How many samples were taken — one per charge-loop iteration. */
    var samples: Int = 0
        private set

    /** The running total, signed, so an exporting session can go back down. */
    var energy: Double = 0.0
        private set

    /** The energy counted so far, in watt-hours — the form the wire carries. */
    val energyWh: ULong get() = if (energy <= 0) 0uL else energy.toULong()

    /**
     * Counts one charge-loop iteration at [watts].
     *
     * Negative power subtracts: a bidirectional session exporting to the grid is energy leaving the
     * battery, and a counter clamping at zero would report a V2H session as pure consumption.
     */
    fun sample(watts: Double) {
        // Whole watt-hours per sample, matching ChargeLoopSample.registerWattHours for the default
        // period — see there for why the *less* precise rule is the right one.
        energy += ChargeLoopSample.roundHalfAwayFromZero(watts * samplePeriodHours)
        samples++
    }

    /** Counts one iteration at [volts] × [amperes]. */
    fun sample(volts: Double, amperes: Double) = sample(volts * amperes)
}
