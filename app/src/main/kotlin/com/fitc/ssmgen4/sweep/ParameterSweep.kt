package com.fitc.ssmgen4.sweep

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fitc.ssmgen4.defs.LogParam
import com.fitc.ssmgen4.defs.LoggerDefs
import com.fitc.ssmgen4.defs.SwitchDef
import com.fitc.ssmgen4.elm.AdapterGate
import com.fitc.ssmgen4.elm.BluetoothSppPort
import com.fitc.ssmgen4.elm.LinkException
import com.fitc.ssmgen4.elm.ObdAdapters
import com.fitc.ssmgen4.elm.StnCanLink
import com.fitc.ssmgen4.ssm.EcuInitResponse
import com.fitc.ssmgen4.ssm.Ssm
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * One pass over everything the RomRaider logger definition says this car might answer.
 *
 * Read-only throughout: the only requests sent are SSM init (AA) and read-addresses (A8).
 * Nothing is ever written to the ECU.
 *
 * 1. Connect and init the ECU, then try the TCU (7E1) once.
 * 2. Every standard parameter (advertised or not), every extended parameter defined for
 *    this ECU id, every switch byte and every trouble-code byte is probed in batches of
 *    32. A refused batch is split until each address is known to be readable, refused
 *    (negative reply 0x12) or silent.
 * 3. The readable addresses are then sampled repeatedly for [SAMPLE_MS], so values can
 *    be judged as live, constant or implausible. Blipping the throttle during this phase
 *    makes the difference between a real sensor and a stuck byte obvious.
 * 4. [SweepReport] turns that into a text report, a CSV and a link trace.
 *
 * A Bluetooth drop mid-sweep reconnects and carries on from the batch it was on.
 */
class ParameterSweep(context: Context, private val preferredAddress: String? = null) {

    class Finished(val ok: Boolean, val summary: String, val files: List<File>)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cancelled = false
    @Volatile private var link: StnCanLink? = null
    private var worker: Thread? = null

    private var onProgress: (String) -> Unit = {}
    private var onFinished: (Finished) -> Unit = {}
    private var onStep: () -> Unit = {}

    private val trace: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var startedAt = 0L
    private var adapter: BluetoothDevice? = null
    private var ecuInit: EcuInitResponse? = null
    private var chipName = ""
    private var reconnects = 0
    private var failedInARow = 0

    val running: Boolean get() = worker?.isAlive == true

    /** [onStep] fires on the main thread at the start of each guided step (for a beep). */
    fun start(onProgress: (String) -> Unit, onFinished: (Finished) -> Unit, onStep: () -> Unit = {}) {
        if (running) return
        this.onProgress = onProgress
        this.onFinished = onFinished
        this.onStep = onStep
        cancelled = false
        worker = Thread(::run, "ssm-sweep").apply { isDaemon = true; start() }
    }

    fun cancel() {
        cancelled = true
        link?.close()
        worker?.interrupt()
    }

    // ---- worker ------------------------------------------------------------------------

    private class Cancelled : Exception()

    private fun run() {
        startedAt = SystemClock.elapsedRealtime()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = (app.getExternalFilesDir("sweep") ?: File(app.filesDir, "sweep")).apply { mkdirs() }

        AdapterGate.claim("Parameter sweep running on the phone")
        var finished: Finished
        try {
            val report = SweepReport(sweep())
            val files = report.write(dir, stamp, trace.toList())
            finished = Finished(true, report.summary() + "\n\nReport: ${files.first().absolutePath}", files)
        } catch (_: Cancelled) {
            finished = Finished(false, "Sweep cancelled", listOfNotNull(writeTrace(dir, stamp)))
        } catch (_: InterruptedException) {
            finished = Finished(false, "Sweep cancelled", listOfNotNull(writeTrace(dir, stamp)))
        } catch (e: Exception) {
            note("sweep failed: $e")
            val traceFile = writeTrace(dir, stamp)
            finished = Finished(false, "Sweep failed: ${e.message}" + (traceFile?.let { "\n\nTrace: ${it.absolutePath}" } ?: ""), listOfNotNull(traceFile))
        } finally {
            link?.close()
            link = null
            AdapterGate.release()
        }
        main.post { onFinished(finished) }
    }

