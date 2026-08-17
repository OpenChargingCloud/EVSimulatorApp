import Foundation
import V2GMetering

/// Why a charge loop stopped, or ``running`` while it has not.
public enum ChargeStop {
    /// Not finished — keep looping.
    case running
    /// The battery reached 100 %.
    case full
    /// The requested state of charge was reached.
    case targetSoC
    /// The requested amount of energy has been delivered.
    case targetEnergy
    /// The charging-time limit ran out.
    case timeLimit
    /// The departure time arrived.
    case departure
    /// The iteration ceiling was hit — a guard, not a goal.
    case loopLimit
}

/// A battery filling up, and the goals that end a charging session.
///
/// A port of C#'s `Simulation.EvBattery`, held to it by the same corpus as the state machines: the
/// `iso2-dc-eim-battery` recording ends after two charge-loop cycles rather than three, and only
/// arithmetic identical to C#'s stops there — one watt-hour out and the third cycle happens.
///
/// It sits in the EVCC target rather than beside the meter because only a car has a pack, and these
/// ports are only ever the car (see the target README). It shares ``ChargeLoopSample`` with the
/// meter, which is the whole point: a battery filled here and a meter reading compared against a
/// station's are measuring the same process.
///
/// ## It runs on simulated time, and that is not a shortcut
///
/// One charge-loop iteration stands for ``ChargeLoopSample/periodHours`` — one minute. A 60 kWh pack
/// from 20 % at 11 kW is then some 260 iterations rather than four and a half hours.
///
/// ## It is charged from what was metered, not from what was asked for
///
/// The caller feeds it the energy the meter counted for that iteration, so in DC the station's
/// delivered current fills the pack and a station that gives less than requested is visible as a
/// slower charge. The one thing this cannot model is the station giving more than the car allowed.
///
/// ## What it is not
///
/// Linear: no constant-voltage taper above the usual 80 % knee, no temperature, no losses, no
/// ageing. A real pack's last fifth takes disproportionately long and this one does not, so a run
/// that ends "at 100 %" reports the arithmetic and not a charging curve. That matters for anything
/// claiming a duration and not at all for a conformance run, which is what this is for.
public final class EvBattery {

    /// A mainstream mid-size EV, and the default when only a state of charge or a goal is given.
    public static let defaultCapacityKWh: Double = 60.0

    /// A ceiling on iterations, so a goal that cannot be reached — a station delivering nothing, a
    /// target above capacity — ends the run instead of hanging on a live counterparty.
    public static let defaultMaxIterations = 5000

    public init(capacityKWh: Double, startSoCPercent: Double) {
        precondition(capacityKWh > 0, "a battery has to hold something")
        precondition((0...100).contains(startSoCPercent), "a state of charge is 0..100 %")

        capacityWh  = capacityKWh * 1000.0
        startSoC    = startSoCPercent
        energyWh    = capacityKWh * 1000.0 * startSoCPercent / 100.0
    }

    /// Usable capacity, in watt-hours.
    public let capacityWh: Double

    /// The state of charge the session started at, in percent.
    public let startSoC: Double

    /// What the pack holds now, in watt-hours.
    public private(set) var energyWh: Double

    /// What has gone in since the session started, in watt-hours.
    public private(set) var deliveredWh: Double = 0

    /// The state of charge now, in percent.
    public var soC: Double { 100.0 * energyWh / capacityWh }

    /// Iterations run so far.
    public private(set) var iterations = 0

    /// Simulated time spent charging, in hours.
    public var elapsedHours: Double { Double(iterations) * ChargeLoopSample.periodHours }


    /// Stop when the pack reaches this state of charge, in percent.
    public var targetSoC: Double?

    /// Stop when this much energy has been delivered, in watt-hours — `EAmount` in ISO 15118-2,
    /// `EVTargetEnergyRequest` in -20.
    public var targetEnergyWh: Double?

