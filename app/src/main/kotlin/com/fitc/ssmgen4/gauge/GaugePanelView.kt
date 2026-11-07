package com.fitc.ssmgen4.gauge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.fitc.ssmgen4.data.Metric

/**
 * Everything shown on the head unit: three pages, of which one is visible at a time and fills
 * the whole area. Pages are indexed trip 0, gauges 1, stuff 2; the car session decides which
 * one shows.
 *
 * The page dots and the connection status line share a small chip laid over the bottom edge.
 * It takes no room of its own: it shows while no readings are arriving (connecting, link lost)
 * and for a few seconds after a [poke] (a touch or a page change), and is otherwise hidden.
 *
 * The host reports the usable rectangle through `SurfaceCallback.onVisibleAreaChanged`;
 * [setVisibleArea] keeps to its sides and bottom but not its top, which is only reserved for the
 * action strip (see there).
 */
class GaugePanelView(context: Context, gaugeMetrics: List<Metric>) : FrameLayout(context) {

    val trip = TripPageView(context)
    val dials = DialsPageView(context, gaugeMetrics)
    val stuff = StuffPageView(context)

    private val pages: List<View> = listOf(trip, dials, stuff)
    private val dots = PageDots(context, pages.size)
    private val status: TextView
    private val chip: LinearLayout

    private var lastReadingAt = NEVER
    private var shownUntil = 0L
    private val tick = object : Runnable {
        override fun run() {
            refreshChip()
            postDelayed(this, TICK_MS)
        }
    }

    init {
        setBackgroundColor(COLOUR_BACKGROUND)
        pages.forEachIndexed { i, page ->
            page.visibility = if (i == START_PAGE) VISIBLE else GONE
            addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        dots.selected = START_PAGE

        chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(COLOUR_CHIP)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Palette.DOT_OFF)
            }
            setPadding(dp(6), dp(1), dp(10), dp(1))
        }
        chip.addView(dots, LinearLayout.LayoutParams(dp(12) * pages.size, dp(14)))
        status = TextView(context).apply {
            setTextColor(Palette.LABEL)
            textSize = 12f
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
        }
        chip.addView(
            status,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) },
        )
        // Kept thin and flush with the bottom edge, where every page has only padding.
        addView(
            chip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(1) },
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    fun showPage(index: Int) {
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) VISIBLE else GONE }
        dots.selected = index
        poke()
    }

    /** Push a batch of readings to whichever pages show them. */
    fun update(readings: Map<String, Float>) {
        lastReadingAt = SystemClock.uptimeMillis()
        dials.update(readings)
        stuff.update(readings)
        refreshChip()
    }

    fun setStatus(text: String) {
        status.text = text
        status.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    /** Show the dots and status for a while, as Android Auto does with its own buttons on a touch. */
    fun poke() {
        shownUntil = SystemClock.uptimeMillis() + SHOW_MS
        refreshChip()
    }

    private fun refreshChip() {
        val now = SystemClock.uptimeMillis()
        val stale = lastReadingAt == NEVER || now - lastReadingAt > STALE_MS
        val wanted = if (stale || now < shownUntil) VISIBLE else GONE
        if (chip.visibility != wanted) chip.visibility = wanted
    }

    fun resetPeaks() {
        dials.resetPeaks()
    }

    /**
     * Inset to the host's visible area, except at the top. Android Auto reserves the top band
     * (88 px on an 800x480 screen) for the action strip permanently, but shows the strip only
     * for 10 seconds after a touch and does not report the area growing when it hides. The
     * pages take the full height instead, and the strip floats over their top edge while shown.
     */
    fun setVisibleArea(visibleArea: Rect) {
        if (visibleArea.isEmpty) return
        setPadding(
            visibleArea.left,
            minOf(visibleArea.top, visibleArea.left),
            (width - visibleArea.right).coerceAtLeast(0),
            (height - visibleArea.bottom).coerceAtLeast(0),
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** One dot per page, the current one lit. */
    private class PageDots(context: Context, private val count: Int) : View(context) {

        var selected = 0
            set(value) {
                field = value
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val step = width.toFloat() / count
            val radius = minOf(step, height.toFloat()) * 0.22f
            for (i in 0 until count) {
                paint.color = if (i == selected) Palette.LIT else Palette.DOT_OFF
                canvas.drawCircle(step * (i + 0.5f), height / 2f, radius, paint)
            }
        }
    }

    private companion object {
        val COLOUR_BACKGROUND = Color.BLACK
        const val COLOUR_CHIP = 0xF20B0E12.toInt()
        const val START_PAGE = 1

        const val NEVER = Long.MIN_VALUE
        /** No readings for this long means the link is down or stalled: show the status. */
        const val STALE_MS = 3_000L
        /** As long as Android Auto keeps its action strip up after a touch. */
        const val SHOW_MS = 10_000L
        const val TICK_MS = 500L
    }
}
