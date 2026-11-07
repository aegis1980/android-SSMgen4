package com.fitc.ssmgen4.gauge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.fitc.ssmgen4.data.Metric
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A single round dial. Drawn with [Canvas] rather than Compose so it can be dropped
 * straight into a [android.app.Presentation] on the car's virtual display without
 * needing a lifecycle owner or the Compose compiler plugin.
 *
 * Deliberately high contrast and sparse: on a head unit this is read at a glance.
 */
class GaugeView(context: Context, private val metric: Metric) : View(context) {

    private var value: Float = metric.min
    private var peak: Float = metric.min

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOUR_TICK
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    /** A dark outline and soft shadow drawn under the text, so it reads clearly over the needle. */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = textPaint.typeface
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = COLOUR_HALO
    }

    private val arcBounds = RectF()

    /** Set the live reading. Values outside the display range are clamped, not dropped. */
    fun setValue(newValue: Float) {
        val clamped = newValue.coerceIn(metric.min, metric.max)
        if (clamped == value) return
        value = clamped
        if (clamped > peak) peak = clamped
        invalidate()
    }

    fun resetPeak() {
        peak = value
        invalidate()
    }

    private val alarming: Boolean
        get() = metric.alarmAbove?.let { value >= it } == true

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // The arc has its gap at the bottom, so with its stroke the dial is about 2.18 radii
        // wide but only 1.89 tall: fit to both and centre what is actually drawn, which lets it
        // grow in the short, wide cells under Android Auto's buttons. Strokes, ticks and text
        // all scale from [size], with radius = 0.41 size.
        val radius = min(w / 2.18f, h / 1.89f) * 0.95f
        val size = radius / 0.41f
        val cx = w / 2f
        val cy = h / 2f + radius * 0.146f
        val stroke = size * 0.075f

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Unlit track
        arcPaint.strokeWidth = stroke
        arcPaint.color = COLOUR_UNLIT
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP, false, arcPaint)

        // Lit portion up to the current value
        val fraction = (value - metric.min) / (metric.max - metric.min)
        arcPaint.color = if (alarming) COLOUR_ALARM else COLOUR_LIT
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP * fraction, false, arcPaint)

        drawTicks(canvas, cx, cy, radius, stroke, size)
        drawPeakMarker(canvas, stroke)
        // The needle goes under the readout; the readout's halo keeps it legible where they cross.
        drawNeedle(canvas, cx, cy, radius, stroke, size, fraction)
        drawReadout(canvas, cx, cy, size)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float, stroke: Float, size: Float) {
        tickPaint.strokeWidth = size * 0.008f
        val outer = radius - stroke / 2f
        val inner = outer - size * 0.045f
        for (i in 0..TICKS) {
            val angle = Math.toRadians((START_ANGLE + SWEEP * i / TICKS).toDouble())
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            canvas.drawLine(cx + c * inner, cy + s * inner, cx + c * outer, cy + s * outer, tickPaint)
        }
    }

    /** Uses [arcBounds], already positioned by onDraw. */
    private fun drawPeakMarker(canvas: Canvas, stroke: Float) {
        if (peak <= metric.min) return
        val peakFraction = (peak - metric.min) / (metric.max - metric.min)
        arcPaint.color = COLOUR_PEAK
        arcPaint.strokeWidth = stroke * 0.35f
        canvas.drawArc(arcBounds, START_ANGLE + SWEEP * peakFraction - 0.6f, 1.2f, false, arcPaint)
    }

    private fun drawNeedle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        stroke: Float,
        size: Float,
        fraction: Float,
    ) {
        val angle = Math.toRadians((START_ANGLE + SWEEP * fraction).toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val tip = radius - stroke
        val tail = -size * 0.06f
        needlePaint.strokeWidth = size * 0.022f
        needlePaint.color = COLOUR_NEEDLE
        canvas.drawLine(cx + c * tail, cy + s * tail, cx + c * tip, cy + s * tip, needlePaint)
    }

    private fun drawReadout(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        drawHaloed(canvas, format(value), cx, cy + size * 0.09f, size * 0.26f, if (alarming) COLOUR_ALARM else COLOUR_VALUE)
        val caption = if (metric.unit.isEmpty()) metric.label else "${metric.label}  ${metric.unit}"
        drawHaloed(canvas, caption, cx, cy + size * 0.265f, size * 0.095f, COLOUR_LABEL)
    }

    private fun drawHaloed(canvas: Canvas, text: String, x: Float, y: Float, textSize: Float, colour: Int) {
        haloPaint.textSize = textSize
        haloPaint.strokeWidth = textSize * 0.16f
        haloPaint.setShadowLayer(textSize * 0.12f, 0f, 0f, COLOUR_HALO)
        canvas.drawText(text, x, y, haloPaint)
        textPaint.textSize = textSize
        textPaint.color = colour
        canvas.drawText(text, x, y, textPaint)
    }

    private fun format(v: Float): String = when (metric.decimals) {
        0 -> v.toInt().toString()
        else -> String.format("%.${metric.decimals}f", v)
    }

    private companion object {
        /** 135° through 405°: a 270° sweep with the gap at the bottom. */
        const val START_ANGLE = 135f
        const val SWEEP = 270f
        const val TICKS = 9

        const val COLOUR_UNLIT = 0xFF26303A.toInt()
        const val COLOUR_LIT = 0xFF35C5F0.toInt()
        const val COLOUR_ALARM = 0xFFFF4136.toInt()
        const val COLOUR_NEEDLE = 0xFFFF3B30.toInt()
        const val COLOUR_HALO = 0xE6000000.toInt()
        const val COLOUR_PEAK = 0xFFFFC107.toInt()
        const val COLOUR_TICK = 0xFF5A6670.toInt()
        const val COLOUR_VALUE = 0xFFFFFFFF.toInt()
        const val COLOUR_LABEL = 0xFF9AA7B0.toInt()
    }
}
