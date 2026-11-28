import Foundation

/// How far the station's reading may stray from the vehicle's own count before the vehicle ends the
/// charging over it: a `fraction` of the station's figure, but never less than `floorWh`.
///
/// The port of the C# `Metering.MeteringDeviationLimit`. A stand-in for a comparator with a loss model and
/// an uncertainty band, and it says so: a fixed fraction knows nothing of cable losses, so it has to leave
/// them room. The floor is there because a fraction of a small number is smaller than one iteration's
/// rounding.
public struct MeteringDeviationLimit: Equatable, Sendable {

    public let fraction: Double
    public let floorWh: UInt64

    public init(fraction: Double, floorWh: UInt64) {
        self.fraction = fraction
        self.floorWh  = floorWh
    }

    /// The difference a reading of `stationNetWh` may show.
    public func limitWh(_ stationNetWh: Double) -> Double {
        Swift.max(Double(floorWh), fraction * abs(stationNetWh))
    }

    /// Whether the two figures are further apart than this limit allows.
    public func exceededBy(stationNetWh: Double, vehicleWh: Double) -> Bool {
        abs(stationNetWh - vehicleWh) > limitWh(stationNetWh)
    }
}

/// Why a vehicle ended the charging over the station's meter: the station's net figure, the vehicle's own
/// count at the same iteration, and the limit their difference crossed.
///
/// ISO 15118-20 lets the vehicle give its reason in `SessionStopReq`: `EVTerminationCode`, a URN of at most
/// 80 characters, and `EVTerminationExplanation`, at most 160. No code is defined yet, so `terminationCode`
/// is ours. The explanation is the **same text in C#, Kotlin and Swift**, because a recorded session holds
/// the vehicle's bytes and every port replays them; hence whole watt-hours only, rounded half away from
/// zero by hand.
public struct MeteringDeviation: Equatable, Sendable {

    /// The `EVTerminationCode` a vehicle sends when it ends the charging over the meter.
    public static let terminationCode = "urn:openchargingcloud:EVTerminationCode:MeteringDeviation"

    public let stationNetWh: Double
    public let vehicleWh: Double
    public let limitWh: Double

    public init(stationNetWh: Double, vehicleWh: Double, limitWh: Double) {
        self.stationNetWh = stationNetWh
        self.vehicleWh    = vehicleWh
        self.limitWh      = limitWh
    }

    /// The `EVTerminationExplanation`: the four figures the decision rests on.
    public var explanation: String { Self.explain(stationNetWh, vehicleWh, limitWh) }

    /// The station's figure against the vehicle's, their difference, and the limit it crossed.
    public static func explain(_ stationNetWh: Double, _ vehicleWh: Double, _ limitWh: Double) -> String {
        "meter \(wh(stationNetWh)) Wh, vehicle \(wh(vehicleWh)) Wh, " +
        "difference \(wh(stationNetWh - vehicleWh)) Wh, beyond \(wh(limitWh)) Wh"
    }

    /// Whole watt-hours, half away from zero, as the C# `MeteringDeviation.Wh` rounds.
    public static func wh(_ value: Double) -> Int64 {
        let magnitude = (abs(value) + 0.5).rounded(.down)
        return Int64(value < 0 ? -magnitude : magnitude)
    }
}
