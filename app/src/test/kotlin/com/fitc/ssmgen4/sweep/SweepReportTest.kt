package com.fitc.ssmgen4.sweep

import com.fitc.ssmgen4.defs.LoggerDefs
import com.fitc.ssmgen4.ssm.EcuInitResponse
import com.fitc.ssmgen4.ssm.Ssm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** Runs the report over a synthetic sweep, so formatting and evaluation fail here, not in the car. */
class SweepReportTest {

    private val defs = File("src/main/assets/" + LoggerDefs.ASSET).inputStream().use {
        LoggerDefs.parse(it, "4B04785007", KXmlParser())
    }

    /**
     * [bitmap] fills the capability bytes (-1 = everything advertised). [fill] overrides
     * the byte read at an address in every sample, e.g. 0xFF filler.
     */
    private fun result(bitmap: Byte = -1, fill: Map<Int, Byte> = emptyMap()): SweepResult {
        // SSM id, ECU id, then the capability bitmap.
        val init = EcuInitResponse(byteArrayOf(0xA2.toByte(), 0x10, 0x0F, 0x4B, 0x04, 0x78, 0x50, 0x07) + ByteArray(48) { bitmap })
        val ecu = DeviceResult(
            name = "ECU", device = Ssm.DEVICE_ECU, init = init,
            params = defs.standard.filter { it.forEcu } + defs.extended,
            switches = defs.switches.filter { it.target and 1 != 0 },
            dtcBytes = defs.dtcs.flatMap { listOf(it.tmpAddress, it.memAddress) },
        )
        val all = (ecu.params.flatMap { it.location!!.addresses.toList() } + ecu.switches.map { it.address } + ecu.dtcBytes).distinct()
        val refused = (0xFF6974..0xFF6977).toSet()
        val silent = setOf(0x000061)
        for (a in all) {
            when (a) {
                in refused -> ecu.book.refused += a
                in silent -> ecu.book.silent += a
                else -> ecu.book.ok[a] = 0
            }
        }
        val random = Random(42)
        repeat(4) { i ->
            val sample = HashMap<Int, Byte>()
            for (a in ecu.book.ok.keys) sample[a] = random.nextInt(256).toByte()
            sample[0x0D] = (150 + i).toByte() // manifold absolute, kPa
            sample[0x23] = 100 // atmospheric, kPa
            for ((a, b) in fill) if (a in sample) sample[a] = b
            // Baseline with one sample, then brake / clutch / headlights.
            if (i == 1) sample[0x29] = 0 // pedal stays put
            ecu.samples += sample
            ecu.sampleSteps += i
        }
        ecu.sampleMs = 4000
        return SweepResult("OBDLink LX", "00:04:3E:6F:57:51", "STN1155 v5.6.19", defs, listOf(ecu), 95_000, 1)
    }

    @Test
    fun writesReportCsvAndTrace() {
        val report = SweepReport(result())
        val dir = Files.createTempDirectory("sweep").toFile()
        val files = report.write(dir, "20261031-190000", listOf("trace line"))

        assertEquals(4, files.size)
        assertTrue(files[2].name.endsWith("-raw.csv"))
        assertTrue(files[2].readLines().size > 500)
        val text = files[0].readText()
        println(text.lines().take(40).joinToString("\n"))
        assertTrue(text.contains("=== ECU standard"))
        assertTrue(text.contains("=== ECU extended"))
        assertTrue(text.contains("=== Calculated"))
        assertTrue(text.contains("=== ECU switches"))
        assertTrue(text.contains("Trouble codes"))
        assertTrue(text.contains("engine speed"))
        assertTrue(text.contains("=== Guided steps"))
        assertTrue(text.contains("2. Brake (1 samples)"))
        assertTrue(text.contains("switches: "))

        val csvRows = files[1].readLines()
        assertEquals(1 + report.rows.size + report.switchRows.size, csvRows.size)
        println(report.summary())
    }

    @Test
    fun boostIsComputedFromItsInputs() {
        val report = SweepReport(result())
        val boost = report.rows.first { it.param.id == "P202" }
        assertEquals(Status.OK, boost.status)
        assertEquals(4, boost.values.size)
        // Default conversion is bar: (MAP - atmospheric) kPa / 100.
        assertEquals("bar", boost.units)
        assertEquals(0.50, boost.values[0], 1e-6)
        assertEquals(0.53, boost.values[3], 1e-6)
        assertTrue(boost.live)
    }

    /** What ECU 4B04785007 actually does: unadvertised addresses and unused code bytes read FF. */
    @Test
    fun ffFillerIsUnsupportedNotReadable() {
        val ff: Byte = -1
        val dtcBytes = defs.dtcs.flatMap { listOf(it.tmpAddress, it.memAddress) }.toSet()
        val fill = (dtcBytes + 0x0B + 0x197).associateWith { ff }
        // Bitmap 0x02 everywhere: only bit 1 of each byte advertised (P7 is byte 8 bit 1; P5 is not).
        val report = SweepReport(result(bitmap = 0x02, fill = fill))

        val p5 = report.rows.first { it.param.id == "P5" }
        assertEquals(false, p5.advertised)
        assertEquals(Status.UNSUPPORTED, p5.status)
        assertEquals(Status.OK, report.rows.first { it.param.id == "P7" }.status)

        assertTrue(report.dtcRows.isEmpty())
        assertEquals(dtcBytes.size, report.dtcFillerBytes.size)

        // S158 is bit 7 of 0x197 with capability byte 57: not advertised under this bitmap.
        val s158 = report.switchRows.first { it.switch.id == "S158" }
        assertEquals(false, s158.supported)
        assertEquals(Status.UNSUPPORTED, s158.status)
        println(report.summary())
    }

    @Test
    fun refusedAndSilentAddressesAreReported() {
        val report = SweepReport(result())
        val refused = report.rows.filter { r -> r.param.location?.address == 0xFF6974 }
        assertTrue(refused.isNotEmpty())
        assertTrue(refused.all { it.status == Status.REFUSED })
        val silentSwitches = report.switchRows.filter { it.switch.address == 0x000061 }
        assertTrue(silentSwitches.all { it.status == Status.SILENT })
        val atmospheric = report.rows.first { it.param.id == "P24" }
        assertTrue("constant" in atmospheric.flags)
    }
}
