package com.ssmgen4.app.sweep

import com.ssmgen4.app.defs.Conversion
import com.ssmgen4.app.defs.Decode
import com.ssmgen4.app.defs.LogParam
import com.ssmgen4.app.defs.LoggerDefs
import com.ssmgen4.app.defs.ParamKind
import com.ssmgen4.app.defs.SwitchDef
import com.ssmgen4.app.ssm.Ssm
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * [UNSUPPORTED] means the ECU answered, but with filler: an address it does not advertise
 * that reads 0xFF every time, a switch whose capability bit is clear, or a calculated value
 * built from such inputs. Measured on ECU 4B04785007, every unadvertised address reads FF.
 */
enum class Status(val label: String) {
    OK("OK"), REFUSED("refused"), SILENT("silent"), NO_INPUTS("no inputs"), UNSUPPORTED("unsupported"),
}

/** One parameter on one device, judged over every sample taken. */
class ParamRow(
    val section: String,
    val deviceName: String,
    val param: LogParam,
    val advertised: Boolean?,
    val status: Status,
    val values: List<Double>,
    val rawLast: String,
    val conversion: Conversion?,
    val flags: List<String>,
) {
    val units: String get() = conversion?.units ?: ""
    val decimals: Int get() = (conversion?.decimals ?: 2).coerceIn(0, 4)
    val last: Double? get() = values.lastOrNull()
    val min: Double? get() = values.filter { it.isFinite() }.minOrNull()
    val max: Double? get() = values.filter { it.isFinite() }.maxOrNull()
    val live: Boolean get() = "live" in flags
}

class SwitchRow(val deviceName: String, val switch: SwitchDef, val supported: Boolean?, val status: Status, val bits: List<Int>)

class DtcRow(val id: String, val name: String, val current: Boolean, val memorised: Boolean)

/** Lazily decoded values for one sample, so calculated parameters can refer to others. */
private class SampleValues(private val defs: LoggerDefs, private val sample: Map<Int, Byte>) {

    private val cache = HashMap<String, Double?>()
    private val busy = HashSet<String>()

    fun value(id: String, units: String?): Double? {
        val key = id + ":" + (units ?: "")
        if (cache.containsKey(key)) return cache[key]
        if (!busy.add(key)) return null // a definition that refers to itself
        val v = compute(id, units)
        busy.remove(key)
        cache[key] = v
        return v
    }

    fun raw(p: LogParam): ByteArray? {
        val loc = p.location ?: return null
        val out = ByteArray(loc.length)
        for (i in 0 until loc.length) out[i] = sample[loc.address + i] ?: return null
        return out
    }

    private fun compute(id: String, units: String?): Double? {
        val p = defs.byId[id] ?: return null
        val conversion = conversionFor(p, units)
        if (p.kind == ParamKind.DEPENDENT) {
            val expr = conversion?.compiled ?: return null
            return expr.eval(::resolve)
        }
        val bytes = raw(p) ?: return null
        return Decode.convert(conversion, Decode.raw(bytes, p.location!!, p.storage))
    }

    /** `P12` is that parameter's default conversion, `[P7:psi]` the one in psi. */
    private fun resolve(name: String): Double? {
        if (name.startsWith("[") && name.endsWith("]")) {
            val inner = name.substring(1, name.length - 1)
            val colon = inner.indexOf(':')
            return if (colon < 0) value(inner, null) else value(inner.substring(0, colon), inner.substring(colon + 1))
        }
        return value(name, null)
    }

    companion object {
        fun conversionFor(p: LogParam, units: String?): Conversion? =
            units?.let { u -> p.conversions.firstOrNull { it.units == u } } ?: p.conversions.firstOrNull()
    }
}

class SweepReport(private val result: SweepResult) {

    private val defs = result.defs
    private val ecu = result.devices.first()

