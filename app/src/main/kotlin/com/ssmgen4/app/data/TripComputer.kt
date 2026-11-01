package com.ssmgen4.app.data

/**
 * Distance, fuel and time for the trip page, integrated from road speed and airflow.
 *
 * Fuel is estimated from the MAF: at the stoichiometric 14.7:1 each gram of petrol burns
 * with 14.7 g of air, and petrol weighs about 745 g/L. That holds in closed loop. Under boost
 * the ECU runs richer and on a closed throttle it may cut fuel altogether, so the estimate
 * reads low on hard driving and high on long descents; [calibration] scales it once a
 * fill-up shows by how much.
 */
class TripComputer(
    distanceKm: Double = 0.0,
    fuelLitres: Double = 0.0,
    elapsedMs: Long = 0L,
    private val calibration: Double = 1.0,
) {
    var distanceKm: Double = distanceKm
        private set
    var fuelLitres: Double = fuelLitres
        private set
    /** Time with data coming in, idling included. */
    var elapsedMs: Long = elapsedMs
        private set

    /** Fuel flow right now, smoothed over a couple of seconds. */
    var litresPerHour: Double = 0.0
        private set

    private var smoothedKmh = 0.0
    private var lastMs = NEVER

    /** Feed one reading. [nowMs] is a monotonic clock, e.g. `SystemClock.elapsedRealtime()`. */
    fun sample(nowMs: Long, kmh: Double, mafGramsPerSecond: Double) {
        val speed = kmh.coerceAtLeast(0.0)
        val flow = mafGramsPerSecond.coerceAtLeast(0.0) * 3600.0 / (STOICHIOMETRIC * PETROL_G_PER_L) * calibration
        val dt = nowMs - lastMs
        val first = lastMs == NEVER
        lastMs = nowMs
        if (first || dt > MAX_GAP_MS) {
            // Nothing to integrate across a gap (a Bluetooth drop); start the smoothing afresh.
            litresPerHour = flow
            smoothedKmh = speed
            return
        }
        if (dt <= 0) return
        val hours = dt / 3_600_000.0
        distanceKm += speed * hours
        fuelLitres += flow * hours
        elapsedMs += dt
        val weight = dt / (SMOOTHING_MS + dt)
        litresPerHour += (flow - litresPerHour) * weight
        smoothedKmh += (speed - smoothedKmh) * weight
    }

    /** Right now; null when barely moving, where litres per 100 km means nothing. */
    val litresPer100Km: Double?
        get() = if (smoothedKmh < MIN_KMH) null else litresPerHour / smoothedKmh * 100.0

    /** Over the trip; null until there is enough distance for it to settle. */
    val averageLitresPer100Km: Double?
        get() = if (distanceKm < MIN_AVERAGE_KM) null else fuelLitres / distanceKm * 100.0

    val averageKmh: Double?
        get() = if (elapsedMs < MIN_AVERAGE_MS) null else distanceKm / (elapsedMs / 3_600_000.0)

    fun reset() {
        distanceKm = 0.0
        fuelLitres = 0.0
        elapsedMs = 0L
    }

    companion object {
        const val STOICHIOMETRIC = 14.7
        const val PETROL_G_PER_L = 745.0

        private const val NEVER = Long.MIN_VALUE
        private const val MAX_GAP_MS = 2_000L
        private const val SMOOTHING_MS = 2_000.0
        private const val MIN_KMH = 5.0
        private const val MIN_AVERAGE_KM = 0.5
        private const val MIN_AVERAGE_MS = 60_000L
    }
}
