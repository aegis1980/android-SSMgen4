package com.fitc.ssmgen4.gauge

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.fitc.ssmgen4.data.TripComputer

/**
 * The trip page: fuel economy now and on average, fuel used, distance, time and average
 * speed, as six tiles. Numbers come from [TripComputer]; this only draws them.
 */
class TripPageView(context: Context) : View(context) {

    private val painter = TilePainter(resources.displayMetrics.density)
    private val gap = 6f * resources.displayMetrics.density
    private var trip: TripComputer? = null

    fun show(trip: TripComputer) {
        this.trip = trip
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = trip ?: return
        if (width <= 0 || height <= 0) return

        val now = t.litresPer100Km
        val tiles = listOf(
            if (now != null) Tile("%.1f".format(now), "L/100 km now", Palette.LIT)
            else Tile("%.1f".format(t.litresPerHour), "L/h now", Palette.LIT),
            Tile(t.averageLitresPer100Km?.let { "%.1f".format(it) } ?: DASH, "L/100 km average"),
            Tile("%.2f".format(t.fuelLitres), "Litres used"),
            Tile("%.1f".format(t.distanceKm), "Km"),
            Tile(duration(t.elapsedMs), "Trip time"),
            Tile(t.averageKmh?.let { "%.0f".format(it) } ?: DASH, "Average km/h"),
        )

        val columns = 3
        val rows = 2
        val w = (width - gap * (columns + 1)) / columns
        val h = (height - gap * (rows + 1)) / rows
        val captionSize = painter.captionSize(tiles.map { it.caption }, w, h)
        tiles.forEachIndexed { i, tile ->
            val left = gap + (i % columns) * (w + gap)
            val top = gap + (i / columns) * (h + gap)
            painter.draw(canvas, painter.rect(left, top, left + w, top + h), listOf(tile.value), tile.caption, tile.colour, captionSize)
        }
    }

    private class Tile(val value: String, val caption: String, val colour: Int = Palette.VALUE)

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return "%d:%02d".format(minutes / 60, minutes % 60)
    }

    private companion object {
        const val DASH = "—"
    }
}
