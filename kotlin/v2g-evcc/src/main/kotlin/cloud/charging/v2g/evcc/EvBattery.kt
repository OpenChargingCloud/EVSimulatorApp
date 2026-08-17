package cloud.charging.v2g.evcc

import cloud.charging.v2g.metering.ChargeLoopSample
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Why a charge loop stopped, or [Running] while it has not. */
enum class ChargeStop {
    /** Not finished — keep looping. */
    Running,
    /** The battery reached 100 %. */
    Full,
    /** The requested state of charge was reached. */
    TargetSoC,
    /** The requested amount of energy has been delivered. */
    TargetEnergy,
    /** The charging-time limit ran out. */
    TimeLimit,
    /** The departure time arrived. */
    Departure,
    /** The iteration ceiling was hit — a guard, not a goal. */
    LoopLimit,
}

/**
 * A battery filling up, and the goals that end a charging session.
 *
 * A port of C#'s `Simulation.EvBattery`, held to it by the same corpus as the state machines: the
 * `iso2-dc-eim-battery` recording ends after two charge-loop cycles rather than three, and only
 * arithmetic identical to C#'s stops there — one watt-hour out and the third cycle happens.
 *
 * It sits in the EVCC module rather than beside the meter because only a car has a pack, and these
 * ports are only ever the car (see the module README). It shares [ChargeLoopSample] with the meter,
 * which is the whole point: a battery filled here and a meter reading compared against a station's
 * are measuring the same process.
 *
 * ## It runs on simulated time, and that is not a shortcut
 *
 * One charge-loop iteration stands for [ChargeLoopSample.PERIOD_HOURS] — one minute. A 60 kWh pack
 * from 20 % at 11 kW is then some 260 iterations rather than four and a half hours.
 *
 * ## It is charged from what was metered, not from what was asked for
 *
 * The caller feeds it the energy the meter counted for that iteration, so in DC the station's
 * delivered current fills the pack and a station that gives less than requested is visible as a
 * slower charge. The one thing this cannot model is the station giving more than the car allowed.
 *
 * ## What it is not
 *
 * Linear: no constant-voltage taper above the usual 80 % knee, no temperature, no losses, no
 * ageing. A real pack's last fifth takes disproportionately long and this one does not, so a run
 * that ends "at 100 %" reports the arithmetic and not a charging curve. That matters for anything
 * claiming a duration and not at all for a conformance run, which is what this is for.
 */
class EvBattery(capacityKWh: Double, startSoCPercent: Double) {

    companion object {

        /** A mainstream mid-size EV, and the default when only a state of charge or a goal is given. */
        const val DEFAULT_CAPACITY_KWH: Double = 60.0

        /**
         * A ceiling on iterations, so a goal that cannot be reached — a station delivering nothing, a
         * target above capacity — ends the run instead of hanging on a live counterparty.
         */
        const val DEFAULT_MAX_ITERATIONS: Int = 5000
    }

    init {
        require(capacityKWh > 0) { "a battery has to hold something" }
        require(startSoCPercent in 0.0..100.0) { "a state of charge is 0..100 %" }
    }

    /** Usable capacity, in watt-hours. */
    val capacityWh: Double = capacityKWh * 1000.0

    /** The state of charge the session started at, in percent. */
    val startSoC: Double = startSoCPercent

    /** What the pack holds now, in watt-hours. */
    var energyWh: Double = capacityWh * startSoCPercent / 100.0
        private set

    /** What has gone in since the session started, in watt-hours. */
    var deliveredWh: Double = 0.0
        private set

    /** The state of charge now, in percent. */
    val soC: Double get() = 100.0 * energyWh / capacityWh

    /** Iterations run so far. */
    var iterations: Int = 0
        private set

    /** Simulated time spent charging. */
    val elapsed: Duration get() = (iterations * ChargeLoopSample.PERIOD_HOURS).hours


    /** Stop when the pack reaches this state of charge, in percent. */
    var targetSoC: Double? = null

