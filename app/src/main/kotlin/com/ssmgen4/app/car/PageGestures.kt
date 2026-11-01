package com.ssmgen4.app.car

import kotlin.math.abs

/**
 * Turns the host's surface gestures into page turns: +1 for the next page, -1 for the
 * previous one, 0 for nothing.
 *
 * Android Auto passes drags and flings in `GestureDetector` terms: a scroll distance is the
 * finger's travel reversed (positive when it moves left), a fling velocity is the finger's
 * own direction (negative when it moves left). Moving the finger left brings in the next
 * page, as on a phone. A drag turns the page once it has covered a quarter of the screen;
 * the fling that usually ends the same drag is then ignored by [cooldownMs].
 */
class PageGestures(private val cooldownMs: Long = 500L) {

    private var dragX = 0f
    private var lastDragMs = 0L
    private var lastTurnMs = -cooldownMs

    fun onScroll(nowMs: Long, distanceX: Float, width: Int): Int {
        if (nowMs - lastDragMs > DRAG_GAP_MS) dragX = 0f
        lastDragMs = nowMs
        dragX += distanceX
        if (width <= 0 || abs(dragX) < width * DRAG_FRACTION) return 0
        val direction = if (dragX > 0) 1 else -1
        dragX = 0f
        return turn(nowMs, direction)
    }

    fun onFling(nowMs: Long, velocityX: Float, velocityY: Float): Int {
        if (abs(velocityX) < MIN_FLING || abs(velocityX) < abs(velocityY)) return 0
        dragX = 0f
        return turn(nowMs, if (velocityX < 0) 1 else -1)
    }

    /** Taps near the left or right edge step back or on; the middle is left alone. */
    fun onClick(nowMs: Long, x: Float, width: Int): Int = when {
        width <= 0 -> 0
        x < width * EDGE_FRACTION -> turn(nowMs, -1)
        x > width * (1 - EDGE_FRACTION) -> turn(nowMs, 1)
        else -> 0
    }

    private fun turn(nowMs: Long, direction: Int): Int {
        if (nowMs - lastTurnMs < cooldownMs) return 0
        lastTurnMs = nowMs
        return direction
    }

    private companion object {
        /** Scroll events further apart than this belong to different drags. */
        const val DRAG_GAP_MS = 300L
        const val DRAG_FRACTION = 0.25f
        /** Pixels per second; slower than this is a drag that stopped, not a swipe. */
        const val MIN_FLING = 500f
        const val EDGE_FRACTION = 0.2f
    }
}
