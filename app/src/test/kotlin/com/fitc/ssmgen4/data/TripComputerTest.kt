package com.fitc.ssmgen4.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TripComputerTest {

    /** Feeds [seconds] of steady readings, one a second, starting at [fromMs]. */
    private fun TripComputer.steady(fromMs: Long, seconds: Int, kmh: Double, maf: Double): Long {
        var t = fromMs
        repeat(seconds + 1) {
            sample(t, kmh, maf)
            t += 1000
        }
        return t - 1000
    }

    private fun litresPerHour(maf: Double) = maf * 3600 / (TripComputer.STOICHIOMETRIC * TripComputer.PETROL_G_PER_L)

    @Test
    fun idlingBurnsFuelButGoesNowhere() {
        val trip = TripComputer()
        trip.steady(0, 3600, kmh = 0.0, maf = 2.45) // the idle airflow the sweep measured
        assertEquals(litresPerHour(2.45), trip.fuelLitres, 1e-6)
        assertEquals(0.0, trip.distanceKm, 0.0)
        assertNull("no L/100 km while stopped", trip.litresPer100Km)
        assertNull(trip.averageLitresPer100Km)
        assertEquals(litresPerHour(2.45), trip.litresPerHour, 1e-6)
    }

    @Test
    fun cruisingAddsUp() {
        val trip = TripComputer()
        trip.steady(0, 1800, kmh = 100.0, maf = 20.0)
        assertEquals(50.0, trip.distanceKm, 1e-6)
        assertEquals(litresPerHour(20.0) / 2, trip.fuelLitres, 1e-6)
        val expected = litresPerHour(20.0)
        assertEquals(expected, trip.averageLitresPer100Km!!, 1e-6)
        assertEquals(expected, trip.litresPer100Km!!, 1e-3)
        assertEquals(100.0, trip.averageKmh!!, 1e-6)
        assertEquals(1_800_000L, trip.elapsedMs)
    }

    @Test
    fun aGapInTheDataAddsNothing() {
        val trip = TripComputer()
        val end = trip.steady(0, 60, kmh = 50.0, maf = 10.0)
        val before = trip.distanceKm
        // Bluetooth drops for ten seconds, then readings resume.
        trip.sample(end + 10_000, 50.0, 10.0)
        assertEquals(before, trip.distanceKm, 0.0)
        trip.sample(end + 11_000, 50.0, 10.0)
        assertEquals(before + 50.0 / 3600, trip.distanceKm, 1e-9)
    }

    @Test
    fun calibrationScalesFuelOnly() {
        val trip = TripComputer(calibration = 1.2)
        trip.steady(0, 360, kmh = 60.0, maf = 15.0)
        assertEquals(6.0, trip.distanceKm, 1e-6)
        assertEquals(litresPerHour(15.0) * 1.2 / 10, trip.fuelLitres, 1e-6)
    }

    @Test
    fun resetStartsAgainButKeepsCounting() {
        val trip = TripComputer(distanceKm = 120.0, fuelLitres = 9.0, elapsedMs = 7_200_000)
        assertNotNull(trip.averageLitresPer100Km)
        trip.reset()
        assertEquals(0.0, trip.distanceKm, 0.0)
        assertEquals(0.0, trip.fuelLitres, 0.0)
        assertEquals(0L, trip.elapsedMs)
        trip.steady(0, 36, kmh = 100.0, maf = 20.0)
        assertEquals(1.0, trip.distanceKm, 1e-6)
    }
}
