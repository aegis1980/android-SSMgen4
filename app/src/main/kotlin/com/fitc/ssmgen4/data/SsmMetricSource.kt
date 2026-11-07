package com.fitc.ssmgen4.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.fitc.ssmgen4.defs.DtcDef
import com.fitc.ssmgen4.defs.LoggerDefs
import com.fitc.ssmgen4.elm.AdapterGate
import com.fitc.ssmgen4.elm.BluetoothSppPort
import com.fitc.ssmgen4.elm.LinkException
import com.fitc.ssmgen4.elm.ObdAdapters
import com.fitc.ssmgen4.elm.StnCanLink
import com.fitc.ssmgen4.ssm.DialDef
import com.fitc.ssmgen4.ssm.Dials
import com.fitc.ssmgen4.ssm.Ssm
import com.fitc.ssmgen4.ssm.SsmParameter

/**
 * Live readings from the ECU over a Bluetooth OBD adapter.
 *
 * Owns one background thread that connects, runs the SSM init, works out which of the
 * requested parameters this ECU can actually answer, then polls as fast as replies come.
 * Policy copied from what was observed working in the car: three attempts per sample,
 * reconnect after four samples in a row are dropped, back off between reconnects.
 *
 * [focus] narrows the poll to what is on screen; every extra address costs update rate
 * (~12 Hz at 10 addresses, ~8 Hz at 15 on the OBDLink LX).
 */