    private val paramRows: List<ParamRow> by lazy { parameterRows() }
    val rows: List<ParamRow> by lazy { paramRows + calculatedRows() }
    val switchRows: List<SwitchRow> by lazy { switchRows() }
    val dtcRows: List<DtcRow> by lazy { dtcRows() }

    // ---- evaluation --------------------------------------------------------------------

    private fun statusOf(d: DeviceResult, addresses: IntArray): Status = when {
        addresses.any { it in d.book.refused } -> Status.REFUSED
        addresses.all { it in d.book.ok } -> Status.OK
        else -> Status.SILENT
    }

    private fun parameterRows(): List<ParamRow> = result.devices.flatMap { d ->
        val views = d.samples.map { SampleValues(defs, it) }
        d.params.map { p ->
            val loc = p.location!!
            val conversion = SampleValues.conversionFor(p, null)
            val values = views.mapNotNull { it.value(p.id, null) }
            val raws = views.mapNotNull { v -> v.raw(p)?.let(Ssm::hex) }
            val advertised = if (p.capByte != null && p.capBit != null) d.init.supports(p.capByte, p.capBit) else null
            val section = d.name + if (p.kind == ParamKind.EXTENDED) " extended" else " standard"
            val filler = raws.isNotEmpty() && raws.all { r -> r.all { it == 'F' } }
            var status = statusOf(d, loc.addresses)
            if (status == Status.OK && filler && advertised == false) status = Status.UNSUPPORTED
            val flags = flags(values, raws, conversion).let { if (filler && advertised != false) it + "reads FF" else it }
            ParamRow(
                section, d.name, p, advertised, status, values,
                raws.lastOrNull() ?: discoveryRaw(d, loc.addresses),
                conversion, flags,
            )
        }
    }

    /** The bytes seen while probing, for when no sample came back at all. */
    private fun discoveryRaw(d: DeviceResult, addresses: IntArray): String {
        val bytes = addresses.map { d.book.ok[it] ?: return "" }
        return Ssm.hex(bytes.toByteArray())
    }

    private fun calculatedRows(): List<ParamRow> {
        val views = ecu.samples.map { SampleValues(defs, it) }
        // Anything computed from filler is filler too; dependents can chain (P242 uses P200).
        val unusable = paramRows
            .filter { it.deviceName == ecu.name && it.status != Status.OK }
            .mapTo(HashSet()) { it.param.id }
        return defs.dependent.map { p ->
            val conversion = SampleValues.conversionFor(p, null)
            val values = views.mapNotNull { it.value(p.id, null) }
            val status = when {
                p.depends.any { it in unusable } -> Status.UNSUPPORTED
                values.isEmpty() -> Status.NO_INPUTS
                else -> Status.OK
            }
            if (status != Status.OK) unusable += p.id
            ParamRow("Calculated", ecu.name, p, null, status, values, "", conversion, flags(values, null, conversion))
        }
    }

