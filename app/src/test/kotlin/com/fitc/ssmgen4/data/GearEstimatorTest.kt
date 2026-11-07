package com.fitc.ssmgen4.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearEstimatorTest {

    /** rpm per km/h in [gear] on tyres of [circumference] metres, by the real TY856WBDAA gearing. */
    private fun ratio(gear: Int, circumference: Double) =
        GearEstimator.GEARBOX[gear - 1] * GearEstimator.FINAL_DRIVE * 1000 / (60 * circumference)

    private class Drive(val gears: GearEstimator) {
        var now = 0L
        var last: GearEstimator.Reading? = null

        /** [seconds] steady at [kmh] with the engine at [rpmPerKmh], ten readings a second. */
        fun hold(rpmPerKmh: Double, kmh: Double, seconds: Int): GearEstimator.Reading {
            repeat(seconds * 10) {
                last = gears.sample(now, rpmPerKmh * kmh, kmh, clutch = false, neutral = false)
                now += 100
            }
            return last!!
        }

        fun clutch(): GearEstimator.Reading {
            now += 100
            return gears.sample(now, 2000.0, 50.0, clutch = true, neutral = false).also { last = it }
        }
    }

    @Test
    fun theStartingPointIsTheFitted215_45R18() {
        assertEquals(2.024, GearEstimator.DEFAULT_CIRCUMFERENCE_M, 0.001)
        // The window is narrower than the closest pair of gears, so one gear at most fits.
        val window = GearEstimator.MAX_CIRCUMFERENCE_M / GearEstimator.MIN_CIRCUMFERENCE_M
        assertTrue(window < GearEstimator.GEARBOX[3] / GearEstimator.GEARBOX[4])
    }

    @Test
    fun everyGearReadsRightFromTheFirstMetreAcrossTheWindow() {
        // Worn, nominal and new, plus speedo calibration either way.
        for (tyre in listOf(1.89, 1.98, 2.024, 2.044, 2.10, 2.16)) {
            for (gear in 1..6) {
                val fresh = GearEstimator()
                val kmh = 15.0 * gear + 10
                val r = fresh.sample(0, ratio(gear, tyre) * kmh, kmh, clutch = false, neutral = false)
                assertEquals("gear $gear on a $tyre m tyre", gear, r.gear)
            }
        }
    }

    @Test
    fun steadyDrivingMeasuresTheTyre() {
        val d = Drive(GearEstimator())
        d.hold(ratio(4, 2.044), kmh = 70.0, seconds = 10)
        assertEquals(2.044, d.gears.circumferenceM, 0.001)
        assertTrue(d.gears.measurements > 50)
        // Any gear will do, and the measurement agrees.
        d.hold(ratio(6, 2.044), kmh = 100.0, seconds = 10)
        assertEquals(2.044, d.gears.circumferenceM, 0.001)
        assertEquals(6, d.last!!.gear)
    }

    @Test
    fun aRatioBetweenGearsTeachesNothing() {
        val d = Drive(GearEstimator())
        // Halfway between 1st and 2nd: no gear, and no plausible tyre fits it.
        val between = (ratio(1, 2.0) + ratio(2, 2.0)) / 2
        val r = d.hold(between, kmh = 60.0, seconds = 10)
        assertNull(r.gear)
        assertEquals(0, d.gears.measurements)
        assertEquals(GearEstimator.DEFAULT_CIRCUMFERENCE_M, d.gears.circumferenceM, 0.0)
    }

    @Test
    fun unsteadyReadingsTeachNothing() {
        val g = GearEstimator()
        // Wheelspin or a slipping clutch: the ratio wanders by more than the steady band.
        repeat(200) { i -> g.sample(i * 100L, (if (i % 2 == 0) 3000.0 else 3400.0), 60.0, clutch = false, neutral = false) }
        assertEquals(0, g.measurements)
    }

    @Test
    fun neutralComesFromTheSwitch() {
        val d = Drive(GearEstimator())
        d.hold(ratio(3, 1.98), kmh = 50.0, seconds = 1)
        d.now += 100
        val r = d.gears.sample(d.now, 800.0, 40.0, clutch = false, neutral = true)
        assertTrue(r.neutral)
        assertNull(r.gear)
    }

    @Test
    fun theClutchHoldsTheGearBeingLeft() {
        val d = Drive(GearEstimator())
        d.hold(ratio(3, 1.98), kmh = 50.0, seconds = 1)
        val r = d.clutch()
        assertTrue(r.clutch)
        assertEquals(3, r.gear)
        assertEquals(4, d.hold(ratio(4, 1.98), kmh = 50.0, seconds = 1).gear)
    }

    @Test
    fun reverseReadsAsFirst() {
        val reverse = 3.545 * GearEstimator.FINAL_DRIVE * 1000 / (60 * 1.98)
        assertEquals(1, GearEstimator().sample(0, reverse * 8, 8.0, clutch = false, neutral = false).gear)
    }

    @Test
    fun crawlingKeepsTheLastGearForAMoment() {
        val d = Drive(GearEstimator())
        d.hold(ratio(1, 1.98), kmh = 12.0, seconds = 1)
        d.now += 100
        assertEquals(1, d.gears.sample(d.now, 900.0, 3.0, clutch = false, neutral = false).gear)
        d.now += 5_000
        assertNull(d.gears.sample(d.now, 900.0, 3.0, clutch = false, neutral = false).gear)
    }

    @Test
    fun aSavedMeasurementCarriesOver() {
        val g = GearEstimator(circumferenceM = 2.044, measurements = 300)
        assertEquals(5, g.sample(0, ratio(5, 2.044) * 90, 90.0, clutch = false, neutral = false).gear)
        assertEquals(2.044, g.circumferenceM, 0.0)
    }
}