class SsmMetricSource(
    context: Context,
    private val dials: List<DialDef> = Dials.DEFAULT,
    /** MAC of the adapter to use; null picks a paired device that looks like an OBD dongle. */
    private val preferredAddress: String? = null,
) : MetricSource {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override val metrics: List<Metric> = dials.map { it.metric }

    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var link: StnCanLink? = null

    private var onReadings: (Map<String, Float>) -> Unit = {}
    private var onStatus: (String) -> Unit = {}
    private var onCodes: (TroubleCodes) -> Unit = {}

    @Volatile private var focus: Set<String>? = null
    @Volatile private var replan = false
    private var nextCodeScan = 0L

    /** Called by [AdapterGate.claim]: drop the connection so the claimant can have it. */
    private val letGo: () -> Unit = { link?.close() }

    override fun start(
        onReadings: (Map<String, Float>) -> Unit,
        onStatus: (String) -> Unit,
        onCodes: (TroubleCodes) -> Unit,
    ) {
        stop()
        this.onReadings = onReadings
        this.onStatus = onStatus
        this.onCodes = onCodes
        running = true
        AdapterGate.register(letGo)
        worker = Thread(::run, "ssm-poll").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        AdapterGate.unregister(letGo)
        link?.close()
        link = null
        worker?.let { w ->
            w.interrupt()
            runCatching { w.join(1500) }
        }
        worker = null
    }

    override fun focus(ids: Set<String>?) {
        focus = ids
        replan = true
    }

    // ---- worker ------------------------------------------------------------------------

    private fun run() {
        var backoffMs = 1000L
        while (running) {
            AdapterGate.reservedFor?.let { reason ->
                status(reason)
                Thread.sleep(1000)
                backoffMs = 1000L
                continue
            }
            try {
                session()
                backoffMs = 1000L
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!running) return
                Log.w(TAG, "session ended: ${e.message}")
                status(e.message ?: "Connection lost")
            }
            link?.close()
            link = null
            if (!running) return
            status("Reconnecting in ${backoffMs / 1000}s…")
            Thread.sleep(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
        }
    }

    /** One connection, from Bluetooth open to the point the link gives up. */
    @SuppressLint("MissingPermission")
    private fun session() {
        if (!hasBluetoothPermission()) {
            status("Open SSMgen4 on the phone to allow Bluetooth")
            Thread.sleep(3000)
            return
        }
        val device = chooseDevice() ?: run {
            status("No OBD adapter paired")
            Thread.sleep(3000)
            return
        }

        status("Connecting to ${device.name ?: device.address}…")
        val port = BluetoothSppPort.open(device) { !running || AdapterGate.reserved }
        if (AdapterGate.reserved) {
            // Claimed while we were connecting: hand the socket straight back.
            port.close()
            return
        }
        val link = StnCanLink(port) { Log.d(TAG, it) }
        this.link = link
        try {
            link.open()
            status("${link.chipName}: waiting for the ECU…")

            val init = link.initialise() ?: throw LinkException("ECU did not answer the init")
            Log.i(TAG, "connected to ${init.ecuId} via ${link.chipName}")

            val supported = init.supports()
            replan = false
            nextCodeScan = 0L
            poll(link, supported, plan(supported))
        } finally {
            link.close()
            if (this.link === link) this.link = null
        }
    }

    // ---- choosing what to read ---------------------------------------------------------

    private class Plan(
        val addresses: IntArray,
        /** Per dial: offsets into [addresses] for each parameter it needs, or null if unavailable. */
        val slots: List<IntArray?>,
    )

    private fun com.fitc.ssmgen4.ssm.EcuInitResponse.supports(): (SsmParameter) -> Boolean =
        { p -> supports(p.capabilityByte, p.capabilityBit) }

    private fun plan(supported: (SsmParameter) -> Boolean, refused: Set<Int> = emptySet()): Plan {
        val addresses = ArrayList<Int>()
        val index = HashMap<Int, Int>()
        fun slot(address: Int): Int = index.getOrPut(address) { addresses.add(address); addresses.size - 1 }

        val wanted = focus
        fun DialDef.wanted() = wanted == null || metric.id in wanted
        val slots = dials.map { dial ->
            val ok = dial.wanted() && dial.needs.all { p -> supported(p) && p.addresses.none { it in refused } }
            if (!ok) null else IntArray(dial.needs.sumOf { it.byteCount }).also { arr ->
                var i = 0
                for (p in dial.needs) for (a in p.addresses) arr[i++] = slot(a)
            }
        }
        val dropped = dials.filterIndexed { i, d -> slots[i] == null && d.wanted() }.map { it.metric.label }
        if (dropped.isNotEmpty()) Log.i(TAG, "not readable on this ECU/link: $dropped")
        return Plan(addresses.toIntArray(), slots)
    }

    // ---- polling -----------------------------------------------------------------------

    private fun poll(link: StnCanLink, supported: (SsmParameter) -> Boolean, initial: Plan) {
        var plan = initial
        var dropped = 0
        var samples = 0
        var windowStart = SystemClock.elapsedRealtime()
        val ecu = link.chipName

        status("$ecu: reading ${plan.addresses.size} addresses")

        while (running) {
            if (replan) {
                replan = false
                plan = plan(supported, link.refusedAddresses)
                nextCodeScan = 0L
            }
            if (plan.addresses.isEmpty()) throw LinkException("Nothing on this screen is readable from this ECU")

            var bytes: ByteArray? = null
            for (attempt in 1..ATTEMPTS) {
                bytes = link.read(plan.addresses)
                if (bytes != null) break
                if (link.refusedAddresses.any { it in plan.addresses }) {
                    // A probe just found the culprits; rebuild without them and carry on.
                    plan = plan(supported, link.refusedAddresses).also {
                        Log.i(TAG, "ECU refuses some addresses, now reading ${it.addresses.size}")
                    }
                    break
                }
                Log.w(TAG, "no reply to a request, attempt $attempt of $ATTEMPTS")
            }

            if (bytes == null) {
                if (++dropped >= DROPS_BEFORE_RECONNECT) throw LinkException("ECU stopped answering")
                continue
            }
            dropped = 0

            // Convert and hand over.
            val values = HashMap<String, Float>(dials.size)
            for ((i, dial) in dials.withIndex()) {
                val slots = plan.slots[i] ?: continue
                val inputs = DoubleArray(dial.needs.size)
                var s = 0
                for ((k, p) in dial.needs.withIndex()) {
                    val raw = ByteArray(p.byteCount) { bytes[slots[s + it]] }
                    s += p.byteCount
                    inputs[k] = p.value(raw, 0)
                }
                values[dial.metric.id] = dial.compute(inputs).toFloat()
            }
            main.post { if (running) onReadings(values) }

            if (focus?.contains(TroubleCodes.ID) == true && SystemClock.elapsedRealtime() >= nextCodeScan) {
                nextCodeScan = SystemClock.elapsedRealtime() + CODE_SCAN_MS
                scanCodes(link)
            }

            // Rate for the status line, once a second.
            samples++
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 1000) {
                val rateHz = samples * 1000.0 / (now - windowStart)
                samples = 0
                windowStart = now
                status("%s · %.0f Hz".format(ecu, rateHz))
            }
        }
    }

    // ---- trouble codes -----------------------------------------------------------------

    /**
     * Reads every trouble-code byte the definitions know (154 for this ECU, five requests)
     * and reports what is set. A read that fails just skips this round.
     */
    private fun scanCodes(link: StnCanLink) {
        val dtcs = troubleCodeDefs() ?: return
        val addresses = dtcs.flatMap { listOf(it.tmpAddress, it.memAddress) }.distinct().sorted()
        val bytes = HashMap<Int, Byte>(addresses.size)
        for (chunk in addresses.chunked(Ssm.MAX_ADDRESSES_PER_REQUEST)) {
            val reply = link.read(chunk.toIntArray()) ?: run {
                Log.w(TAG, "trouble-code scan incomplete, trying again later")
                return
            }
            chunk.forEachIndexed { i, a -> bytes[a] = reply[i] }
        }
        val codes = TroubleCodes.decode(dtcs, bytes)
        Log.i(TAG, "trouble codes: ${codes.current.size} current, ${codes.memorised.size} memorised")
        main.post { if (running) onCodes(codes) }
    }

    private fun troubleCodeDefs(): List<DtcDef>? = troubleCodeCache ?: runCatching { LoggerDefs.load(app, null).dtcs }
        .onFailure { Log.w(TAG, "could not load the trouble-code definitions", it) }
        .getOrNull()
        ?.also { troubleCodeCache = it }

    // ---- helpers -----------------------------------------------------------------------

    private fun status(text: String) {
        main.post { if (running) onStatus(text) }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun chooseDevice(): BluetoothDevice? = ObdAdapters.choose(app, preferredAddress)

    private companion object {
        const val TAG = "SsmSource"
        const val ATTEMPTS = 3
        const val DROPS_BEFORE_RECONNECT = 4
        const val CODE_SCAN_MS = 60_000L

        /** Parsed once per process; the definitions file is 2 MB. */
        @Volatile var troubleCodeCache: List<DtcDef>? = null
    }
}
