package com.ne_bknn.adbinstaller.logging

import android.util.Log

/**
 * Tiny logger with a TRACE level that can write both to Logcat and an optional UI sink.
 *
 * - Default is INFO (good for end-users).
 * - Switch to TRACE when debugging pairing/connect issues.
 */
object AppLog {
    enum class Level {
        ERROR,
        INFO,
        DEBUG,
        TRACE,
    }

    @Volatile
    var level: Level = Level.INFO

    fun e(tag: String, msg: String, t: Throwable? = null, ui: ((String) -> Unit)? = null) =
        log(Level.ERROR, tag, msg, t, ui)

    fun i(tag: String, msg: String, t: Throwable? = null, ui: ((String) -> Unit)? = null) =
        log(Level.INFO, tag, msg, t, ui)

    fun d(tag: String, msg: String, t: Throwable? = null, ui: ((String) -> Unit)? = null) =
        log(Level.DEBUG, tag, msg, t, ui)

    fun t(tag: String, msg: String, t: Throwable? = null, ui: ((String) -> Unit)? = null) =
        log(Level.TRACE, tag, msg, t, ui)

    private fun log(
        msgLevel: Level,
        tag: String,
        msg: String,
        throwable: Throwable?,
        ui: ((String) -> Unit)?,
    ) {
        // Emit if the configured log level is at least as verbose as msgLevel.
        if (msgLevel.ordinal > level.ordinal) return

        val prefix = when (msgLevel) {
            Level.ERROR -> "E"
            Level.INFO -> "I"
            Level.DEBUG -> "D"
            Level.TRACE -> "T"
        }
        val line = buildString {
            append("[$prefix] ")
            append(tag)
            append(": ")
            append(msg)
        }

        // UI sink (e.g. statusLog)
        ui?.invoke(line)
        if (throwable != null && ui != null) {
            ui.invoke(throwableToMultilineString(throwable))
        }

        // Logcat
        when (msgLevel) {
            Level.ERROR -> Log.e(tag, msg, throwable)
            Level.INFO -> Log.i(tag, msg, throwable)
            Level.DEBUG -> Log.d(tag, msg, throwable)
            Level.TRACE -> Log.v(tag, msg, throwable)
        }
    }

    fun throwableToMultilineString(t: Throwable): String {
        // Include cause chain + stack.
        return Log.getStackTraceString(t).trimEnd()
    }
}


