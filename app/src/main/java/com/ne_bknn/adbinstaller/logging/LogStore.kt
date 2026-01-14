package com.ne_bknn.adbinstaller.logging

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple file-backed log buffer for the app UI.
 *
 * - Appends are best-effort; failures are ignored.
 * - Not intended for huge logs; we trim by file size.
 */
class LogStore(
    appContext: Context,
) {
    private val context = appContext.applicationContext

    private val logFile: File = File(context.filesDir, "adbinstaller.log")

    fun append(line: String) {
        val stamped = "${ts()} $line".trimEnd() + "\n"
        runCatching {
            logFile.appendText(stamped)
            trimIfNeeded(maxBytes = 512 * 1024) // 512KB
        }
    }

    fun readText(maxChars: Int = 80_000): String {
        val text = runCatching { logFile.readText() }.getOrDefault("")
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun clear() {
        runCatching { logFile.writeText("") }
    }

    fun buildShareIntent(): Intent? {
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile,
            )
        }.getOrNull() ?: return null

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ADBInstaller log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun logUri(): Uri? {
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile,
            )
        }.getOrNull()
    }

    private fun ts(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun trimIfNeeded(maxBytes: Int) {
        val len = logFile.length()
        if (len <= maxBytes) return

        // Keep last ~75% when trimming.
        val keep = (maxBytes * 0.75).toInt()
        val bytes = logFile.readBytes()
        val start = (bytes.size - keep).coerceAtLeast(0)
        logFile.writeBytes(bytes.copyOfRange(start, bytes.size))
    }
}


