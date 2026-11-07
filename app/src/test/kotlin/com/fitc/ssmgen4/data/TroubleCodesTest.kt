package com.fitc.ssmgen4.data

import com.fitc.ssmgen4.defs.LoggerDefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

class TroubleCodesTest {

    private val dtcs = File("src/main/assets/" + LoggerDefs.ASSET).inputStream().use {
        LoggerDefs.parse(it, "4B04785007", KXmlParser())
    }.dtcs

    private fun allClear(): HashMap<Int, Byte> =
        dtcs.flatMap { listOf(it.tmpAddress, it.memAddress) }.associateWithTo(HashMap()) { 0.toByte() }

    @Test
    fun nothingSetMeansNoCodes() {
        val codes = TroubleCodes.decode(dtcs, allClear())
        assertTrue(codes.current.isEmpty())
        assertTrue(codes.memorised.isEmpty())
    }

    @Test
    fun aSetBitIsReportedByName() {
        val thermostat = dtcs.first { it.name.startsWith("P0128 ") }
        val bytes = allClear()
        bytes[thermostat.memAddress] = (1 shl thermostat.bit).toByte()
        val codes = TroubleCodes.decode(dtcs, bytes)
        assertTrue(codes.current.isEmpty())
        assertTrue(thermostat.name in codes.memorised)
        assertEquals(listOf(thermostat.name), codes.all.distinct().filter { it.startsWith("P0128 ") })
    }

    @Test
    fun aByteOfFillerIsNotEightFaults() {
        val any = dtcs.first()
        val bytes = allClear()
        bytes[any.tmpAddress] = 0xFF.toByte()
        val codes = TroubleCodes.decode(dtcs, bytes)
        assertTrue("codes in a 0xFF byte are skipped", codes.current.isEmpty())
    }
}
