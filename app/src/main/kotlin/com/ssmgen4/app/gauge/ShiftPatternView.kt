package com.ssmgen4.app.gauge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.ssmgen4.app.data.GearEstimator
import kotlin.math.min

/**
 * A six-speed manual shift gate with reverse, the current gear lit.
 *
 * Laid out as on Subaru six-speeds: reverse up and left of first, then 1/2, 3/4, 5/6, with
 * neutral across the middle. Reverse is drawn for completeness but never lights (see
 * [GearEstimator]). With the clutch down the gear being left is outlined in amber.
 */
class ShiftPatternView(context: Context) : View(context) {

    private var reading: GearEstimator.Reading? = null
    private val x = FloatArray(4)

    private val gate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.DOT_OFF
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        color = Palette.LABEL
    }

    fun show(reading: GearEstimator.Reading) {
        if (reading == this.reading) return
        this.reading = reading
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val captionHeight = h * 0.14f
        val patternHeight = h - captionHeight
        // Four lanes (R, 1/2, 3/4, 5/6) and two rows, each knob 0.36 lanes in radius.
        val lane = min(w * 0.94f / 3.72f, patternHeight * 0.94f / 2.82f)
        val radius = lane * 0.36f
        val row = lane * 1.05f
        val left = (w - lane * 3) / 2
        for (i in x.indices) x[i] = left + i * lane
        val middle = patternHeight / 2
        val top = middle - row
        val bottom = middle + row

        gate.strokeWidth = radius * 0.35f
        canvas.drawLine(x[0], middle, x[3], middle, gate)
        canvas.drawLine(x[0], top, x[0], middle, gate)
        for (i in 1..3) canvas.drawLine(x[i], top, x[i], bottom, gate)

        val r = reading
        val engaged = r?.gear
        val clutchDown = r?.clutch == true
        drawKnob(canvas, x[0], top, radius, "R", lit = false, clutch = false)
        for (gear in 1..GearEstimator.GEARS) {
            val y = if (gear % 2 == 1) top else bottom
            val current = gear == engaged
            drawKnob(canvas, x[(gear + 1) / 2], y, radius, gear.toString(), lit = current && !clutchDown, clutch = current && clutchDown)
        }
        drawKnob(canvas, (x[1] + x[2]) / 2, middle, radius * 0.8f, "N", lit = r?.neutral == true, clutch = false)

        val text = when {
            r == null -> "Gear"
            r.clutch -> "Clutch"
            else -> ""
        }
        if (text.isNotEmpty()) {
            caption.textSize = captionHeight * 0.55f
            val measured = caption.measureText(text)
            if (measured > w * 0.95f) caption.textSize *= w * 0.95f / measured
            canvas.drawText(text, w / 2, h - captionHeight * 0.3f, caption)
        }
    }

    private fun drawKnob(canvas: Canvas, cx: Float, cy: Float, radius: Float, text: String, lit: Boolean, clutch: Boolean) {
        knob.color = if (lit) Palette.LIT else Palette.TILE
        canvas.drawCircle(cx, cy, radius, knob)
        if (clutch) {
            ring.color = Palette.WARN
            ring.strokeWidth = radius * 0.16f
            canvas.drawCircle(cx, cy, radius - ring.strokeWidth / 2, ring)
        }
        label.color = when {
            lit -> Palette.ON_LIT
            clutch -> Palette.WARN
            else -> Palette.LABEL
        }
        label.textSize = radius * 1.15f
        canvas.drawText(text, cx, cy + label.textSize * 0.36f, label)
    }
}
