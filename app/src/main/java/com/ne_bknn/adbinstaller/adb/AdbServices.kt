package com.ne_bknn.adbinstaller.adb

import io.github.muntashirakon.adb.AdbConnection
import java.io.ByteArrayOutputStream

object AdbServices {
    /**
     * Runs a shell command and returns combined stdout/stderr as produced by adbd for `shell:`.
     *
     * Note: This is a best-effort helper; some commands may keep the stream open.
     */
    fun shell(adb: AdbConnection, command: String, maxBytes: Int = 512 * 1024): String {
        val stream = adb.open("shell:$command")
        stream.use { s ->
            val input = s.openInputStream()
            val buf = ByteArray(DEFAULT_BUF)
            val out = ByteArrayOutputStream()
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                out.write(buf, 0, read)
                if (out.size() >= maxBytes) break
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private const val DEFAULT_BUF = 16 * 1024
}