    /** Stop when this much energy has been delivered, in watt-hours — `EAmount` in ISO 15118-2,
     *  `EVTargetEnergyRequest` in -20. */
    var targetEnergyWh: Double? = null

    /** Stop after this much simulated charging time. */
    var maxDuration: Duration? = null

    /** Stop when the departure time arrives — the same value the wire carries as `DepartureTime`,
     *  seconds from the session's anchor. */
    var departureIn: Duration? = null

    /**
     * The state of charge the driver needs to have when leaving, in percent.
     *
     * **Not a stop condition, and deliberately not one.** A floor cannot extend a session — you
     * cannot charge after you have driven off — so this neither prolongs the loop past a departure
     * time nor past a charging-time limit. What it does is turn "the session ended" into "the
     * session ended and the car had enough / did not", which is the question a driver actually
     * asked and the one a scheduling station should be judged on. [describe] says which.
     *
     * It is declared as well as expected — see [minimumNeededWh].
     */
    var minimumSoC: Double? = null

    /** Whether the minimum was asked for and missed. False when none was asked for. */
    val minimumSoCMissed: Boolean get() = minimumSoC?.let { soC < it } ?: false


    // ── the same goals, as the energy a request carries ────────────────────
    // -20 asks for three figures rather than one, and they are exactly the three questions a
    // scheduling station has to answer: how much do you want, how much can you still take, how much
    // do you actually need. All three shrink as the pack fills, because they are what is *left*.

    /**
     * How much more this session is asking for, in watt-hours: what it takes to reach the nearest
     * goal that names a quantity — a target state of charge, a target amount delivered, or simply a
     * full pack. Never negative, and zero once the goal is met.
     *
     * The time-based goals do not shorten this, and deliberately: what the car wants is not changed
     * by having less time to get it. Reconciling the two is the station's job, which is what
     * `DepartureTime` beside this figure is for.
     */
    val energyNeededWh: Double
        get() {
            var wanted = energyAcceptableWh   // a full pack, the goal behind every other one
            targetSoC?.let      { wanted = min(wanted, max(0.0, capacityWh * it / 100.0 - energyWh)) }
            targetEnergyWh?.let { wanted = min(wanted, max(0.0, it - deliveredWh)) }
            return wanted
        }

    /** How much more the pack can physically take, in watt-hours — the ceiling on any request,
     *  whatever the goals say, and the figure a station may not plan above. */
    val energyAcceptableWh: Double get() = max(0.0, capacityWh - energyWh)

    /**
     * How much more it takes to reach [minimumSoC], in watt-hours; zero when no minimum was asked
     * for, or it is already met.
     *
     * This is where the minimum reaches the wire. It is not a stop condition and cannot be one (see
     * [minimumSoC]), but a station scheduling a Dynamic session is owed it, and -20 has two places
     * to put it: `EVMinimumEnergyRequest` in every request that carries the energy triple, and
     * `MinimumSOC` in the Dynamic ScheduleExchange request, which — unlike the charge-loop request
     * — does carry it EV-side.
     */
    val minimumNeededWh: Double
        get() = minimumSoC?.let { max(0.0, capacityWh * it / 100.0 - energyWh) } ?: 0.0

    /** The power the car asks for, in watts. Zero means "whatever the station offers". */
    var requestedPowerW: Double = 0.0

    /**
     * Where constant-current charging ends and the taper begins, in percent. 100 disables it.
     *
     * A lithium pack takes full current only to roughly four fifths, then the charger holds the
     * voltage and the current falls away — which is why the last fifth takes as long as the first
     * three. 80 % is the conventional knee.
     */
    var taperFromSoC: Double = 80.0

    /**
     * The smallest fraction of the requested power the taper will still ask for.
     *
     * Not cosmetic: a taper that reaches zero at exactly 100 % never fills the pack, and with the
     * meter's whole-watt-hour rounding the session would grind to a halt short of full and only end
     * at the iteration ceiling. Real chargers stop at a termination current for the same reason, so
     * the floor is the physical behaviour as much as it is the guard.
     */
    var taperFloor: Double = 0.05