    /// Stop after this much simulated charging time, in hours.
    public var maxDurationHours: Double?

    /// Stop when the departure time arrives, in hours — the same value the wire carries as
    /// `DepartureTime`, seconds from the session's anchor.
    public var departureInHours: Double?

    /// The state of charge the driver needs to have when leaving, in percent.
    ///
    /// **Not a stop condition, and deliberately not one.** A floor cannot extend a session — you
    /// cannot charge after you have driven off — so this neither prolongs the loop past a departure
    /// time nor past a charging-time limit. What it does is turn "the session ended" into "the
    /// session ended and the car had enough / did not", which is the question a driver actually
    /// asked and the one a scheduling station should be judged on. ``describe(_:)`` says which.
    ///
    /// It is declared as well as expected — see ``minimumNeededWh``.
    public var minimumSoC: Double?

    /// Whether the minimum was asked for and missed. False when none was asked for.
    public var minimumSoCMissed: Bool { minimumSoC.map { soC < $0 } ?? false }


    // ── the same goals, as the energy a request carries ────────────────────
    // -20 asks for three figures rather than one, and they are exactly the three questions a
    // scheduling station has to answer: how much do you want, how much can you still take, how much
    // do you actually need. All three shrink as the pack fills, because they are what is *left*.

    /// How much more this session is asking for, in watt-hours: what it takes to reach the nearest
    /// goal that names a quantity — a target state of charge, a target amount delivered, or simply a
    /// full pack. Never negative, and zero once the goal is met.
    ///
    /// The time-based goals do not shorten this, and deliberately: what the car wants is not changed
    /// by having less time to get it. Reconciling the two is the station's job, which is what
    /// `DepartureTime` beside this figure is for.
    public var energyNeededWh: Double {
        var wanted = energyAcceptableWh   // a full pack, the goal behind every other one
        if let s = targetSoC      { wanted = min(wanted, max(0, capacityWh * s / 100.0 - energyWh)) }
        if let e = targetEnergyWh { wanted = min(wanted, max(0, e - deliveredWh)) }
        return wanted
    }

    /// How much more the pack can physically take, in watt-hours — the ceiling on any request,
    /// whatever the goals say, and the figure a station may not plan above.
    public var energyAcceptableWh: Double { max(0, capacityWh - energyWh) }

    /// How much more it takes to reach ``minimumSoC``, in watt-hours; zero when no minimum was asked
    /// for, or it is already met.
    ///
    /// This is where the minimum reaches the wire. It is not a stop condition and cannot be one (see
    /// ``minimumSoC``), but a station scheduling a Dynamic session is owed it, and -20 has two places
    /// to put it: `EVMinimumEnergyRequest` in every request that carries the energy triple, and
    /// `MinimumSOC` in the Dynamic ScheduleExchange request, which — unlike the charge-loop request
    /// — does carry it EV-side.
    public var minimumNeededWh: Double {
        minimumSoC.map { max(0, capacityWh * $0 / 100.0 - energyWh) } ?? 0
    }

    /// The power the car asks for, in watts. Zero means "whatever the station offers".
    public var requestedPowerW: Double = 0

    /// Where constant-current charging ends and the taper begins, in percent. 100 disables it.
    ///
    /// A lithium pack takes full current only to roughly four fifths, then the charger holds the
    /// voltage and the current falls away — which is why the last fifth takes as long as the first
    /// three. 80 % is the conventional knee.
    public var taperFromSoC: Double = 80.0

    /// The smallest fraction of the requested power the taper will still ask for.
    ///
    /// Not cosmetic: a taper that reaches zero at exactly 100 % never fills the pack, and with the
    /// meter's whole-watt-hour rounding the session would grind to a halt short of full and only end
    /// at the iteration ceiling. Real chargers stop at a termination current for the same reason, so
    /// the floor is the physical behaviour as much as it is the guard.
    public var taperFloor: Double = 0.05

