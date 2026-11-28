package cloud.charging.v2g.metering

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * How far the station's reading may stray from the vehicle's own count before the vehicle ends the
 * charging over it: a [fraction] of the station's figure, but never less than [floorWh].
 *
 * The port of the C# `Metering.MeteringDeviationLimit`. A stand-in for a comparator with a loss model and
 * an uncertainty band, and it says so: a fixed fraction knows nothing of cable losses, so it has to leave
 * them room. The floor is there because a fraction of a small number is smaller than one iteration's
 * rounding.
 */
data class MeteringDeviationLimit(val fraction: Double, val floorWh: ULong) {

    /** The difference a reading of [stationNetWh] may show. */
    fun limitWh(stationNetWh: Double): Double = max(floorWh.toDouble(), fraction * abs(stationNetWh))

    /** Whether the two figures are further apart than this limit allows. */
    fun exceededBy(stationNetWh: Double, vehicleWh: Double): Boolean =
        abs(stationNetWh - vehicleWh) > limitWh(stationNetWh)
}

/**
 * Why a vehicle ended the charging over the station's meter: the station's net figure, the vehicle's own
 * count at the same iteration, and the limit their difference crossed.
 *
 * ISO 15118-20 lets the vehicle give its reason in `SessionStopReq`: `EVTerminationCode`, a URN of at most
 * 80 characters, and `EVTerminationExplanation`, at most 160. No code is defined yet, so [TERMINATION_CODE]
 * is ours. The explanation is the **same text in C#, Kotlin and Swift**, because a recorded session holds
 * the vehicle's bytes and every port replays them; hence whole watt-hours only, rounded half away from zero
 * by hand.
 */
data class MeteringDeviation(val stationNetWh: Double, val vehicleWh: Double, val limitWh: Double) {

    /** The `EVTerminationExplanation`: the four figures the decision rests on. */
    val explanation: String get() = explain(stationNetWh, vehicleWh, limitWh)

    companion object {

        /** The `EVTerminationCode` a vehicle sends when it ends the charging over the meter. */
        const val TERMINATION_CODE = "urn:openchargingcloud:EVTerminationCode:MeteringDeviation"

        /** The station's figure against the vehicle's, their difference, and the limit it crossed. */
        fun explain(stationNetWh: Double, vehicleWh: Double, limitWh: Double): String =
            "meter ${wh(stationNetWh)} Wh, vehicle ${wh(vehicleWh)} Wh, " +
            "difference ${wh(stationNetWh - vehicleWh)} Wh, beyond ${wh(limitWh)} Wh"

        /** Whole watt-hours, half away from zero, as the C# `MeteringDeviation.Wh` rounds. */
        fun wh(value: Double): Long {
            val magnitude = floor(abs(value) + 0.5)
            return (if (value < 0) -magnitude else magnitude).toLong()
        }
    }
}
