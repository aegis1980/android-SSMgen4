package com.fitc.ssmgen4.data

import com.fitc.ssmgen4.defs.DtcDef

/**
 * Trouble codes set in the ECU, by RomRaider name, e.g. "P0128 THERMOSTAT MALFUNCTION".
 * Current codes are faults present now; memorised ones are stored from earlier drives.
 */
class TroubleCodes(val current: List<String>, val memorised: List<String>) {

    /** Every code once, current ones first. */
    val all: List<String> get() = (current + memorised).distinct()

    companion object {
        /** Put in [MetricSource.focus] to have the source scan for codes now and then. */
        const val ID = "DTC"

        /**
         * Codes set in [bytes] (address to byte read). A byte that reads 0xFF holds codes this
         * ECU does not have rather than eight faults at once, so codes in it are skipped, the
         * same rule the sweep report uses.
         */
        fun decode(dtcs: List<DtcDef>, bytes: Map<Int, Byte>): TroubleCodes {
            val current = ArrayList<String>()
            val memorised = ArrayList<String>()
            for (dtc in dtcs) {
                val tmp = bytes[dtc.tmpAddress]
                val mem = bytes[dtc.memAddress]
                if (tmp == FILLER || mem == FILLER) continue
                if (tmp != null && (tmp.toInt() shr dtc.bit) and 1 == 1) current += dtc.name
                if (mem != null && (mem.toInt() shr dtc.bit) and 1 == 1) memorised += dtc.name
            }
            return TroubleCodes(current, memorised)
        }

        private const val FILLER = 0xFF.toByte()
    }
}
