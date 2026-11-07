package com.fitc.ssmgen4.elm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/** Finding the OBD adapter among the phone's paired Bluetooth devices. */
object ObdAdapters {

    private val NAMES = listOf("OBDLink", "STN", "ELM", "OBD", "vLinker", "Veepeak")

    fun looksLikeObd(name: String?): Boolean = NAMES.any { (name ?: "").contains(it, ignoreCase = true) }

    /**
     * [preferredAddress] if it is paired, else the first paired device whose name looks like
     * an OBD dongle. Null if Bluetooth is off or nothing matches. Needs BLUETOOTH_CONNECT.
     */
    @SuppressLint("MissingPermission")
    fun choose(context: Context, preferredAddress: String? = null): BluetoothDevice? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        val bonded = adapter.bondedDevices ?: return null
        preferredAddress?.let { want ->
            bonded.firstOrNull { it.address.equals(want, ignoreCase = true) }?.let { return it }
        }
        return bonded.firstOrNull { looksLikeObd(it.name) }
    }
}
