package com.fitc.ssmgen4.defs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/** Parses the bundled RomRaider definition the sweep relies on, for the car's ECU id. */
class LoggerDefsTest {

    companion object {
        private const val ECU = "4B04785007"
        private lateinit var defs: LoggerDefs

        @BeforeClass
        @JvmStatic
        fun load() {
            val file = File("src/main/assets/" + LoggerDefs.ASSET)
            defs = file.inputStream().use { LoggerDefs.parse(it, ECU, KXmlParser()) }
            println(
                "standard=${defs.standard.size} extended=${defs.extended.size}/${defs.extendedTotal} " +
                    "dependent=${defs.dependent.size} switches=${defs.switches.size} dtcs=${defs.dtcs.size}",
            )
        }
    }

    @Test
    fun countsMatchTheFile() {
        assertEquals(235, defs.standard.size)
        assertEquals(56, defs.extended.size)
        // SSM section only; the file's other 341 ecuparams belong to the DS2 and NCS protocols.
        assertEquals(204, defs.extendedTotal)
        assertEquals(172, defs.switches.size)
        assertEquals(589, defs.dtcs.size)
    }

    @Test
    fun standardParameterShape() {
        val map = defs.byId.getValue("P7")
        assertEquals(0x0D, map.location!!.address)
        assertEquals(8, map.capByte)
        assertEquals(1, map.capBit)
        assertEquals("kPa", map.conversions.first().units)
    }

    @Test
    fun extendedParametersUseThisEcusAddresses() {
        // IAM, 4-byte float: FF2538 for this ROM family, FF2530 for others.
        val iam = defs.extended.firstOrNull { it.location?.address == 0xFF2538 }
        assertNotNull(iam)
        assertEquals(4, iam!!.location!!.length)
        assertEquals("float", iam.storage)
    }

    @Test
    fun boostIsManifoldMinusAtmospheric() {
        val boost = defs.byId.getValue("P202")
        assertEquals(ParamKind.DEPENDENT, boost.kind)
        assertEquals(listOf("P7", "P24"), boost.depends)
        val psi = boost.conversions.first { it.units == "psi" }.compiled!!
        val values = mapOf("[P7:psi]" to 20.0, "[P24:psi]" to 14.5)
        assertEquals(5.5, psi.eval { values[it] }!!, 1e-9)
    }

    @Test
    fun everyConversionExpressionParses() {
        val all = defs.standard + defs.extended + defs.dependent
        val bad = all.flatMap { p -> p.conversions.filter { it.compiled == null }.map { "${p.id} ${it.units}: ${it.expr}" } }
        assertTrue("unparsed expressions:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun exprArithmetic() {
        assertEquals(-45.0 + 0.3515625 * 128, Expr.parse("(x*.3515625)-45")!!.eval { if (it == "x") 128.0 else null }!!, 1e-9)
        assertEquals(-3.0, Expr.parse("-x+2*(1-3)/4*-1")!!.eval { if (it == "x") 4.0 else null }!!, 1e-9)
        assertNull(Expr.parse("x^2"))
        assertNull(Expr.parse("P12*")?.eval { 1.0 })
    }

    /** Android's parser chokes on the internal DTD (kxml2 here does not), so it must be gone. */
    @Test
    fun doctypeIsStrippedBeforeParsing() {
        val text = File("src/main/assets/" + LoggerDefs.ASSET).readText()
        val stripped = LoggerDefs.withoutDoctype(text)
        assertTrue(text.contains("<!DOCTYPE"))
        assertTrue(!stripped.contains("<!DOCTYPE") && !stripped.contains("<!ATTLIST") && !stripped.contains("<!ELEMENT"))
        assertTrue(stripped.contains("<logger version=\"370\">"))
        assertEquals(text.length - stripped.length, text.substring(text.indexOf("<!DOCTYPE"), text.indexOf("]>") + 2).length)
        assertEquals("<a/>", LoggerDefs.withoutDoctype("<!DOCTYPE a><a/>"))
        assertEquals("<a/>", LoggerDefs.withoutDoctype("<a/>"))
    }

    @Test
    fun decodeStorageTypes() {
        val four = Location(0xFF0000, 4, null)
        assertEquals(1.0, Decode.raw(byteArrayOf(0x3F, 0x80.toByte(), 0, 0), four, "float"), 0.0)
        assertEquals(1065353216.0, Decode.raw(byteArrayOf(0x3F, 0x80.toByte(), 0, 0), four, null), 0.0)
        assertEquals(-1.0, Decode.raw(byteArrayOf(0xFF.toByte()), Location(0x10, 1, null), "int8"), 0.0)
        assertEquals(255.0, Decode.raw(byteArrayOf(0xFF.toByte()), Location(0x10, 1, null), null), 0.0)
        assertEquals(1.0, Decode.raw(byteArrayOf(0x08), Location(0x10, 1, 3), null), 0.0)
        assertEquals(0.0, Decode.raw(byteArrayOf(0x08), Location(0x10, 1, 2), null), 0.0)
    }
}