    @SuppressLint("MissingPermission")
    private fun sweep(): SweepResult {
        val device = ObdAdapters.choose(app, preferredAddress)
            ?: throw LinkException("No paired OBD adapter, or Bluetooth is off")
        adapter = device

        // Give a live source that was just told to let go a moment to close its socket.
        progress("Taking over the adapter…")
        Thread.sleep(SETTLE_AFTER_CLAIM_MS)

        io { } // connects and inits the ECU
        val ecu = ecuInit!!
        progress("ECU ${ecu.ecuId}: reading the logger definitions…")
        val defs = LoggerDefs.load(app, ecu.ecuId)
        note(
            "definitions: ${defs.standard.size} standard, ${defs.extended.size} extended for ${ecu.ecuId} " +
                "(of ${defs.extendedTotal}), ${defs.dependent.size} calculated, ${defs.switches.size} switches, ${defs.dtcs.size} trouble codes",
        )

        progress("Asking whether a TCU is on the bus…")
        val tcu = io { it.initialise(TCU_INIT_TIMEOUT_MS, Ssm.DEVICE_TCU) }
        note(if (tcu != null) "TCU answered: ${tcu.ecuId} (${Ssm.hex(tcu.raw)})" else "no TCU answer on 7E1")

        val devices = ArrayList<DeviceResult>()
        devices += sweepDevice(
            DeviceResult(
                name = "ECU", device = Ssm.DEVICE_ECU, init = ecu,
                params = defs.standard.filter { it.forEcu } + defs.extended,
                switches = defs.switches.filter { it.target and 1 != 0 },
                dtcBytes = defs.dtcs.flatMap { listOf(it.tmpAddress, it.memAddress) },
            ),
        )
        if (tcu != null) {
            devices += sweepDevice(
                DeviceResult(
                    name = "TCU", device = Ssm.DEVICE_TCU, init = tcu,
                    params = defs.standard.filter { it.forTcu },
                    switches = defs.switches.filter { it.target and 2 != 0 },
                    dtcBytes = emptyList(),
                ),
            )
        }

        return SweepResult(
            adapterName = device.name ?: "adapter",
            adapterAddress = device.address,
            chipName = chipName,
            defs = defs,
            devices = devices,
            durationMs = SystemClock.elapsedRealtime() - startedAt,
            reconnects = reconnects,
        )
    }

    private fun sweepDevice(d: DeviceResult): DeviceResult {
        val addresses = (
            d.params.flatMap { p -> p.location!!.addresses.toList() } +
                d.switches.map(SwitchDef::address) +
                d.dtcBytes
            ).distinct().sorted()
        note("${d.name}: probing ${addresses.size} addresses for ${d.params.size} parameters")
        discover(d, addresses)
        note("${d.name}: ${d.book.ok.size} readable, ${d.book.refused.size} refused, ${d.book.silent.size} silent")
        sample(d)
        return d
    }

    // ---- discovery ---------------------------------------------------------------------

    private fun discover(d: DeviceResult, addresses: List<Int>) {
        var done = 0
        for (chunk in addresses.chunked(BATCH)) {
            classify(d, chunk)
            done += chunk.size
            progress(
                "${d.name}: probed $done of ${addresses.size} addresses\n" +
                    "${d.book.ok.size} readable · ${d.book.refused.size} refused · ${d.book.silent.size} silent\n\n" +
                    "GET SET: neutral, handbrake on, engine idling, lights, fan, A/C and wipers off.\n" +
                    "Guided steps start shortly, about ${GuidedSteps.totalSeconds / 60 + 1} minutes in all. Each step beeps.",
            )
        }
    }

