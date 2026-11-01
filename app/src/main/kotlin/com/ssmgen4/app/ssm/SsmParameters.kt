package com.ssmgen4.app.ssm

import com.ssmgen4.app.data.Metric

/**
 * A standard SSM parameter: where it lives, how wide it is, how to turn the raw integer
 * into a value, and which bit of the init reply says the ECU supports it.
 *
 * Addresses and formulas are the SSM standard set as used by RomRaider; multi-byte values
 * are big-endian. Capability offsets index the whole init reply (see [EcuInitResponse]).
 */
class SsmParameter(
    val id: String,
    val name: String,
    val address: Int,
    val byteCount: Int,
    val capabilityByte: Int,
    val capabilityBit: Int,
    val convert: (Int) -> Double,
) {
    /** The individual byte addresses a read request has to ask for. */
    val addresses: IntArray get() = IntArray(byteCount) { address + it }

    fun value(bytes: ByteArray, offset: Int): Double {
        var raw = 0
        for (i in 0 until byteCount) raw = (raw shl 8) or (bytes[offset + i].toInt() and 0xFF)
        return convert(raw)
    }

    override fun toString(): String = id
}

object SsmParameters {
    val RPM = SsmParameter("RPM", "Engine speed", 0x0E, 2, 8, 0) { it / 4.0 }
    val COOLANT = SsmParameter("MCT", "Coolant", 0x08, 1, 8, 6) { it - 40.0 }
    val INTAKE = SsmParameter("MAT", "Intake", 0x12, 1, 9, 5) { it - 40.0 }
    val TIMING = SsmParameter("TIMING", "Timing", 0x11, 1, 9, 6) { (it - 128) / 2.0 }
    val MAF = SsmParameter("MAF", "MAF", 0x13, 2, 9, 4) { it / 100.0 }
    val THROTTLE = SsmParameter("TP", "Throttle plate", 0x15, 1, 9, 3) { it * 100.0 / 255.0 }
    val SPEED_KMH = SsmParameter("KMH", "Speed", 0x10, 1, 9, 7) { it.toDouble() }
    val BATTERY = SsmParameter("VBAT", "Battery", 0x1C, 1, 10, 7) { it * 0.08 }
    val WGDC = SsmParameter("WGDC", "Turbo WGDC", 0x30, 1, 12, 3) { it * 100.0 / 255.0 }
    val AFR = SsmParameter("AFR", "AFR", 0x46, 1, 15, 5) { it / 128.0 * 14.7 }

    /** Manifold absolute pressure, psi. Boost is this minus [ATMOSPHERE]. */
    val MANIFOLD_ABS = SsmParameter("ABSBOOST", "Manifold absolute", 0x0D, 1, 8, 1) { it * 37.0 / 255.0 }
    val ATMOSPHERE = SsmParameter("ATMOS", "Atmosphere", 0x23, 1, 10, 0) { it * 37.0 / 255.0 }

    // The rest were found live on 4B04785007 by the parameter sweep (RomRaider P3, P4, P90,
    // P63/64/69/70, P48/49/83/84).

    /** Short-term fuel trim. */
    val AF_CORRECTION = SsmParameter("AFC1", "A/F correction #1", 0x09, 1, 8, 5) { (it - 128) * 100.0 / 128.0 }
    /** Long-term fuel trim. */
    val AF_LEARNING = SsmParameter("AFL1", "A/F learning #1", 0x0A, 1, 8, 4) { (it - 128) * 100.0 / 128.0 }
    /** Ignition advance multiplier: 1.0 means the ECU has learned no knock. */
    val IAM = SsmParameter("IAM", "IAM", 0xF9, 1, 55, 0) { it / 16.0 }

    /** Misfire counts, cylinders 1 to 4. */
    val ROUGHNESS: List<SsmParameter> = listOf(0xCE to 7, 0xCF to 6, 0xD8 to 5, 0xD9 to 4)
        .mapIndexed { i, (address, bit) ->
            SsmParameter("RGH${i + 1}", "Roughness cylinder ${i + 1}", address, 1, 55, bit) { it.toDouble() }
        }

    /** Cam advance in degrees, both cams on both banks. */
    val INTAKE_CAM_RIGHT = SsmParameter("AVCS_IR", "Intake cam right", 0x3C, 1, 14, 7) { it - 50.0 }
    val INTAKE_CAM_LEFT = SsmParameter("AVCS_IL", "Intake cam left", 0x3D, 1, 14, 6) { it - 50.0 }
    val EXHAUST_CAM_RIGHT = SsmParameter("AVCS_ER", "Exhaust cam right", 0x118, 1, 43, 7) { it - 50.0 }
    val EXHAUST_CAM_LEFT = SsmParameter("AVCS_EL", "Exhaust cam left", 0x119, 1, 43, 6) { it - 50.0 }

    // Switches, each 0 or 1. All of these changed with the matching control during the guided
    // sweep (the MIL flag read off throughout).
    val AC_SWITCH = switch("S9", "A/C switch", 0x62, 1, 20)
    val LIGHTS = switch("S22", "Light switch", 0x64, 3, 22)
    val AC_COMPRESSOR = switch("S26", "A/C compressor", 0x65, 7, 23)
    val RADIATOR_FAN = switch("S28", "Radiator fan relay #1", 0x65, 5, 23)
    val CLUTCH = switch("S63", "Clutch switch", 0x121, 7, 45)
    val BRAKE = switch("S64", "Stop light switch", 0x121, 6, 45)
    val MIL = switch("S155", "Check engine light", 0x196, 7, 56)
    /** On in neutral. Read on throughout the parked sweep; no reverse switch exists on this ECU. */
    val NEUTRAL = switch("S4", "Neutral position switch", 0x62, 7, 20)

