package com.ssmgen4.app.defs

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader

/** One way of turning a decoded raw value into a reading. The first listed is the default. */
class Conversion(
    val units: String,
    val expr: String,
    val format: String?,
    val storage: String?,
    val gaugeMin: Double?,
    val gaugeMax: Double?,
) {
    val compiled: Expr? by lazy { Expr.parse(expr) }

    /** Decimal places implied by a RomRaider format like `0.00`. */
    val decimals: Int get() = format?.substringAfter('.', "")?.length ?: 2
}

/** Where a value lives. [bit] set means it is one flag bit of the byte at [address]. */
class Location(val address: Int, val length: Int, val bit: Int?) {
    val addresses: IntArray get() = IntArray(length) { address + it }
    override fun toString(): String = "%06X".format(address)
}

enum class ParamKind { STANDARD, EXTENDED, DEPENDENT }

class LogParam(
    val kind: ParamKind,
    val id: String,
    val name: String,
    /** RomRaider target bits: 1 = ECU, 2 = TCU, 3 = both. */
    val target: Int,
    /** Capability bit in the init reply, indexed over the whole reply (header included). */
    val capByte: Int?,
    val capBit: Int?,
    val location: Location?,
    val conversions: List<Conversion>,
    val depends: List<String>,
) {
    val forEcu: Boolean get() = target and 1 != 0
    val forTcu: Boolean get() = target and 2 != 0

    /** Storage type from the conversions, if any states one; otherwise unsigned by width. */
    val storage: String? get() = conversions.firstNotNullOfOrNull { it.storage }

    override fun toString(): String = "$id $name"
}

class SwitchDef(val id: String, val name: String, val address: Int, val bit: Int, val capByte: Int?, val target: Int)

class DtcDef(val id: String, val name: String, val tmpAddress: Int, val memAddress: Int, val bit: Int)

/**
 * The SSM section of a RomRaider logger definition. Extended (RAM) parameters are kept
 * only for the ECU id given at load time, because their addresses differ per ROM.
 */
