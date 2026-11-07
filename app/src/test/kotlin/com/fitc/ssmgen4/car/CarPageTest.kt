package com.fitc.ssmgen4.car

import com.fitc.ssmgen4.data.TroubleCodes
import com.fitc.ssmgen4.ssm.Dials
import com.fitc.ssmgen4.ssm.Ssm
import com.fitc.ssmgen4.ssm.SsmParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPageTest {

    @Test
    fun everyPageIsOneRequestPerPoll() {
        for (page in CarPage.entries) {
            val addresses = Dials.CAR
                .filter { it.metric.id in page.focus }
                .flatMap { d -> d.needs.flatMap { it.addresses.toList() } }
                .distinct()
            assertTrue("$page polls ${addresses.size} addresses", addresses.size <= Ssm.MAX_ADDRESSES_PER_REQUEST)
        }
    }

    @Test
    fun everyFocusedIdIsSomethingTheSourceCanRead() {
        val known = Dials.CAR.map { it.metric.id }.toSet() + TroubleCodes.ID
        for (page in CarPage.entries) {
            assertTrue("$page asks for ${page.focus - known}", known.containsAll(page.focus))
        }
    }

    @Test
    fun theTripAndTheGearKeepGoingOnEveryPage() {
        for (page in CarPage.entries) {
            for (dial in listOf(Dials.SPEED, Dials.MAF, Dials.RPM, Dials.CLUTCH, Dials.NEUTRAL)) {
                assertTrue("$page lacks ${dial.metric.id}", dial.metric.id in page.focus)
            }
        }
    }

    @Test
    fun neutralIsTheTopBitOf0x62() {
        assertEquals(1.0, SsmParameters.NEUTRAL.value(byteArrayOf(0x80.toByte()), 0), 0.0)
        assertEquals(0.0, SsmParameters.NEUTRAL.value(byteArrayOf(0x02), 0), 0.0) // the A/C switch bit
        assertEquals(SsmParameters.AC_SWITCH.address, SsmParameters.NEUTRAL.address)
    }

    @Test
    fun switchesReadTheirOwnBit() {
        // Clutch and brake share byte 0x121: bits 7 and 6.
        assertEquals(1.0, SsmParameters.CLUTCH.value(byteArrayOf(0x80.toByte()), 0), 0.0)
        assertEquals(0.0, SsmParameters.BRAKE.value(byteArrayOf(0x80.toByte()), 0), 0.0)
        assertEquals(1.0, SsmParameters.BRAKE.value(byteArrayOf(0x40), 0), 0.0)
        assertEquals(SsmParameters.CLUTCH.address, SsmParameters.BRAKE.address)
    }

    @Test
    fun theSweepValuesConvertAsTheReportShowed() {
        // Raw bytes from sweep-20261101-103543: IAM 08, A/F learning 82, cams 32/33.
        assertEquals(0.5, SsmParameters.IAM.value(byteArrayOf(0x08), 0), 1e-9)
        assertEquals(1.5625, SsmParameters.AF_LEARNING.value(byteArrayOf(0x82.toByte()), 0), 1e-9)
        assertEquals(0.0, SsmParameters.INTAKE_CAM_RIGHT.value(byteArrayOf(0x32), 0), 1e-9)
        assertEquals(1.0, SsmParameters.EXHAUST_CAM_RIGHT.value(byteArrayOf(0x33), 0), 1e-9)
    }
}
