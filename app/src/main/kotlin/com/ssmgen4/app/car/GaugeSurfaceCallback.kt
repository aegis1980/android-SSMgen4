package com.ssmgen4.app.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.ssmgen4.app.data.Metric
import com.ssmgen4.app.data.MetricSource
import com.ssmgen4.app.gauge.GaugePanelView
import com.ssmgen4.app.ssm.Dials

/**
 * Bridges the car's drawing surface to an ordinary Android view hierarchy.
 *
 * Android Auto gives apps a fixed set of templates and no way to draw arbitrary content —
 * except through a [android.view.Surface] handed out with `NavigationTemplate` to apps
 * holding `androidx.car.app.ACCESS_SURFACE`. Wrapping that surface in a [VirtualDisplay]
 * and a [Presentation] lets a normal [GaugePanelView] render onto the head unit, which is
 * the documented approach for getting custom pixels on screen.
 *
 * Also owns which [CarPage] is showing. The virtual display gets no touch input of its own;
 * swipes and taps arrive here from the host (see [PageGestures]) once the screen offers
 * `Action.PAN`, and the action strip can turn the page too.
 */
class GaugeSurfaceCallback(
    private val carContext: CarContext,
    private val source: MetricSource,
    /** The dials on the gauges page. */
    private val gaugeMetrics: List<Metric>,
) : SurfaceCallback {

    var page: CarPage = CarPage.GAUGES
        private set

    /** Runs on the main thread after every page change, so the screen can redo its actions. */
    var onPageChanged: () -> Unit = {}

    private val tripStore = TripStore(carContext)
    private val trip = tripStore.load()
    private var tripSavedAt = 0L
    private val gearStore = GearStore(carContext)
    private val gears = gearStore.load()
    private var gearsSavedAt = 0L
    private val gestures = PageGestures()
    private var surfaceWidth = 0

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var panel: GaugePanelView? = null

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.i(
            TAG,
            "surface available: ${surfaceContainer.width}x${surfaceContainer.height} " +
                "at ${surfaceContainer.dpi} dpi",
        )
        // A second surface without a destroy in between must not leak the first display.
        release()
        val surface = surfaceContainer.surface
        if (surface == null) {
            Log.w(TAG, "surface available with no Surface; nothing to draw into")
            return
        }
        surfaceWidth = surfaceContainer.width

        val displayManager = carContext.getSystemService(DisplayManager::class.java)
        val display = displayManager.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            surfaceContainer.width.coerceAtLeast(1),
            surfaceContainer.height.coerceAtLeast(1),
            surfaceContainer.dpi.coerceAtLeast(1),
            surface,
            0,
        )
        virtualDisplay = display

        val view = GaugePanelView(carContext, gaugeMetrics)
        view.showPage(page.ordinal)
        view.trip.show(trip)
        val show = Presentation(carContext, display.display).apply {
            setContentView(view)
            show()
        }
        presentation = show
        panel = view
        Log.i(TAG, "gauges shown on virtual display ${display.display.displayId}")

        source.focus(page.focus)
        source.start(
            onReadings = ::onReadings,
            onStatus = { text -> panel?.setStatus(text) },
            onCodes = { codes -> panel?.stuff?.setCodes(codes) },
        )
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "surface destroyed")
        release()
    }

    /**
     * Stops polling and tears down the display. Also called when the session ends, since a
     * host that drops the connection outright may never send [onSurfaceDestroyed], which
     * would leave the polling thread holding the adapter.
     */
    fun release() {
        source.stop()
        tripStore.save(trip)
        gearStore.save(gears)
        presentation?.dismiss()
        presentation = null
        // Releasing the VirtualDisplay also releases the Surface it was created with.
        virtualDisplay?.release()
        virtualDisplay = null
        panel = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(TAG, "visible area $visibleArea")
        panel?.setVisibleArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(TAG, "stable area $stableArea")
    }

    // ---- pages -------------------------------------------------------------------------

    // Gestures are logged so their direction on a real head unit can be checked in logcat.

    override fun onScroll(distanceX: Float, distanceY: Float) {
        Log.d(TAG, "scroll $distanceX, $distanceY")
        turn(gestures.onScroll(SystemClock.uptimeMillis(), distanceX, surfaceWidth))
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        Log.d(TAG, "fling $velocityX, $velocityY")
        turn(gestures.onFling(SystemClock.uptimeMillis(), velocityX, velocityY))
    }

    override fun onClick(x: Float, y: Float) {
        Log.d(TAG, "click $x, $y")
        turn(gestures.onClick(SystemClock.uptimeMillis(), x, surfaceWidth))
    }

    fun nextPage() = show(page.step(1))

    private fun turn(direction: Int) {
        if (direction != 0) show(page.step(direction))
    }

    private fun show(next: CarPage) {
        page = next
        Log.i(TAG, "page $next")
        panel?.showPage(next.ordinal)
        source.focus(next.focus)
        onPageChanged()
    }

    // ---- readings ----------------------------------------------------------------------

    private fun onReadings(readings: Map<String, Float>) {
        val view = panel ?: return
        view.update(readings)
        val now = SystemClock.elapsedRealtime()
        val kmh = readings[Dials.SPEED.metric.id]
        val maf = readings[Dials.MAF.metric.id]
        if (kmh != null && maf != null) {
            trip.sample(now, kmh.toDouble(), maf.toDouble())
            view.trip.show(trip)
            if (now - tripSavedAt >= SAVE_MS) {
                tripStore.save(trip)
                tripSavedAt = now
            }
        }
        val rpm = readings[Dials.RPM.metric.id]
        val clutch = readings[Dials.CLUTCH.metric.id]
        val neutral = readings[Dials.NEUTRAL.metric.id]
        if (kmh != null && rpm != null && clutch != null && neutral != null) {
            view.dials.setGear(gears.sample(now, rpm.toDouble(), kmh.toDouble(), clutch >= 0.5f, neutral >= 0.5f))
            if (now - gearsSavedAt >= SAVE_MS) {
                gearStore.save(gears)
                gearsSavedAt = now
            }
        }
    }

    fun resetPeaks() {
        panel?.resetPeaks()
    }

    fun resetTrip() {
        trip.reset()
        tripStore.save(trip)
        panel?.trip?.show(trip)
    }

    private companion object {
        const val TAG = "SsmGen4Car"
        const val VIRTUAL_DISPLAY_NAME = "SSMgen4"
        const val SAVE_MS = 15_000L
    }
}
