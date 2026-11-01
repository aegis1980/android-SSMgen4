package com.ssmgen4.app.gauge

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.ssmgen4.app.data.TroubleCodes
import com.ssmgen4.app.ssm.DialDef
import com.ssmgen4.app.ssm.Dials
import kotlin.math.abs

/**
 * The "stuff" page: engine health and odds and ends, as two rows of tiles over a row of
 * switch lights.
 *
 * Top: check engine, IAM, fuel trims, battery. Middle: coolant, intake, misfire counts,
 * cam advance. Bottom: A/C, compressor, radiator fan, lights, clutch, brake. Colours flag
 * what is worth a look; a dash means no reading yet.
 */
class StuffPageView(context: Context) : View(context) {

    private val painter = TilePainter(resources.displayMetrics.density)
    private val gap = 6f * resources.displayMetrics.density
    private val values = HashMap<String, Float>()
    private var codes: TroubleCodes? = null

    fun update(readings: Map<String, Float>) {
        values.putAll(readings)
        invalidate()
    }

    fun setCodes(codes: TroubleCodes) {
        this.codes = codes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        val tiles = listOf(
            checkEngine(), iam(), trims(), battery(),
            coolant(), intake(), misfires(), cams(),
        )
        val columns = 4
        val pillsHeight = height * 0.18f
        val w = (width - gap * (columns + 1)) / columns
        val h = (height - pillsHeight - gap * 4) / 2
        val captionSize = painter.captionSize(tiles.map { it.caption }, w, h)
        tiles.forEachIndexed { i, tile ->
            val left = gap + (i % columns) * (w + gap)
            val top = gap + (i / columns) * (h + gap)
            painter.draw(canvas, painter.rect(left, top, left + w, top + h), tile.lines, tile.caption, tile.colour, captionSize)
        }

        val switches = Dials.SWITCHES
        val pillTop = height - gap - pillsHeight
        val pillWidth = (width - gap * (switches.size + 1)) / switches.size
        val labelSize = painter.pillTextSize(switches.map { it.metric.label }, pillWidth, pillsHeight)
        switches.forEachIndexed { i, dial ->
            val left = gap + i * (pillWidth + gap)
            val on = (values[dial.metric.id] ?: 0f) >= 0.5f
            val area = painter.rect(left, pillTop, left + pillWidth, pillTop + pillsHeight)
            painter.pill(canvas, area, dial.metric.label, on, labelSize)
        }
    }

    // ---- tiles -------------------------------------------------------------------------

    private class Tile(val lines: List<String>, val caption: String, val colour: Int = Palette.VALUE) {
        constructor(value: String, caption: String, colour: Int = Palette.VALUE) : this(listOf(value), caption, colour)
    }

    private fun checkEngine(): Tile {
        val c = codes
        val shown = c?.all.orEmpty().take(2).joinToString(" ") { it.substringBefore(' ') }
        return when {
            value(Dials.MIL) == 1f -> Tile("MIL ON", shown.ifEmpty { "Check engine" }, Palette.ALARM)
            c == null -> Tile("…", "Reading codes", Palette.LABEL)
            c.all.isEmpty() -> Tile("OK", "No trouble codes", Palette.GOOD)
            else -> Tile(if (c.all.size == 1) "1 code" else "${c.all.size} codes", shown, Palette.WARN)
        }
    }

    private fun iam(): Tile {
        val v = value(Dials.IAM) ?: return Tile(DASH, IAM_CAPTION)
        return Tile("%.2f".format(v), IAM_CAPTION, if (v >= 0.99f) Palette.GOOD else Palette.WARN)
    }

    private fun trims(): Tile {
        val short = value(Dials.SHORT_TRIM)
        val long = value(Dials.LONG_TRIM)
        val text = "${signed(short)} / ${signed(long)}"
        val off = (short != null && abs(short) > 15f) || (long != null && abs(long) > 10f)
        return Tile(text, "Trim % short/long", if (off) Palette.WARN else Palette.VALUE)
    }

    private fun battery(): Tile {
        val v = value(Dials.BATTERY) ?: return Tile(DASH, "Battery V")
        return Tile("%.1f".format(v), "Battery V", if (v < 11.8f || v > 15.2f) Palette.ALARM else Palette.VALUE)
    }

    private fun coolant(): Tile {
        val v = value(Dials.COOLANT) ?: return Tile(DASH, "Coolant °C")
        return when {
            v >= 108f -> Tile("%.0f".format(v), "Coolant °C", Palette.ALARM)
            v < 60f -> Tile("%.0f".format(v), "Coolant °C · cold", Palette.WARN)
            else -> Tile("%.0f".format(v), "Coolant °C")
        }
    }

    private fun intake(): Tile {
        val v = value(Dials.INTAKE) ?: return Tile(DASH, "Intake °C")
        return Tile("%.0f".format(v), "Intake °C", if (v >= 60f) Palette.WARN else Palette.VALUE)
    }

    private fun misfires(): Tile {
        val counts = Dials.MISFIRES.map { value(it) }
        val text = counts.joinToString(" ") { it?.let { c -> "%.0f".format(c) } ?: DASH }
        val any = counts.any { it != null && it > 0f }
        return Tile(text, "Misfires cyl 1–4", if (any) Palette.WARN else Palette.VALUE)
    }

    private fun cams(): Tile {
        val (ir, il, er, el) = Dials.CAMS.map { value(it) }
        return Tile(listOf("In ${whole(ir)}/${whole(il)}", "Ex ${whole(er)}/${whole(el)}"), "Cam advance ° R/L")
    }

    // ---- formatting --------------------------------------------------------------------

    private fun value(dial: DialDef): Float? = values[dial.metric.id]

    private fun signed(v: Float?): String = v?.let { "%+.1f".format(it) } ?: DASH

    private fun whole(v: Float?): String = v?.let { "%.0f".format(it) } ?: DASH

    private companion object {
        const val DASH = "—"
        const val IAM_CAPTION = "IAM · 1 = no knock"
    }
}
