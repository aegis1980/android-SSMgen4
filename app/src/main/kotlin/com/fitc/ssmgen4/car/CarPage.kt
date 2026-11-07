package com.fitc.ssmgen4.car

import androidx.annotation.StringRes
import com.fitc.ssmgen4.R
import com.fitc.ssmgen4.data.TroubleCodes
import com.fitc.ssmgen4.ssm.Dials

/**
 * The pages of the car screen, in swipe order. Each one polls only what it shows, plus what
 * the trip and the gear estimate need, so both keep counting and learning whichever page is up.
 */
enum class CarPage(@param:StringRes val title: Int) {
    TRIP(R.string.car_page_trip),
    GAUGES(R.string.car_page_gauges),
    STUFF(R.string.car_page_stuff);

    /** Metric ids to poll while this page is showing. */
    val focus: Set<String>
        get() = ALWAYS + when (this) {
            TRIP -> emptySet()
            GAUGES -> Dials.DEFAULT.map { it.metric.id }.toSet()
            STUFF -> Dials.STUFF.map { it.metric.id }.toSet() + TroubleCodes.ID
        }

    /** [direction] pages on, wrapping round at either end. */
    fun step(direction: Int): CarPage = entries[(ordinal + direction).mod(entries.size)]

    private companion object {
        /** Speed and airflow for the trip; engine speed, clutch and neutral for the gear. */
        val ALWAYS: Set<String> = listOf(Dials.SPEED, Dials.MAF, Dials.RPM, Dials.CLUTCH, Dials.NEUTRAL)
            .map { it.metric.id }
            .toSet()
    }
}
