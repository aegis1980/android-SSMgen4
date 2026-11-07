package com.fitc.ssmgen4.elm

import java.util.concurrent.CopyOnWriteArraySet

/**
 * The adapter takes one Bluetooth serial client at a time, and the phone screen and the
 * car session each run their own source in this process. Something that needs the adapter
 * to itself (the parameter sweep) [claim]s it: every registered holder is told to let go,
 * and holders stay off until [release].
 */
object AdapterGate {

    /** Why the adapter is reserved, or null when anyone may use it. */
    @Volatile
    var reservedFor: String? = null
        private set

    val reserved: Boolean get() = reservedFor != null

    private val holders = CopyOnWriteArraySet<() -> Unit>()

    /** [letGo] closes the holder's connection; it is called from the claiming thread. */
    fun register(letGo: () -> Unit) {
        holders += letGo
    }

    fun unregister(letGo: () -> Unit) {
        holders -= letGo
    }

    fun claim(reason: String) {
        reservedFor = reason
        for (letGo in holders) runCatching { letGo() }
    }

    fun release() {
        reservedFor = null
    }
}
