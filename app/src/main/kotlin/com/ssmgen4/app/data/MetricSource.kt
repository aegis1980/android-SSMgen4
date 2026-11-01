package com.ssmgen4.app.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.sin

/**
 * A value the dashboard can show. Ranges are display ranges, not sensor limits:
 * they decide where the needle sits, so they are deliberately round numbers.
 */
data class Metric(
    val id: String,
    val label: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val decimals: Int = 1,
    /** Readings at or beyond this turn the gauge red. Null means no alarm. */
    val alarmAbove: Float? = null,
)

/**
 * Where gauge values come from. The car UI only ever talks to this, so a simulated
 * source and a real SSM link are interchangeable.
 */
interface MetricSource {

    val metrics: List<Metric>

    /**
     * Begin producing readings. All callbacks arrive on the main looper. [onReadings]
     * carries metric-id to value and may cover a subset of [metrics]; [onStatus] is a short
     * human-readable line for the strip under the dials; [onCodes] reports trouble codes
     * when [focus] asks for them.
     */
    fun start(
        onReadings: (Map<String, Float>) -> Unit,
        onStatus: (String) -> Unit = {},
        onCodes: (TroubleCodes) -> Unit = {},
    )

    fun stop()

    /**
     * Read only the metrics in [ids], so a page polls what it shows and no more; null reads
     * them all. Including [TroubleCodes.ID] adds a trouble-code scan every so often. Takes
     * effect on the next poll, and may be called before [start].
     */
    fun focus(ids: Set<String>?) {}
}

/**
 * Plausible-looking movement with no car attached, for developing against the Desktop
 * Head Unit. Swap for [SsmMetricSource] in `SsmSession` to talk to the real thing.
 *
 * Every metric sweeps its display range at its own pace and switches blink, except that the
 * headline ones follow one shared pull of the throttle so the dials move together.
 */
class SimulatedMetricSource(
    override val metrics: List<Metric>,
    private val statusText: String = "Simulated data",
) : MetricSource {

    private val ids = metrics.mapTo(HashSet()) { it.id }
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var tick: Runnable? = null

    override fun start(
        onReadings: (Map<String, Float>) -> Unit,
        onStatus: (String) -> Unit,
        onCodes: (TroubleCodes) -> Unit,
    ) {
        stop()
        startedAt = SystemClock.elapsedRealtime()
        onStatus(statusText)
        onCodes(TroubleCodes(emptyList(), emptyList()))
        val runnable = object : Runnable {
            override fun run() {
                onReadings(sample())
                handler.postDelayed(this, FRAME_MS)
            }
        }
        tick = runnable
        handler.post(runnable)
    }

    override fun stop() {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
    }

    /** One synthetic pull of the throttle every ~8 seconds. */
    private fun sample(): Map<String, Float> {
        val t = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        val pull = ((sin(t * 0.8f) + 1f) / 2f)
        val out = HashMap<String, Float>(metrics.size)
        metrics.forEachIndexed { i, m ->
            val wave = (sin(t * 0.3f + i) + 1f) / 2f
            out[m.id] = if (m.min == 0f && m.max == 1f && m.decimals == 0) {
                if (wave > 0.6f) 1f else 0f
            } else {
                m.min + (m.max - m.min) * wave
            }
        }
        out["RPM"] = 800f + pull * 5800f
        out["BOOST"] = -12f + pull * 30f
        out["KMH"] = pull * 160f
        out["MAF"] = 3f + pull * 120f
        out["MCT"] = 82f + sin(t * 0.07f) * 8f
        out["S155"] = 0f
        out["IAM"] = 1f
        return out.filterKeys { it in ids }
    }

    private companion object {
        const val FRAME_MS = 50L // 20 Hz; the head unit will not show more than this usefully
    }
}
