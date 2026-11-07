package com.fitc.ssmgen4.car

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import com.fitc.ssmgen4.R

/**
 * The car screen itself. `NavigationTemplate` contributes no content of its own here —
 * it exists because it is the template that grants surface access, and the pages are
 * drawn onto that surface by [GaugeSurfaceCallback]. The template only supplies the
 * action strips the host overlays on top.
 *
 * The first action always turns to the next page and is titled with that page's name; the
 * second resets whatever the current page accumulates (dial peaks, the trip).
 */
class GaugeScreen(
    carContext: CarContext,
    private val surfaceCallback: GaugeSurfaceCallback,
) : Screen(carContext) {

    init {
        surfaceCallback.onPageChanged = { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val page = surfaceCallback.page
        val actions = ActionStrip.Builder()
            .addAction(action(page.step(1).title, R.drawable.ic_car_next_page) { surfaceCallback.nextPage() })
        when (page) {
            CarPage.GAUGES -> actions.addAction(
                action(R.string.car_action_reset_peaks, R.drawable.ic_car_peaks) { surfaceCallback.resetPeaks() },
            )
            CarPage.TRIP -> actions.addAction(
                action(R.string.car_action_reset_trip, R.drawable.ic_car_reset) { surfaceCallback.resetTrip() },
            )
            CarPage.STUFF -> Unit
        }

        val template = NavigationTemplate.Builder().setActionStrip(actions.build())
        // Swipes and taps on the surface only reach SurfaceCallback while the map action strip
        // offers Pan. On a touchscreen the host never shows the Pan button itself.
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            template.setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
        }
        return template.build()
    }

    private fun action(@StringRes title: Int, @DrawableRes icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setTitle(carContext.getString(title))
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()
}
