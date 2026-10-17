package com.bilimusicplayer.core
import com.bilimusicplayer.core.Log

import io.github.aakira.napier.Napier

/**
 * Thin alias for the old android.util.Log call sites. Keeping the two-argument
 * shape makes the shared code read exactly like the Android code it replaced.
 */
object Log {
    fun d(tag: String, message: String) = Napier.d(message = message, tag = tag)

    fun i(tag: String, message: String) = Napier.i(message = message, tag = tag)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        Napier.w(message = message, throwable = throwable, tag = tag)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        Napier.e(message = message, throwable = throwable, tag = tag)
}
