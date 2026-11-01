package com.ssmgen4.app.gauge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ssmgen4.app.data.Metric

/**
 * Everything shown on the head unit: three pages, of which one is visible at a time, over a
 * footer of page dots and the connection status line. Pages are indexed trip 0, gauges 1,
 * stuff 2; the car session decides which one shows.
 *
 * The host draws its own chrome (action strip, status bar) over parts of the surface and
 * reports the usable rectangle through `SurfaceCallback.onVisibleAreaChanged`; [setVisibleArea]
 * insets everything so nothing important ends up underneath it.
 */
class GaugePanelView(context: Context, gaugeMetrics: List<Metric>) : LinearLayout(context) {

    val trip = TripPageView(context)
    val dials = DialsPageView(context, gaugeMetrics)
    val stuff = StuffPageView(context)

    private val pages: List<View> = listOf(trip, dials, stuff)
    private val dots = PageDots(context, pages.size)
    private val status: TextView

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOUR_BACKGROUND)

        val frame = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        for (page in pages) {
            frame.addView(page, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(frame)

        val footer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), dp(4))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        footer.addView(dots, LayoutParams(dp(14) * pages.size, dp(14)))
        status = TextView(context).apply {
            setTextColor(Palette.LABEL)
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            // Balance the dots on the left so the text sits in the middle.
            setPadding(0, 0, dp(14) * pages.size, 0)
        }
        footer.addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(footer)

        showPage(1)
    }

    fun showPage(index: Int) {
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) VISIBLE else GONE }
        dots.selected = index
    }

    /** Push a batch of readings to whichever pages show them. */
    fun update(readings: Map<String, Float>) {
        dials.update(readings)
        stuff.update(readings)
    }

    fun setStatus(text: String) {
        status.text = text
    }

    fun resetPeaks() {
        dials.resetPeaks()
    }

    /** Inset so the host's own overlays never cover a dial. */
    fun setVisibleArea(visibleArea: Rect) {
        if (visibleArea.isEmpty) return
        setPadding(
            visibleArea.left,
            visibleArea.top,
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
    }
}
