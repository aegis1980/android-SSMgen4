package com.fitc.ssmgen4.car

import android.content.Context
import androidx.core.content.edit
import com.fitc.ssmgen4.data.TripComputer

/**
 * Keeps the trip across car sessions, like a car's own trip meter: it only goes back to
 * zero when reset from the car screen. Wireless Android Auto can drop and reconnect mid-drive,
 * and that should not lose the trip.
 */
class TripStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): TripComputer = TripComputer(
        distanceKm = Double.fromBits(prefs.getLong(KEY_DISTANCE, 0L)),
        fuelLitres = Double.fromBits(prefs.getLong(KEY_FUEL, 0L)),
        elapsedMs = prefs.getLong(KEY_ELAPSED, 0L),
    )

    fun save(trip: TripComputer) {
        prefs.edit {
            putLong(KEY_DISTANCE, trip.distanceKm.toRawBits())
            putLong(KEY_FUEL, trip.fuelLitres.toRawBits())
            putLong(KEY_ELAPSED, trip.elapsedMs)
        }
    }

    private companion object {
        const val PREFS = "trip"
        const val KEY_DISTANCE = "distance_km"
        const val KEY_FUEL = "fuel_litres"
        const val KEY_ELAPSED = "elapsed_ms"
    }
}