class LoggerDefs(
    val standard: List<LogParam>,
    val dependent: List<LogParam>,
    val extended: List<LogParam>,
    val switches: List<SwitchDef>,
    val dtcs: List<DtcDef>,
    /** How many extended parameters the file defines across all ECUs. */
    val extendedTotal: Int,
) {
    val byId: Map<String, LogParam> by lazy { (standard + dependent + extended).associateBy { it.id } }

    companion object {
        const val ASSET = "definitions/logger_METRIC_EN_v370.xml"

        fun load(context: Context, ecuId: String?): LoggerDefs =
            context.assets.open(ASSET).use { parse(it, ecuId) }

        /** [p] is injectable because android.util.Xml is only a stub in JVM unit tests. */
        fun parse(input: InputStream, ecuId: String?, p: XmlPullParser = Xml.newPullParser()): LoggerDefs {
            p.setInput(StringReader(withoutDoctype(input.readBytes().toString(Charsets.UTF_8))))

            val standard = ArrayList<LogParam>()
            val dependent = ArrayList<LogParam>()
            val extended = ArrayList<LogParam>()
            val switches = ArrayList<SwitchDef>()
            val dtcs = ArrayList<DtcDef>()
            var extendedTotal = 0

            var inSsm = false
            var inEcu = false
            var ecuMatches = false
            var cur: Builder? = null

            var event = p.eventType
            loop@ while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val tag = p.name
                    if (tag == "protocol") {
                        inSsm = p.attr("id") == "SSM"
                    } else if (inSsm) {
                        when (tag) {
                            "parameter" -> cur = Builder(
                                ParamKind.STANDARD, p.attr("id") ?: "", p.attr("name") ?: "",
                                p.int("target") ?: 1, p.int("ecubyteindex"), p.int("ecubit"),
                            )
                            "ecuparam" -> {
                                extendedTotal++
                                cur = Builder(ParamKind.EXTENDED, p.attr("id") ?: "", p.attr("name") ?: "", p.int("target") ?: 1, null, null)
                            }
                            "depends" -> cur?.dependent = true
                            "ref" -> p.attr("parameter")?.let { cur?.depends?.add(it) }
                            "ecu" -> {
                                inEcu = true
                                ecuMatches = ecuId != null &&
                                    p.attr("id")?.split(',')?.any { it.trim().equals(ecuId, ignoreCase = true) } == true
                            }
                            "address" -> {
                                val length = p.int("length") ?: 1
                                val bit = p.int("bit")
                                val address = hex(p.nextText())
                                val b = cur
                                if (b != null && address != null && b.location == null) {
                                    val keep = (b.kind == ParamKind.STANDARD && !inEcu) ||
                                        (b.kind == ParamKind.EXTENDED && inEcu && ecuMatches)
                                    if (keep) b.location = Location(address, length, bit)
                                }
                            }
                            "conversion" -> cur?.conversions?.add(
                                Conversion(
                                    units = p.attr("units") ?: "",
                                    expr = p.attr("expr") ?: "x",
                                    format = p.attr("format"),
                                    storage = p.attr("storagetype"),
                                    gaugeMin = p.attr("gauge_min")?.toDoubleOrNull(),
                                    gaugeMax = p.attr("gauge_max")?.toDoubleOrNull(),
                                ),
                            )
                            "switch" -> {
                                val address = hex(p.attr("byte"))
                                val bit = p.int("bit")
                                if (address != null && bit != null) {
                                    switches += SwitchDef(p.attr("id") ?: "", p.attr("name") ?: "", address, bit, p.int("ecubyteindex"), p.int("target") ?: 1)
                                }
                            }
                            "dtcode" -> {
                                val tmp = hex(p.attr("tmpaddr"))
                                val mem = hex(p.attr("memaddr"))
                                val bit = p.int("bit")
                                if (tmp != null && mem != null && bit != null) {
                                    dtcs += DtcDef(p.attr("id") ?: "", p.attr("name") ?: "", tmp, mem, bit)
                                }
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && inSsm) {
                    when (p.name) {
                        "protocol" -> break@loop
                        "ecu" -> {
                            inEcu = false
                            ecuMatches = false
                        }
                        "parameter", "ecuparam" -> {
                            cur?.let { b ->
                                val param = b.build()
                                when {
                                    b.dependent -> dependent += param
                                    b.kind == ParamKind.STANDARD && b.location != null -> standard += param
                                    b.kind == ParamKind.EXTENDED && b.location != null -> extended += param
                                }
                            }
                            cur = null
                        }
                    }
                }
                event = p.next()
            }
            return LoggerDefs(standard, dependent, extended, switches, dtcs, extendedTotal)
        }

        /**
         * Drops the `<!DOCTYPE ... [ ... ]>` block. RomRaider's internal DTD declares enumerations
         * of bare numbers (`bit ( 0 | 1 | ... )`), which is valid XML but which Android's built-in
         * parser rejects with "name expected". Nothing here validates, so the DTD is not needed.
         */
        internal fun withoutDoctype(xml: String): String {
            val start = xml.indexOf("<!DOCTYPE")
            if (start < 0) return xml
            val firstClose = xml.indexOf('>', start)
            val bracket = xml.indexOf('[', start)
            val end = if (bracket in 0 until firstClose) {
                xml.indexOf("]>", bracket).let { if (it < 0) return xml else it + 2 }
            } else {
                if (firstClose < 0) return xml else firstClose + 1
            }
            return xml.substring(0, start) + xml.substring(end)
        }

        private fun hex(text: String?): Int? =
            text?.trim()?.removePrefix("0x")?.removePrefix("0X")?.toIntOrNull(16)

        private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

        private fun XmlPullParser.int(name: String): Int? = attr(name)?.trim()?.toIntOrNull()
    }

    private class Builder(
        val kind: ParamKind,
        val id: String,
        val name: String,
        val target: Int,
        val capByte: Int?,
        val capBit: Int?,
    ) {
        var location: Location? = null
        var dependent = false
        val conversions = ArrayList<Conversion>()
        val depends = ArrayList<String>()

        fun build() = LogParam(
            if (dependent) ParamKind.DEPENDENT else kind,
            id, name, target, capByte, capBit, location, conversions, depends,
        )
    }
}

/** Raw bytes to a number, per the definition's storage type, then through a conversion. */
object Decode {

    fun raw(bytes: ByteArray, location: Location, storage: String?): Double {
        location.bit?.let { return ((bytes[0].toInt() shr it) and 1).toDouble() }
        var u = 0L
        for (i in 0 until location.length) u = (u shl 8) or (bytes[i].toLong() and 0xFF)
        return when (storage?.lowercase()) {
            "float" -> if (location.length == 4) java.lang.Float.intBitsToFloat(u.toInt()).toDouble() else u.toDouble()
            "int8" -> u.toByte().toDouble()
            "int16" -> u.toShort().toDouble()
            "int32" -> u.toInt().toDouble()
            else -> u.toDouble()
        }
    }

    fun convert(conversion: Conversion?, raw: Double): Double? =
        if (conversion == null) raw else conversion.compiled?.eval { if (it == "x") raw else null }
}