    /** Sort every address in [chunk] into readable, refused or silent. */
    private fun classify(d: DeviceResult, chunk: List<Int>) {
        var pending = chunk
        var failures = 0
        while (pending.isNotEmpty()) {
            val bytes = io { it.read(pending.toIntArray(), d.device) }
            if (bytes != null) {
                pending.forEachIndexed { i, a -> d.book.ok[a] = bytes[i] }
                return
            }
            val declined = link?.refusedBy(d.device).orEmpty()
            val culprits = pending.filter { it in declined }
            if (culprits.isNotEmpty()) {
                d.book.refused += culprits
                pending = pending - culprits.toSet()
                continue
            }
            if (pending.size == 1) {
                val address = pending[0]
                if (link?.lastNegativeCode == Ssm.REFUSED_ADDRESS) {
                    d.book.refused += address
                    return
                }
                if (++failures >= SINGLE_ATTEMPTS) {
                    d.book.silent += address
                    return
                }
                continue
            }
            if (++failures >= BATCH_ATTEMPTS) {
                // Refused or ignored as a batch with no single culprit: take them one at a time.
                for (address in pending) classify(d, listOf(address))
                return
            }
        }
    }

    // ---- sampling ----------------------------------------------------------------------

    /**
     * The ECU is sampled through [GuidedSteps]: a beep and an on-screen instruction per
     * step, every sample tagged with the step it was taken in. Anything else (a TCU) just
     * gets [SAMPLE_MS] of plain sampling.
     */
    private fun sample(d: DeviceResult) {
        val readable = d.book.ok.keys.sorted()
        if (readable.isEmpty()) return
        val chunks = readable.chunked(BATCH)
        val began = SystemClock.elapsedRealtime()
        val failed = IntArray(1)
        if (d.device == Ssm.DEVICE_ECU) {
            val steps = GuidedSteps.STEPS
            for ((index, step) in steps.withIndex()) {
                val ends = SystemClock.elapsedRealtime() + step.seconds * 1000L
                main.post { onStep() }
                note("step ${index + 1}: ${step.label}")
                var emptyRounds = 0
                while (true) {
                    val left = ends - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    val next = steps.getOrNull(index + 1)?.let { "\n\nNext: ${it.label}" } ?: "\n\nLast step"
                    progress("STEP ${index + 1} of ${steps.size} · ${(left + 999) / 1000} s\n\n${step.prompt}$next")
                    if (takeSample(d, chunks, failed, index)) {
                        emptyRounds = 0
                    } else if (++emptyRounds >= MAX_EMPTY_ROUNDS) {
                        note("${d.name}: stopped answering during sampling")
                        break
                    }
                }
            }
        } else {
            var emptyRounds = 0
            while (SystemClock.elapsedRealtime() - began < SAMPLE_MS && emptyRounds < MAX_EMPTY_ROUNDS) {
                progress("${d.name}: sampling ${readable.size} addresses · ${d.samples.size} samples")
                if (takeSample(d, chunks, failed, -1)) emptyRounds = 0 else emptyRounds++
            }
        }
        d.sampleMs = SystemClock.elapsedRealtime() - began
        note("${d.name}: ${d.samples.size} samples in ${d.sampleMs} ms, ${failed[0]} batch reads failed")
    }

    /** One pass over every readable address; false if nothing at all came back. */
    private fun takeSample(d: DeviceResult, chunks: List<List<Int>>, failed: IntArray, step: Int): Boolean {
        val sample = HashMap<Int, Byte>()
        for (chunk in chunks) {
            val bytes = io { it.read(chunk.toIntArray(), d.device) }
            if (bytes == null) {
                failed[0]++
                continue
            }
            chunk.forEachIndexed { i, a -> sample[a] = bytes[i] }
        }
        if (sample.isEmpty()) return false
        d.samples += sample
        d.sampleSteps += step
        return true
    }

    // ---- connection --------------------------------------------------------------------

