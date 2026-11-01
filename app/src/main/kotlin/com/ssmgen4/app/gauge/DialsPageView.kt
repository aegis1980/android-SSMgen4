package com.ssmgen4.app.gauge

import android.content.Context
import android.widget.LinearLayout
import com.ssmgen4.app.data.GearEstimator
import com.ssmgen4.app.data.Metric

/**
 * The gauges page: the dials two by two, with the shift pattern beside them. The grid suits
 * the short, wide area Android Auto leaves under its own buttons better than a single row.
 */
class DialsPageView(context: Context, metrics: List<Metric>) : LinearLayout(context) {

    private val gauges: Map<String, GaugeView>
    private val shift = ShiftPatternView(context)

    init {
        orientation = HORIZONTAL
        val views = metrics.map { GaugeView(context, it) }
        val grid = LinearLayout(context).apply { orientation = VERTICAL }
        for (pair in views.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (gauge in pair) row.addView(gauge, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(grid, LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))
        addView(shift, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.2f))
        gauges = metrics.map { it.id }.zip(views).toMap()
    }

    /** Ids not present are left showing their last value. */
    fun update(readings: Map<String, Float>) {
        readings.forEach { (id, value) -> gauges[id]?.setValue(value) }
    }

    fun setGear(reading: GearEstimator.Reading) {
        shift.show(reading)
    }

    fun resetPeaks() {
        gauges.values.forEach(GaugeView::resetPeak)
    }
}