    /**
     * What fraction of [requestedPowerW] the car asks for at the current state of charge: 1 below
     * the knee, falling linearly to [taperFloor] at 100 %.
     *
     * Linear, and that is a simplification worth naming — a real constant-voltage phase decays
     * roughly exponentially. What this reproduces is the shape that matters for a charging session:
     * full power to the knee, then progressively less, so time-to-100 % is no longer time-to-80 %
     * scaled up.
     */
    val powerFactor: Double
        get() {
            if (taperFromSoC >= 100 || soC <= taperFromSoC) return 1.0

            val through = (100.0 - soC) / (100.0 - taperFromSoC)   // 1 at the knee, 0 at full
            return through.coerceIn(taperFloor, 1.0)
        }

    /** The iteration ceiling; see [DEFAULT_MAX_ITERATIONS]. */
    var maxIterations: Int = DEFAULT_MAX_ITERATIONS


    /**
     * Counts one iteration and the energy it delivered. Charge is clamped at capacity — a pack does
     * not take more than it holds, and a station that keeps pushing is simply not counted.
     */
    fun add(wattHours: Double) {
        iterations++

        if (wattHours > 0) {
            val accepted = min(wattHours, capacityWh - energyWh)
            energyWh    += accepted
            deliveredWh += accepted
        } else {
            // Negative is a bidirectional session exporting: the pack goes down, and down to empty.
            val released = min(-wattHours, energyWh)
            energyWh    -= released
            deliveredWh += wattHours
        }
    }

    /**
     * Whether a goal has been met, and which. Evaluated in the order a driver would care about: a
     * full battery ends the session whatever else was asked for.
     */
    val stop: ChargeStop
        get() {
            // 0.5 Wh rather than exact equality: the meter counts whole watt-hours, so a pack filled
            // by it lands next to capacity and never precisely on it.
            if (energyWh >= capacityWh - 0.5)                            return ChargeStop.Full
            targetSoC?.let      { if (soC >= it)         return ChargeStop.TargetSoC }
            targetEnergyWh?.let { if (deliveredWh >= it) return ChargeStop.TargetEnergy }
            maxDuration?.let    { if (elapsed >= it)     return ChargeStop.TimeLimit }
            departureIn?.let    { if (elapsed >= it)     return ChargeStop.Departure }
            if (iterations >= maxIterations)                             return ChargeStop.LoopLimit
            return ChargeStop.Running
        }

    /**
     * One line for the console: where the pack ended up and why it stopped.
     *
     * [Locale.ROOT] throughout, not the platform default: the C# original formats invariantly, and a
     * phone set to German would otherwise print "22,7 %" where the reference prints "22.7 %".
     */
    fun describe(stop: ChargeStop): String {
        val why = when (stop) {
            ChargeStop.Full         -> "full."
            ChargeStop.TargetSoC    -> String.format(Locale.ROOT, "target %.0f %% reached.", targetSoC)
            ChargeStop.TargetEnergy -> String.format(Locale.ROOT, "target %.1f kWh delivered.", (targetEnergyWh ?: 0.0) / 1000)
            ChargeStop.TimeLimit    -> "charging-time limit reached."
            ChargeStop.Departure    -> "departure time reached."
            ChargeStop.LoopLimit    -> "stopped at the $maxIterations-iteration ceiling — the goal was not reachable."
            else                    -> "still running."
        }
        val minimum = minimumSoC?.let {
            if (minimumSoCMissed)
                String.format(Locale.ROOT, " NOT ENOUGH: %.0f %% was asked for and the car leaves at %.1f %%.", it, soC)
            else
                String.format(Locale.ROOT, " The %.0f %% minimum was met.", it)
        } ?: ""

        return String.format(Locale.ROOT,
            "Battery: %.1f %% of %.1f kWh (started at %.1f %%, %.2f kWh delivered) " +
            "after %.0f min simulated in %d iteration(s) — ",
            soC, capacityWh / 1000, startSoC, deliveredWh / 1000,
            elapsed.inWholeSeconds / 60.0, iterations) + why + minimum
    }
}