    /**
     * One flag bit of a switch byte. As in RomRaider, the capability bit is the switch's own
     * bit within its byte of the init reply. Switches sharing a byte share one read.
     */
    private fun switch(id: String, name: String, address: Int, bit: Int, capabilityByte: Int) =
        SsmParameter(id, name, address, 1, capabilityByte, bit) { raw -> ((raw shr bit) and 1).toDouble() }
}

/**
 * One value for the car screen: the [Metric] it is shown as, the parameters it needs, and
 * how to combine them. Most are one-to-one; boost is derived from two. Dials are the
 * original use, but the trip and stuff pages are fed the same way.
 */
class DialDef(
    val metric: Metric,
    val needs: List<SsmParameter>,
    val compute: (DoubleArray) -> Double = { it[0] },
) {
    constructor(metric: Metric, parameter: SsmParameter) : this(metric, listOf(parameter))
}

object Dials {
    val BOOST = DialDef(
        Metric("BOOST", "Boost", "psi", -15f, 25f, decimals = 1, alarmAbove = 20f),
        listOf(SsmParameters.MANIFOLD_ABS, SsmParameters.ATMOSPHERE),
    ) { v -> v[0] - v[1] }

    val RPM = DialDef(Metric("RPM", "Engine speed", "rpm", 0f, 8000f, decimals = 0, alarmAbove = 6800f), SsmParameters.RPM)
    val SPEED = DialDef(Metric("KMH", "Speed", "km/h", 0f, 240f, decimals = 0), SsmParameters.SPEED_KMH)
    val COOLANT = DialDef(Metric("MCT", "Coolant", "°C", 40f, 130f, decimals = 0, alarmAbove = 108f), SsmParameters.COOLANT)
    val AFR = DialDef(Metric("AFR", "AFR", "", 10f, 20f, decimals = 1), SsmParameters.AFR)
    val WGDC = DialDef(Metric("WGDC", "Turbo WGDC", "%", 0f, 100f, decimals = 1), SsmParameters.WGDC)
    val INTAKE = DialDef(Metric("MAT", "Intake", "°C", -20f, 80f, decimals = 0, alarmAbove = 60f), SsmParameters.INTAKE)
    val TIMING = DialDef(Metric("TIMING", "Timing", "°", -20f, 50f, decimals = 1), SsmParameters.TIMING)

    /** What the head unit shows unless configured otherwise. */
    val DEFAULT: List<DialDef> = listOf(BOOST, RPM, SPEED, COOLANT)

    /** Airflow, for the trip page's fuel estimate. */
    val MAF = DialDef(Metric("MAF", "Airflow", "g/s", 0f, 250f, decimals = 1), SsmParameters.MAF)

    // The stuff page.
    val BATTERY = DialDef(Metric("VBAT", "Battery", "V", 10f, 16f, decimals = 1), SsmParameters.BATTERY)
    val IAM = DialDef(Metric("IAM", "IAM", "", 0f, 1f, decimals = 2), SsmParameters.IAM)
    val SHORT_TRIM = DialDef(Metric("AFC1", "Short trim", "%", -25f, 25f, decimals = 1), SsmParameters.AF_CORRECTION)
    val LONG_TRIM = DialDef(Metric("AFL1", "Long trim", "%", -25f, 25f, decimals = 1), SsmParameters.AF_LEARNING)
    val MISFIRES: List<DialDef> = SsmParameters.ROUGHNESS.map { p ->
        DialDef(Metric(p.id, p.name, "", 0f, 255f, decimals = 0), p)
    }
    val CAMS: List<DialDef> = listOf(
        SsmParameters.INTAKE_CAM_RIGHT, SsmParameters.INTAKE_CAM_LEFT,
        SsmParameters.EXHAUST_CAM_RIGHT, SsmParameters.EXHAUST_CAM_LEFT,
    ).map { p -> DialDef(Metric(p.id, p.name, "°", 0f, 50f, decimals = 0), p) }

    val AC = switch(SsmParameters.AC_SWITCH, "A/C")
    val COMPRESSOR = switch(SsmParameters.AC_COMPRESSOR, "Compressor")
    val FAN = switch(SsmParameters.RADIATOR_FAN, "Fan")
    val LIGHTS = switch(SsmParameters.LIGHTS, "Lights")
    val CLUTCH = switch(SsmParameters.CLUTCH, "Clutch")
    val BRAKE = switch(SsmParameters.BRAKE, "Brake")
    val MIL = switch(SsmParameters.MIL, "Check engine")
    val NEUTRAL = switch(SsmParameters.NEUTRAL, "Neutral")
    val SWITCHES: List<DialDef> = listOf(AC, COMPRESSOR, FAN, LIGHTS, CLUTCH, BRAKE)

    val STUFF: List<DialDef> =
        listOf(MIL, IAM, SHORT_TRIM, LONG_TRIM, BATTERY, COOLANT, INTAKE) + MISFIRES + CAMS + SWITCHES

    /** Everything the car session can be asked for; each page polls only its own share. */
    val CAR: List<DialDef> = (DEFAULT + RPM + MAF + NEUTRAL + STUFF).distinctBy { it.metric.id }

    private fun switch(parameter: SsmParameter, label: String) =
        DialDef(Metric(parameter.id, label, "", 0f, 1f, decimals = 0), parameter)
}
