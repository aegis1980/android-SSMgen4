package com.ssmgen4.app.elm

import java.io.Closeable

/** A byte pipe to an ELM327-style adapter, whatever carries it. */
interface ElmPort : Closeable {

    fun write(bytes: ByteArray)

    /**
     * Whatever has arrived, waiting up to [timeoutMs] for something.
     * Empty array: nothing came in time. Null: the port is gone.
     */
    fun read(timeoutMs: Long): ByteArray?
}

/** Anything that ends a session with the adapter or the car. */
class LinkException(message: String, cause: Throwable? = null) : Exception(message, cause)