    private fun flags(values: List<Double>, raws: List<String>?, conversion: Conversion?): List<String> {
        if (values.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        if (values.any { it.isNaN() }) out += "not a number"
        if (values.any { it.isInfinite() }) out += "infinite (divides by zero)"
        val finite = values.filter { it.isFinite() }
        val lo = conversion?.gaugeMin
        val hi = conversion?.gaugeMax
        if (lo != null && hi != null && hi > lo) {
            val span = hi - lo
            if (finite.any { it < lo - span || it > hi + span }) out += "outside plausible range"
        } else if (finite.any { abs(it) > 1e7 }) {
            out += "implausibly large"
        }
        val distinct = raws?.distinct()?.size ?: values.distinct().size
        val count = raws?.size ?: values.size
        out += when {
            count < 2 -> "single sample"
            distinct > 1 -> "live"
            raws != null && raws.first().all { it == '0' } -> "always zero"
            raws == null && values.first() == 0.0 -> "always zero"
            else -> "constant"
        }
        return out
    }

    private fun switchRows(): List<SwitchRow> = result.devices.flatMap { d ->
        d.switches.map { s ->
            val bits = d.samples.mapNotNull { sample -> sample[s.address]?.let { (it.toInt() shr s.bit) and 1 } }
            val supported = s.capByte?.let { d.init.supports(it, s.bit) }
            var status = statusOf(d, intArrayOf(s.address))
            if (status == Status.OK && supported == false) status = Status.UNSUPPORTED
            SwitchRow(d.name, s, supported, status, bits)
        }
    }

    /** Trouble-code bytes that read 0xFF: codes this ECU does not have, not 8 faults at once. */
    val dtcFillerBytes: Set<Int> by lazy {
        val last = ecu.samples.lastOrNull() ?: ecu.book.ok
        ecu.dtcBytes.filterTo(HashSet()) { last[it] == FF }
    }

    private fun dtcRows(): List<DtcRow> {
        val last = ecu.samples.lastOrNull() ?: ecu.book.ok
        return defs.dtcs.mapNotNull { dtc ->
            if (dtc.tmpAddress in dtcFillerBytes || dtc.memAddress in dtcFillerBytes) return@mapNotNull null
            val tmp = last[dtc.tmpAddress]
            val mem = last[dtc.memAddress]
            val current = tmp != null && (tmp.toInt() shr dtc.bit) and 1 == 1
            val memorised = mem != null && (mem.toInt() shr dtc.bit) and 1 == 1
            if (current || memorised) DtcRow(dtc.id, dtc.name, current, memorised) else null
        }
    }

    // ---- writing -----------------------------------------------------------------------

    /** Writes report, CSV, raw bytes and trace; the report is first in the returned list. */
    fun write(dir: File, stamp: String, trace: List<String>): List<File> {
        val report = File(dir, "sweep-$stamp.txt").apply { writeText(text(stamp)) }
        val csv = File(dir, "sweep-$stamp.csv").apply { writeText(csv()) }
        val raw = File(dir, "sweep-$stamp-raw.csv").apply { writeText(rawCsv()) }
        val traceFile = File(dir, "sweep-$stamp-trace.txt").apply { writeText(trace.joinToString("\n")) }
        return listOf(report, csv, raw, traceFile)
    }

    /** Every probed address and every sampled byte, so the report can be redone without the car. */
    private fun rawCsv(): String = buildString {
        val samples = result.devices.maxOfOrNull { it.samples.size } ?: 0
        appendLine("device,address,status,probe" + (1..samples).joinToString("") { ",s$it" })
        for (d in result.devices) {
            appendLine("${d.name},step,," + d.sampleSteps.joinToString("") { ",${it + 1}" })
            val all = (d.book.ok.keys + d.book.refused + d.book.silent).sorted()
            for (a in all) {
                val status = when (a) {
                    in d.book.refused -> "refused"
                    in d.book.silent -> "silent"
                    else -> "ok"
                }
                append("${d.name},%06X,$status,".format(a))
                append(d.book.ok[a]?.let { "%02X".format(it) } ?: "")
                for (s in d.samples) append(',').append(s[a]?.let { "%02X".format(it) } ?: "")
                appendLine()
            }
        }
    }

    private fun text(stamp: String): String = buildString {
        val time = runCatching { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(stamp) }.getOrNull() ?: Date()
        appendLine("SSMgen4 parameter sweep, ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(time)}")
        appendLine("Adapter      ${result.adapterName} ${result.adapterAddress}, ${result.chipName}")
        appendLine("Definitions  ${LoggerDefs.ASSET.substringAfterLast('/')}")
        appendLine("Duration     ${duration(result.durationMs)}, ${result.reconnects} Bluetooth reconnects")
        appendLine("Requests     read-only (SSM init AA and read-addresses A8). Nothing was written to the ECU.")
        for (d in result.devices) {
            appendLine()
            appendLine("${d.name} ${d.init.ecuId} (SSM id ${d.init.ssmId})")
            appendLine("  init reply   ${Ssm.hex(d.init.raw)}")
            appendLine("  addresses    ${d.book.ok.size + d.book.refused.size + d.book.silent.size} probed: ${d.book.ok.size} readable, ${d.book.refused.size} refused, ${d.book.silent.size} silent")
            appendLine("  samples      ${d.samples.size} over ${"%.1f".format(Locale.US, d.sampleMs / 1000.0)} s")
            rows.firstOrNull { it.deviceName == d.name && it.param.id == "P8" && it.values.isNotEmpty() }?.let { rpm ->
                appendLine("  engine speed ${fmt(rpm.min, 0)}..${fmt(rpm.max, 0)} rpm while sampling")
            }
        }
        if (result.devices.none { it.name == "TCU" }) {
            appendLine()
            appendLine("TCU          no answer on 7E1, so TCU-only parameters were not probed")
        }

        appendLine()
        appendLine("SUMMARY")
        for (line in summaryLines()) appendLine("  $line")

        append(guidedText())

        val advertisedRefused = rows.filter { it.advertised == true && it.status != Status.OK }
        if (advertisedRefused.isNotEmpty()) {
            appendLine()
            appendLine("Advertised by the init bitmap but not readable:")
            for (r in advertisedRefused) appendLine("  ${r.param.id} ${r.param.name} (${r.status.label})")
        }
        val bonus = rows.filter { it.advertised == false && it.status == Status.OK && it.live }
        if (bonus.isNotEmpty()) {
            appendLine()
            appendLine("Not advertised, yet readable and changing:")
            for (r in bonus) appendLine("  ${r.param.id} ${r.param.name}")
        }

        for ((section, group) in rows.groupBy { it.section }) {
            appendLine()
            appendLine("=== $section (${group.count { it.status == Status.OK }} of ${group.size} readable) ===")
            appendLine(ROW.format("ID", "Name", "Address", "Adv", "Status", "Last", "Min..Max", "Units", "Raw", "Notes"))
            for (r in group.sortedBy { it.status.ordinal }) {
                val loc = r.param.location
                val address = when {
                    loc == null -> r.param.depends.joinToString("+")
                    loc.bit != null -> "$loc.${loc.bit}"
                    loc.length > 1 -> "$loc x${loc.length}"
                    else -> loc.toString()
                }
                val adv = when (r.advertised) { true -> "yes"; false -> "no"; null -> "-" }
                val range = if (r.values.size > 1) "${fmt(r.min, r.decimals)}..${fmt(r.max, r.decimals)}" else ""
                appendLine(
                    ROW.format(
                        r.param.id, r.param.name.take(NAME_WIDTH), address.take(14), adv, r.status.label,
                        fmt(r.last, r.decimals), range, r.units.take(10), r.rawLast.take(10), r.flags.joinToString(", "),
                    ),
                )
            }
        }

        for ((device, group) in switchRows.groupBy { it.deviceName }) {
            appendLine()
            appendLine("=== $device switches (${group.count { it.status == Status.OK }} of ${group.size} supported and readable) ===")
            for (s in group.sortedBy { it.status.ordinal }) {
                val state = when {
                    s.status != Status.OK -> s.status.label
                    s.bits.isEmpty() -> "no samples"
                    s.bits.distinct().size > 1 -> "changed (last ${onOff(s.bits.last())})"
                    else -> onOff(s.bits.last())
                }
                appendLine("%-6s %-44s %06X.%d  %s".format(s.switch.id, s.switch.name.take(44), s.switch.address, s.switch.bit, state))
            }
        }

        appendLine()
        appendLine("=== Trouble codes (generic RomRaider table, last sample) ===")
        val dtcBytes = ecu.dtcBytes.distinct()
        val dtcReadable = dtcBytes.count { it in ecu.book.ok }
        appendLine(
            "${dtcBytes.size} trouble-code bytes, $dtcReadable readable, ${dtcFillerBytes.size} read FF " +
                "(codes this ECU does not have; ignored)",
        )
        if (dtcRows.isEmpty()) appendLine("No current or memorised codes set")
        for (d in dtcRows) {
            val kind = listOfNotNull("current".takeIf { d.current }, "memorised".takeIf { d.memorised }).joinToString(" + ")
            appendLine("%-6s %-60s %s".format(d.id, d.name, kind))
        }
    }

    /**
     * Per guided step: key readings, switches that differ from the idle baseline, and any
     * parameter that held one value through the baseline but moved during the step.
     */
    private fun guidedText(): String = buildString {
        val steps = ecu.sampleSteps
        if (steps.none { it >= 0 }) return@buildString
        val views = ecu.samples.map { SampleValues(defs, it) }
        val okRows = rows.filter { it.deviceName == ecu.name && it.status == Status.OK }
        val byId = okRows.associateBy { it.param.id }
        val keys = KEY_READINGS.filter { it.first in byId }
        val switches = switchRows.filter { it.deviceName == ecu.name && it.status == Status.OK }

        fun samplesOf(step: Int) = steps.indices.filter { steps[it] == step }
        fun valuesOf(id: String, idx: List<Int>) = idx.mapNotNull { views[it].value(id, null) }.filter { it.isFinite() }
        fun bitsOf(s: SwitchRow, idx: List<Int>) = idx.mapNotNull { i -> ecu.samples[i][s.switch.address]?.let { (it.toInt() shr s.switch.bit) and 1 } }

        val base = samplesOf(0)
        val baseBits = switches.associateWith { s -> bitsOf(s, base).let { b -> if (b.count { it == 1 } * 2 > b.size) 1 else 0 } }
        // Parameters that sat still at idle: any movement in a later step is that step's doing.
        val still = okRows.filter { r -> r.param.kind != ParamKind.DEPENDENT && valuesOf(r.param.id, base).distinct().size == 1 }

        appendLine()
        appendLine("=== Guided steps (compared with the idle baseline) ===")
        for ((index, step) in GuidedSteps.STEPS.withIndex()) {
            val idx = samplesOf(index)
            appendLine()
            appendLine("${index + 1}. ${step.label} (${idx.size} samples)")
            if (idx.isEmpty()) continue
            appendLine("   " + keys.joinToString(" · ") { (id, short) ->
                val v = valuesOf(id, idx)
                val r = byId.getValue(id)
                val range = if (v.isEmpty()) "-" else "${fmt(v.min(), r.decimals)}..${fmt(v.max(), r.decimals)}"
                "$short $range ${r.units}".trimEnd()
            })
            if (index == 0) continue
            val flipped = switches.mapNotNull { s ->
                val b = bitsOf(s, idx)
                val differ = b.count { it != baseBits[s] }
                if (differ == 0) null else "${s.switch.id} ${s.switch.name} ${onOff(1 - baseBits.getValue(s))} ($differ/${b.size})"
            }
            appendLine("   switches: " + (flipped.joinToString("; ").ifEmpty { "none changed" }))
            val moved = still.mapNotNull { r ->
                val baseValue = valuesOf(r.param.id, base).first()
                val v = valuesOf(r.param.id, idx)
                if (v.all { it == baseValue }) null
                else "${r.param.id} ${r.param.name} ${fmt(baseValue, r.decimals)} -> ${fmt(v.min(), r.decimals)}..${fmt(v.max(), r.decimals)} ${r.units}".trimEnd()
            }
            if (moved.isNotEmpty()) {
                appendLine("   moved from their idle value:")
                for (m in moved) appendLine("     $m")
            }
        }
    }

    private fun csv(): String = buildString {
        appendLine("section,device,id,name,address,length,bit,advertised,status,samples,last,min,max,units,raw_last,notes")
        for (r in rows) {
            val loc = r.param.location
            appendLine(
                listOf(
                    r.section, r.deviceName, r.param.id, r.param.name,
                    loc?.toString() ?: "", loc?.length?.toString() ?: "", loc?.bit?.toString() ?: "",
                    r.advertised?.toString() ?: "", r.status.label, r.values.size.toString(),
                    r.last?.toString() ?: "", r.min?.toString() ?: "", r.max?.toString() ?: "",
                    r.units, r.rawLast, r.flags.joinToString("; "),
                ).joinToString(",") { csvField(it) },
            )
        }
        for (s in switchRows) {
            appendLine(
                listOf(
                    "${s.deviceName} switches", s.deviceName, s.switch.id, s.switch.name,
                    "%06X".format(s.switch.address), "1", s.switch.bit.toString(), "", s.status.label,
                    s.bits.size.toString(), s.bits.lastOrNull()?.toString() ?: "",
                    s.bits.minOrNull()?.toString() ?: "", s.bits.maxOrNull()?.toString() ?: "", "", "", "",
                ).joinToString(",") { csvField(it) },
            )
        }
    }

    /** Short version for the phone screen. */
    fun summary(): String =
        result.devices.joinToString("\n") { d -> "${d.name} ${d.init.ecuId}: ${d.samples.size} samples" } +
            "\n" + summaryLines().joinToString("\n")

    private fun summaryLines(): List<String> {
        val out = ArrayList<String>()
        for ((section, group) in rows.groupBy { it.section }) {
            val ok = group.count { it.status == Status.OK }
            val refused = group.count { it.status == Status.REFUSED }
            val silent = group.count { it.status == Status.SILENT }
            val unsupported = group.count { it.status == Status.UNSUPPORTED }
            val live = group.count { it.status == Status.OK && it.live }
            var line = "$section: $ok of ${group.size} readable, $live live"
            if (refused > 0) line += ", $refused refused"
            if (silent > 0) line += ", $silent silent"
            if (unsupported > 0) line += ", $unsupported unsupported"
            val adv = group.filter { it.advertised == true }
            if (adv.isNotEmpty()) line += " (${adv.count { it.status == Status.OK }} of ${adv.size} advertised)"
            out += line
        }
        for ((device, group) in switchRows.groupBy { it.deviceName }) {
            out += "$device switches: ${group.count { it.status == Status.OK }} of ${group.size} supported and readable"
        }
        out += "Trouble codes: ${dtcRows.count { it.current }} current, ${dtcRows.count { it.memorised }} memorised"
        val odd = rows.count { r ->
            r.status == Status.OK && r.flags.any { it == "not a number" || it == "outside plausible range" || it == "implausibly large" }
        }
        if (odd > 0) out += "Implausible values: $odd"
        return out
    }

    private companion object {
        const val FF: Byte = -1
        const val NAME_WIDTH = 42

        /** Shown for every guided step, if readable: parameter id to short label. */
        val KEY_READINGS = listOf(
            "P8" to "rpm", "P7" to "MAP", "P202" to "boost", "P13" to "throttle", "P30" to "pedal",
            "P1" to "load", "P10" to "timing", "P12" to "MAF", "P21" to "inj", "P17" to "batt",
        )
        const val ROW = "%-6s %-42s %-14s %-3s %-9s %12s %-23s %-10s %-10s %s"

        fun fmt(v: Double?, decimals: Int): String = when {
            v == null -> ""
            !v.isFinite() -> v.toString()
            abs(v) >= 1e7 -> "%.3e".format(Locale.US, v)
            else -> "%.${decimals}f".format(Locale.US, v)
        }

        fun onOff(bit: Int) = if (bit == 1) "on" else "off"

        fun duration(ms: Long): String = "%dm %02ds".format(Locale.US, ms / 60_000, (ms / 1000) % 60)

        fun csvField(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

