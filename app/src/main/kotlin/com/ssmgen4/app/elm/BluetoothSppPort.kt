package com.ssmgen4.app.elm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Bluetooth Classic serial link to the adapter. The OBDLink LX connects over insecure SPP;
 * the secure variant and the reflective channel-1 fallback are kept for other dongles.
 *
 * The caller must hold BLUETOOTH_CONNECT (API 31+) before opening.
 */
@SuppressLint("MissingPermission")
class BluetoothSppPort private constructor(
    private val socket: BluetoothSocket,
    val how: String,
) : ElmPort {

    private val output: OutputStream = socket.outputStream
    private val incoming = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false

    init {
        Thread({
            val buffer = ByteArray(256)
            val input = socket.inputStream
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) incoming.put(buffer.copyOf(n))
                }
            } catch (_: IOException) {
                // socket went away; fall through and mark closed
            }
            closed = true
        }, "spp-read").apply { isDaemon = true }.start()
    }

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun read(timeoutMs: Long): ByteArray? {
        if (closed && incoming.isEmpty()) return null
        val chunk = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (chunk != null) return chunk
        return if (closed) null else EMPTY
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "SppPort"
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val EMPTY = ByteArray(0)

        /**
         * Connects, trying each socket flavour in turn. [cancelled] is polled while the
         * (blocking) connect is in flight so a stop request can abandon it.
         */
        @Throws(IOException::class)
        fun open(device: BluetoothDevice, cancelled: () -> Boolean = { false }): BluetoothSppPort {
            val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
                "secure SPP" to { device.createRfcommSocketToServiceRecord(SPP) },
                "insecure SPP" to { device.createInsecureRfcommSocketToServiceRecord(SPP) },
                "channel 1" to {
                    device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                },
            )
            var last: Exception? = null
            for ((how, make) in attempts) {
                if (cancelled()) throw IOException("cancelled")
                var socket: BluetoothSocket? = null
                try {
                    socket = make()
                    val watch = Thread({
                        try {
                            while (!cancelled()) Thread.sleep(500)
                            runCatching { socket.close() }
                        } catch (_: InterruptedException) {
                        }
                    }, "spp-connect-watch").apply { isDaemon = true }
                    watch.start()
                    try {
                        socket.connect()
                    } finally {
                        watch.interrupt()
                    }
                    if (cancelled()) throw IOException("cancelled")
                    Log.i(TAG, "connected by $how")
                    return BluetoothSppPort(socket, how)
                } catch (e: Exception) {
                    Log.w(TAG, "$how failed: ${e.message}")
                    runCatching { socket?.close() }
                    last = e
                }
            }
            throw IOException("could not open a serial link: ${last?.message}")
        }
    }
}