    /// What fraction of ``requestedPowerW`` the car asks for at the current state of charge: 1 below
    /// the knee, falling linearly to ``taperFloor`` at 100 %.
    ///
    /// Linear, and that is a simplification worth naming — a real constant-voltage phase decays
    /// roughly exponentially. What this reproduces is the shape that matters for a charging session:
    /// full power to the knee, then progressively less, so time-to-100 % is no longer time-to-80 %
    /// scaled up.
    public var powerFactor: Double {
        if taperFromSoC >= 100 || soC <= taperFromSoC { return 1.0 }

        let through = (100.0 - soC) / (100.0 - taperFromSoC)   // 1 at the knee, 0 at full
        return min(max(through, taperFloor), 1.0)
    }

    /// The iteration ceiling; see ``defaultMaxIterations``.
    public var maxIterations = EvBattery.defaultMaxIterations


    /// Counts one iteration and the energy it delivered. Charge is clamped at capacity — a pack does
    /// not take more than it holds, and a station that keeps pushing is simply not counted.
    public func add(_ wattHours: Double) {
        iterations += 1

        if wattHours > 0 {
            let accepted = min(wattHours, capacityWh - energyWh)
            energyWh    += accepted
            deliveredWh += accepted
        } else {
            // Negative is a bidirectional session exporting: the pack goes down, and down to empty.
            let released = min(-wattHours, energyWh)
            energyWh    -= released
            deliveredWh += wattHours
        }
    }

    /// Whether a goal has been met, and which. Evaluated in the order a driver would care about: a
    /// full battery ends the session whatever else was asked for.
    public var stop: ChargeStop {
        // 0.5 Wh rather than exact equality: the meter counts whole watt-hours, so a pack filled by
        // it lands next to capacity and never precisely on it.
        if energyWh >= capacityWh - 0.5                              { return .full }
        if let s = targetSoC,        soC >= s                        { return .targetSoC }
        if let e = targetEnergyWh,   deliveredWh >= e                { return .targetEnergy }
        if let d = maxDurationHours, elapsedHours >= d               { return .timeLimit }
        if let t = departureInHours, elapsedHours >= t               { return .departure }
        if iterations >= maxIterations                               { return .loopLimit }
        return .running
    }

    /// One line for the console: where the pack ended up and why it stopped.
    ///
    /// A POSIX locale throughout, not the device's: the C# original formats invariantly, and a phone
    /// set to German would otherwise print "22,7 %" where the reference prints "22.7 %".
    public func describe(_ stop: ChargeStop) -> String {
        func f(_ format: String, _ arguments: CVarArg...) -> String {
            String(format: format, locale: Locale(identifier: "en_US_POSIX"), arguments: arguments)
        }

        let why: String
        switch stop {
        case .full:         why = "full."
        case .targetSoC:    why = f("target %.0f %% reached.", targetSoC ?? 0)
        case .targetEnergy: why = f("target %.1f kWh delivered.", (targetEnergyWh ?? 0) / 1000)
        case .timeLimit:    why = "charging-time limit reached."
        case .departure:    why = "departure time reached."
        case .loopLimit:    why = "stopped at the \(maxIterations)-iteration ceiling — the goal was not reachable."
        case .running:      why = "still running."
        }

        var minimum = ""
        if let m = minimumSoC {
            minimum = minimumSoCMissed
                ? f(" NOT ENOUGH: %.0f %% was asked for and the car leaves at %.1f %%.", m, soC)
                : f(" The %.0f %% minimum was met.", m)
        }

        // The iteration count is interpolated rather than formatted: `%d` against a 64-bit `Int` is
        // the classic String(format:) trap, and it buys nothing here.
        return f("Battery: %.1f %% of %.1f kWh (started at %.1f %%, %.2f kWh delivered) after %.0f min ",
                 soC, capacityWh / 1000, startSoC, deliveredWh / 1000, elapsedHours * 60)
             + "simulated in \(iterations) iteration(s) — " + why + minimum
    }
}
