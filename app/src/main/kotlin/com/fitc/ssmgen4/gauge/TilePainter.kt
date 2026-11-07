package com.fitc.ssmgen4.gauge

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Draws the number tiles of the trip and stuff pages: one or two big value lines over a
 * small caption, on a dark rounded panel. Text shrinks to fit rather than being cut off,
 * since tile sizes follow whatever area the head unit leaves.
 */
internal class TilePainter(density: Float) {

    private val corner = 10f * density
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TILE }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        color = Palette.LABEL
    }
    private val bounds = RectF()

    /**
     * The caption size for tiles of [width] x [height] at which every one of [captions] fits,
     * so a page's captions match instead of each shrinking on its own.
     */
    fun captionSize(captions: List<String>, width: Float, height: Float): Float =
        captions.minOfOrNull { fit(captionPaint, it, height * 0.15f, width * 0.9f) } ?: (height * 0.15f)

    /** Likewise for pill labels. */
    fun pillTextSize(labels: List<String>, width: Float, height: Float): Float =
        labels.minOfOrNull { fit(captionPaint, it, height * 0.5f, width * 0.85f) } ?: (height * 0.5f)

    fun draw(canvas: Canvas, area: RectF, lines: List<String>, caption: String, colour: Int, captionSize: Float) {
        canvas.drawRoundRect(area, corner, corner, panel)
        val w = area.width()
        val h = area.height()
        val usable = w * 0.9f

        captionPaint.textSize = captionSize
        val captionY = area.bottom - h * 0.1f
        canvas.drawText(caption, area.centerX(), captionY, captionPaint)

        // Value lines share the space above the caption.
        val space = captionY - captionPaint.textSize - area.top
        val lineHeight = space / lines.size
        var size = lineHeight * if (lines.size == 1) 0.62f else 0.72f
        for (line in lines) size = fit(valuePaint, line, size, usable)
        valuePaint.textSize = size
        valuePaint.color = colour
        lines.forEachIndexed { i, line ->
            val centre = area.top + h * 0.04f + lineHeight * (i + 0.5f)
            canvas.drawText(line, area.centerX(), centre + size * 0.36f, valuePaint)
        }
    }

    /** A pill for an on/off switch: filled when on, outlined when off. */
    fun pill(canvas: Canvas, area: RectF, label: String, on: Boolean, textSize: Float) {
        val radius = area.height() / 2f
        if (on) {
            panel.color = Palette.LIT
            canvas.drawRoundRect(area, radius, radius, panel)
            panel.color = Palette.TILE
        } else {
            canvas.drawRoundRect(area, radius, radius, panel)
        }
        captionPaint.color = if (on) Palette.ON_LIT else Palette.LABEL
        captionPaint.textSize = textSize
        canvas.drawText(label, area.centerX(), area.centerY() + captionPaint.textSize * 0.36f, captionPaint)
        captionPaint.color = Palette.LABEL
    }

    /** [size], or smaller if [text] would not fit in [width] at that size. */
    private fun fit(paint: Paint, text: String, size: Float, width: Float): Float {
        paint.textSize = size
        val measured = paint.measureText(text)
        return if (measured <= width || measured == 0f) size else size * width / measured
    }

    fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF =
        bounds.apply { set(left, top, right, bottom) }
}

/** Colours shared by the car pages; the dials use the same values. */
internal object Palette {
    const val TILE = 0xFF141B22.toInt()
    const val LIT = 0xFF35C5F0.toInt()
    const val ON_LIT = 0xFF000000.toInt()
    const val VALUE = 0xFFFFFFFF.toInt()
    const val LABEL = 0xFF9AA7B0.toInt()
    const val GOOD = 0xFF2ECC71.toInt()
    const val WARN = 0xFFFFC107.toInt()
    const val ALARM = 0xFFFF4136.toInt()
    const val DOT_OFF = 0xFF3A4652.toInt()
}
