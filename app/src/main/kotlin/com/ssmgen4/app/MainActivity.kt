package com.ssmgen4.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ssmgen4.app.data.Metric
import com.ssmgen4.app.data.SsmMetricSource
import com.ssmgen4.app.elm.ObdAdapters
import com.ssmgen4.app.sweep.ParameterSweep

/**
 * Phone-side screen. The dials live on the head unit; this exists to get the Bluetooth
 * permission granted (a CarAppService has no window to ask from) and, while it is in the
 * foreground, to run the same SSM source and show its status and readings. That makes it
 * the in-car diagnostic: everything the head unit would do, visible on the phone.
 *
 * Only one client can hold the adapter's SPP socket, so this stops its source in onPause
 * rather than fight the car session for it.
 *
 * It shows over the lock screen and keeps the display on: a dashboard that disappears when
 * the phone locks in its cradle is useless, and nothing on this screen is sensitive.
 *
 * The button runs a [ParameterSweep] instead of the live readings. It can also be started
 * from a computer: `adb shell am start -n com.ssmgen4.app/.MainActivity --ez sweep true`.
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { LIVE, SWEEPING, SWEPT }

    private lateinit var status: TextView
    private lateinit var readings: TextView
    private lateinit var action: Button
    private var source: SsmMetricSource? = null
    private var sweep: ParameterSweep? = null
    private var mode = Mode.LIVE
    private var sweepRequested = false
    private val latest = LinkedHashMap<String, Float>()
    private var beeper: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(padding, padding, padding, padding)
        }
        // From Android 15 the window draws under the status and navigation bars, so keep the
        // content clear of them. Older releases report no bar insets here.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.phone_connect_hint)
            setTextColor(COLOUR_DIM)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, padding / 2, 0, 0)
        })
        status = TextView(this).apply {
            setTextColor(COLOUR_ACCENT)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, padding, 0, padding / 2)
        }
        root.addView(status)
        readings = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        root.addView(
            ScrollView(this).apply { addView(readings) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        action = Button(this).apply {
            setOnClickListener { onAction() }
        }
        root.addView(action)
        setContentView(root)
        showMode(Mode.LIVE)
        sweepRequested = intent.getBooleanExtra(EXTRA_SWEEP, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                REQUEST_BLUETOOTH,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SWEEP, false)) sweepRequested = true
    }

    override fun onResume() {
        super.onResume()
        if (!describeAdapter()) return
        when {
            sweepRequested && mode != Mode.SWEEPING -> {
                sweepRequested = false
                startSweep()
            }
            mode == Mode.LIVE -> startSource()
            else -> Unit
        }
    }

    override fun onPause() {
        // A running sweep carries on: it holds the adapter through AdapterGate and only
        // takes a minute or two. The live source stops so the car session can have the link.
        stopSource()
        super.onPause()
    }

    override fun onDestroy() {
        sweep?.cancel()
        sweep = null
        beeper?.release()
        beeper = null
        super.onDestroy()
    }

    // ---- parameter sweep ---------------------------------------------------------------

    private fun onAction() {
        when (mode) {
            Mode.LIVE -> if (describeAdapter()) startSweep()
            Mode.SWEEPING -> {
                sweep?.cancel()
                status.text = "Cancelling…"
            }
            Mode.SWEPT -> {
                showMode(Mode.LIVE)
                if (describeAdapter()) startSource()
            }
        }
    }

    private fun startSweep() {
        stopSource()
        showMode(Mode.SWEEPING)
        readings.text = "Engine running, car parked. Reads only; nothing is written to the ECU."
        sweep = ParameterSweep(this).also { s ->
            s.start(
                onProgress = { text -> status.text = text },
                onFinished = { result ->
                    sweep = null
                    beep(long = true)
                    status.text = if (result.ok) "Sweep finished. All done, thanks." else "Sweep stopped"
                    readings.text = result.summary
                    showMode(Mode.SWEPT)
                },
                onStep = { beep(long = false) },
            )
        }
    }

    private fun showMode(next: Mode) {
        mode = next
        action.text = when (next) {
            Mode.LIVE -> "Full parameter sweep"
            Mode.SWEEPING -> "Cancel sweep"
            Mode.SWEPT -> "Back to live readings"
        }
        val report = next != Mode.LIVE
        readings.textSize = if (report) 12f else 20f
        readings.gravity = if (report) Gravity.START else Gravity.CENTER
        // Guided steps are read at arm's length in the car, so the instruction line is big.
        status.textSize = if (next == Mode.SWEEPING) 24f else 15f
    }

    /** A cue to look at the phone: short at each guided step, long when the sweep ends. */
    private fun beep(long: Boolean) {
        val tone = beeper ?: runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()?.also { beeper = it }
        tone?.startTone(if (long) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, if (long) 600 else 250)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // onResume runs after the dialog closes and picks the connection up from there.
    }

    // ---- live connection ---------------------------------------------------------------

    private fun startSource() {
        if (source != null) return
        latest.clear()
        render(emptyList())
        source = SsmMetricSource(this).also { s ->
            s.start(
                onReadings = { values ->
                    latest.putAll(values)
                    render(s.metrics)
                },
                onStatus = { text -> status.text = text },
            )
        }
    }

    private fun stopSource() {
        source?.stop()
        source = null
    }

    private fun render(metrics: List<Metric>) {
        readings.text = metrics.joinToString("\n") { m ->
            val v = latest[m.id]
            val shown = if (v == null) "—" else "%.${m.decimals}f".format(v)
            "%-12s %8s %s".format(m.label, shown, m.unit)
        }
    }

    // ---- preconditions -----------------------------------------------------------------

    /** Writes the adapter situation to the status line; true if connecting is worth trying. */
    @SuppressLint("MissingPermission")
    private fun describeAdapter(): Boolean {
        if (!hasBluetoothPermission()) {
            status.text = "Bluetooth permission needed to reach the OBD adapter"
            return false
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            status.text = "Bluetooth is off"
            return false
        }
        val obd = adapter.bondedDevices.orEmpty().firstOrNull { ObdAdapters.looksLikeObd(it.name) }
        if (obd == null) {
            status.text = "No paired OBD adapter — pair it in Android Bluetooth settings"
            return false
        }
        status.text = "Adapter: ${obd.name} (${obd.address})"
        return true
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REQUEST_BLUETOOTH = 1
        const val EXTRA_SWEEP = "sweep"
        const val COLOUR_DIM = 0xFF9AA7B0.toInt()
        const val COLOUR_ACCENT = 0xFF35C5F0.toInt()
    }
}
