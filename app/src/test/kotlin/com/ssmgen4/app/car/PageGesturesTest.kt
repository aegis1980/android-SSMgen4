package com.ssmgen4.app.car

import org.junit.Assert.assertEquals
import org.junit.Test

class PageGesturesTest {

    @Test
    fun flingLeftIsNextAndRightIsPrevious() {
        val g = PageGestures()
        assertEquals(1, g.onFling(1_000, velocityX = -2000f, velocityY = 100f))
        assertEquals(-1, g.onFling(2_000, velocityX = 2000f, velocityY = 100f))
    }

    @Test
    fun slowOrMostlyVerticalFlingsDoNothing() {
        val g = PageGestures()
        assertEquals(0, g.onFling(1_000, velocityX = -200f, velocityY = 0f))
        assertEquals(0, g.onFling(2_000, velocityX = -2000f, velocityY = 3000f))
    }

    @Test
    fun aQuarterScreenDragTurnsOnceAndItsFlingIsIgnored() {
        val g = PageGestures()
        val width = 800
        // Finger moving left: GestureDetector-style distances are positive.
        assertEquals(0, g.onScroll(1_000, 60f, width))
        assertEquals(0, g.onScroll(1_016, 60f, width))
        assertEquals(0, g.onScroll(1_032, 60f, width))
        assertEquals(1, g.onScroll(1_048, 60f, width))
        assertEquals(0, g.onScroll(1_064, 60f, width))
        assertEquals("the drag's own fling", 0, g.onFling(1_080, velocityX = -1500f, velocityY = 0f))
    }

    @Test
    fun separateDragsDoNotAddUp() {
        val g = PageGestures()
        assertEquals(0, g.onScroll(1_000, 150f, 800))
        assertEquals(0, g.onScroll(2_000, 150f, 800))
    }

    @Test
    fun edgeTapsStepAndMiddleTapsDoNot() {
        val g = PageGestures()
        assertEquals(-1, g.onClick(1_000, 40f, 800))
        assertEquals(1, g.onClick(2_000, 760f, 800))
        assertEquals(0, g.onClick(3_000, 400f, 800))
    }

    @Test
    fun pagesWrapRound() {
        assertEquals(CarPage.STUFF, CarPage.TRIP.step(-1))
        assertEquals(CarPage.TRIP, CarPage.STUFF.step(1))
        assertEquals(CarPage.STUFF, CarPage.GAUGES.step(1))
    }
}