    /**
     * Runs [block] on a live link, reconnecting (and re-running it) if Bluetooth drops.
     * Only failures in a row count against the limit: a link that drops every so often but
     * makes progress in between still finishes the sweep.
     */
    private fun <T> io(block: (StnCanLink) -> T): T {
        while (true) {
            if (cancelled || Thread.currentThread().isInterrupted) throw Cancelled()
            try {
                val l = link ?: connect()
                val result = block(l)
                failedInARow = 0
                return result
            } catch (e: LinkException) {
                if (cancelled) throw Cancelled()
                note("link lost: ${e.message}")
                link?.close()
                link = null
                reconnects++
                if (++failedInARow > MAX_FAILURES_IN_A_ROW) {
                    throw LinkException("gave up after $MAX_FAILURES_IN_A_ROW failed reconnects in a row (${e.message})")
                }
                progress("Bluetooth dropped, reconnecting (try $failedInARow of $MAX_FAILURES_IN_A_ROW)…")
                Thread.sleep(RECONNECT_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(): StnCanLink {
        val device = adapter!!
        progress("Connecting to ${device.name ?: device.address}…")
        val port = try {
            BluetoothSppPort.open(device) { cancelled }
        } catch (e: IOException) {
            if (cancelled) throw Cancelled()
            throw LinkException(e.message ?: "could not open the Bluetooth link")
        }
        val l = StnCanLink(port, ::note)
        link = l
        l.open()
        chipName = l.chipName
        progress("${l.chipName}: waiting for the ECU…")
        val init = l.initialise() ?: throw LinkException("ECU did not answer the init")
        val first = ecuInit
        if (first == null) {
            ecuInit = init
            note("ECU ${init.ecuId}, init reply ${Ssm.hex(init.raw)}")
        } else if (first.ecuId != init.ecuId) {
            throw IllegalStateException("ECU id changed from ${first.ecuId} to ${init.ecuId} after reconnecting")
        }
        return l
    }

    // ---- reporting ---------------------------------------------------------------------

    private fun note(text: String) {
        val t = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        trace += "%8.2f  %s".format(Locale.US, t, text)
        Log.d(TAG, text)
    }

    private fun progress(text: String) {
        note(text.replace('\n', ' '))
        main.post { onProgress(text) }
    }

    private fun writeTrace(dir: File, stamp: String): File? = runCatching {
        File(dir, "sweep-$stamp-trace.txt").apply { writeText(trace.joinToString("\n")) }
    }.getOrNull()

    private companion object {
        const val TAG = "SsmSweep"
        const val BATCH = Ssm.MAX_ADDRESSES_PER_REQUEST
        const val BATCH_ATTEMPTS = 2
        const val SINGLE_ATTEMPTS = 2
        const val TCU_INIT_TIMEOUT_MS = 1200
        const val SAMPLE_MS = 20_000L
        const val MAX_FAILURES_IN_A_ROW = 5
        const val MAX_EMPTY_ROUNDS = 3
        const val RECONNECT_DELAY_MS = 1500L
        const val SETTLE_AFTER_CLAIM_MS = 1500L
    }
}

/** What one device (ECU or TCU) gave back. Filled in as the sweep runs. */
class DeviceResult(
    val name: String,
    val device: Int,
    val init: EcuInitResponse,
    val params: List<LogParam>,
    val switches: List<SwitchDef>,
    val dtcBytes: List<Int>,
) {
    class Book {
        val ok = HashMap<Int, Byte>()
        val refused = LinkedHashSet<Int>()
        val silent = LinkedHashSet<Int>()
    }

    val book = Book()
    val samples = ArrayList<Map<Int, Byte>>()
    /** Index into [GuidedSteps.STEPS] for each sample, or -1 when not guided. */
    val sampleSteps = ArrayList<Int>()
    var sampleMs = 0L
}

class SweepResult(
    val adapterName: String,
    val adapterAddress: String,
    val chipName: String,
    val defs: LoggerDefs,
    val devices: List<DeviceResult>,
    val durationMs: Long,
    val reconnects: Int,
)
