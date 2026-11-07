package com.fitc.ssmgen4.car

import android.content.Context
import androidx.core.content.edit
import com.fitc.ssmgen4.data.GearEstimator

/** Keeps the measured tyre circumference, so the next drive starts calibrated. */
class GearStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): GearEstimator {
        val measurements = prefs.getInt(KEY_MEASUREMENTS, 0)
        // Nothing measured yet: start from the current default, not whatever default was saved.
        if (measurements == 0) return GearEstimator()
        val circumference = Double.fromBits(prefs.getLong(KEY_CIRCUMFERENCE, 0L))
        return GearEstimator(circumference, measurements)
    }

    fun save(gears: GearEstimator) {
        prefs.edit {
            putLong(KEY_CIRCUMFERENCE, gears.circumferenceM.toRawBits())
            putInt(KEY_MEASUREMENTS, gears.measurements)
        }
    }

    private companion object {
        const val PREFS = "gears"
        const val KEY_CIRCUMFERENCE = "circumference_m"
        const val KEY_MEASUREMENTS = "measurements"
    }
}
