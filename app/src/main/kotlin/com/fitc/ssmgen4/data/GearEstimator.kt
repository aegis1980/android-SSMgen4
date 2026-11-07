package com.fitc.ssmgen4.data

import kotlin.math.abs

/**
 * Which gear the six-speed (TY856WBDAA) is in, from engine speed against road speed.
 *
 * The gearing is known, so each gear's rpm per km/h depends only on the rolling circumference
 * of the tyres. That starts at a typical [DEFAULT_CIRCUMFERENCE_M], close enough to tell the
 * gears apart from the first metre (the nearest pair, 4th and 5th, are 17% apart), and is then
 * measured from steady driving. A steady reading is credited to the one gear that puts the
 * circumference inside [MIN_CIRCUMFERENCE_M]..[MAX_CIRCUMFERENCE_M]; that window is narrower
 * than the smallest gear step, so at most one gear can, and a misread gear cannot skew it.
 *
 * The neutral switch reports neutral and the clutch switch holds the gear being left until the
 * next one engages. Reverse (3.545) is within 2.5% of first (3.636) and the ECU has no reverse
 * signal, so reversing reads as first.
 */
class GearEstimator(
    circumferenceM: Double = DEFAULT_CIRCUMFERENCE_M,
    measurements: Int = 0,
) {

    /** [gear] is 1..[GEARS], or null when it cannot be told (or in neutral). */
    data class Reading(val gear: Int?, val neutral: Boolean, val clutch: Boolean)

    /** Rolling circumference the gears are currently worked out from, metres. */
    var circumferenceM: Double = circumferenceM.coerceIn(MIN_CIRCUMFERENCE_M, MAX_CIRCUMFERENCE_M)
        private set

    /** Steady readings that have gone into [circumferenceM]; 0 means it is still the default. */
    var measurements: Int = measurements
        private set

    private var lastRatio = 0.0
    private var steadyMs = 0L
    private var lastMs = NEVER
    private var lastGear: Int? = null
    private var lastGearMs = 0L

    /** rpm per km/h in [gear] (1-based) at [circumference] metres. */
    fun rpmPerKmh(gear: Int, circumference: Double = circumferenceM): Double =
        GEARBOX[gear - 1] * FINAL_DRIVE * 1000.0 / (60.0 * circumference)

    fun sample(nowMs: Long, rpm: Double, kmh: Double, clutch: Boolean, neutral: Boolean): Reading {
        val dt = if (lastMs == NEVER) 0L else (nowMs - lastMs).coerceIn(0L, MAX_GAP_MS)
        lastMs = nowMs
        fun held() = lastGear.takeIf { nowMs - lastGearMs < HOLD_MS }

        if (neutral) {
            steadyMs = 0
            lastGear = null
            return Reading(null, neutral = true, clutch = clutch)
        }
        if (clutch || kmh < MIN_KMH || rpm < MIN_RPM) {
            // Mid-shift, or too slow for the ratio to mean anything (speed comes in whole km/h).
            steadyMs = 0
            return Reading(if (clutch) lastGear else held(), neutral = false, clutch = clutch)
        }

        val ratio = rpm / kmh
        steadyMs = if (lastRatio > 0 && abs(ratio - lastRatio) / lastRatio < STEADY) steadyMs + dt else 0L
        lastRatio = ratio
        if (steadyMs >= STEADY_MS && kmh >= CALIBRATE_MIN_KMH) calibrate(ratio)

        val gear = (1..GEARS)
            .minBy { abs(ratio / rpmPerKmh(it) - 1) }
            .takeIf { abs(ratio / rpmPerKmh(it) - 1) < MATCH }
        if (gear != null) {
            lastGear = gear
            lastGearMs = nowMs
        }
        return Reading(gear ?: held(), neutral = false, clutch = false)
    }

    private fun calibrate(ratio: Double) {
        val implied = (1..GEARS)
            .map { GEARBOX[it - 1] * FINAL_DRIVE * 1000.0 / (60.0 * ratio) }
            .singleOrNull { it in MIN_CIRCUMFERENCE_M..MAX_CIRCUMFERENCE_M }
            ?: return
        val w = measurements.coerceAtMost(MAX_AVERAGED)
        circumferenceM = (circumferenceM * w + implied) / (w + 1)
        measurements++
    }

    companion object {
        /** TY856WBDAA, 1st to 6th. Reverse is 3.545. */
        val GEARBOX = listOf(3.636, 2.235, 1.521, 1.137, 0.971, 0.756)
        /** Front 3.900; the rear's 3.545 behind the 1.100 transfer drop comes to the same. */
        const val FINAL_DRIVE = 3.900
        val GEARS = GEARBOX.size

        /**
         * 215/45R18 on all four wheels: 650.7 mm across (18 in rim plus two 45% sidewalls of
         * 215 mm), so 2.044 m round, less about 1% for the tyre flattening under load.
         */
        val DEFAULT_CIRCUMFERENCE_M = Math.PI * (18 * 25.4 + 2 * 215 * 0.45) / 1000 * 0.99
        /**
         * 7% either side covers wear and speedo calibration, and stays narrower (15%) than the
         * smallest gear step (4th/5th, 17%).
         */
        val MIN_CIRCUMFERENCE_M = DEFAULT_CIRCUMFERENCE_M * 0.93
        val MAX_CIRCUMFERENCE_M = DEFAULT_CIRCUMFERENCE_M * 1.07

        private const val NEVER = Long.MIN_VALUE
        private const val MAX_GAP_MS = 2_000L
        private const val MIN_KMH = 5.0
        private const val MIN_RPM = 500.0
        /** Fast enough that whole-km/h speed barely matters, and reversing rarely gets there. */
        private const val CALIBRATE_MIN_KMH = 20.0
        /** Consecutive readings within this fraction of each other count as steady. */
        private const val STEADY = 0.03
        private const val STEADY_MS = 1_000L
        /**
         * A reading this close to a gear's rpm per km/h is shown as that gear: loose enough for an
         * uncalibrated start anywhere in the window (7.5% at worst), under half the 4th/5th
         * step (8.2%).
         */
        private const val MATCH = 0.08
        /** Caps how far one reading can move a well-measured circumference. */
        private const val MAX_AVERAGED = 500
        /** How long the last gear stays shown while crawling or between matches. */
        private const val HOLD_MS = 3_000L
    }
}
