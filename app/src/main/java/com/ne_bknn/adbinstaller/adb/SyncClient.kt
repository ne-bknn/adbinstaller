package com.ne_bknn.adbinstaller.adb

import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Minimal ADB "sync:" client for pushing a single file.
 *
 * Protocol refs (AOSP): SEND/DATA/DONE + OKAY/FAIL.
 */
class SyncClient(
    private val adb: AdbConnection,
) {
    fun push(localFile: File, remotePath: String, mode: Int = MODE_0644) {
        require(localFile.exists()) { "Local file missing: ${localFile.absolutePath}" }
        require(remotePath.startsWith("/")) { "Remote path must be absolute" }

        adb.open("sync:").use { stream ->
            sendSend(stream, remotePath, mode)
            sendData(stream, localFile)
            sendDone(stream)
            readStatus(stream)
        }
    }

    private fun sendSend(stream: AdbStream, remotePath: String, mode: Int) {
        val spec = "$remotePath,$mode"
        writeRequest(stream, "SEND", spec.toByteArray(Charsets.UTF_8))
    }

    private fun sendData(stream: AdbStream, file: File) {
        FileInputStream(file).use { input ->
            val buf = ByteArray(DATA_CHUNK)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                writeRequest(stream, "DATA", buf, read)
            }
        }
    }

    private fun sendDone(stream: AdbStream) {
        val mtimeSeconds = (System.currentTimeMillis() / 1000L).toInt()
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mtimeSeconds)
        writeRequest(stream, "DONE", bb.array())
    }

    private fun readStatus(stream: AdbStream) {
        val id = readExactly(stream, 4).toString(Charsets.US_ASCII)
        val len = readLeInt(stream)
        val payload = if (len > 0) readExactly(stream, len) else ByteArray(0)
        when (id) {
            "OKAY" -> return
            "FAIL" -> {
                val msg = payload.toString(Charsets.UTF_8)
                error("ADB sync FAIL: $msg")
            }
            else -> error("ADB sync unexpected response: id=$id len=$len")
        }
    }

    private fun writeRequest(stream: AdbStream, id: String, payload: ByteArray) {
        writeRequest(stream, id, payload, payload.size)
    }

    private fun writeRequest(stream: AdbStream, id: String, payload: ByteArray, payloadLen: Int) {
        require(id.length == 4)
        require(payloadLen >= 0 && payloadLen <= payload.size)

        stream.write(id.toByteArray(Charsets.US_ASCII), 0, 4)
        stream.write(leInt(payloadLen), 0, 4)
        if (payloadLen > 0) {
            stream.write(payload, 0, payloadLen)
        }
        stream.flush()
    }

    private fun readLeInt(stream: AdbStream): Int {
        val b = readExactly(stream, 4)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readExactly(stream: AdbStream, n: Int): ByteArray {
        var remaining = n
        val out = ByteArray(n)
        var off = 0
        while (remaining > 0) {
            val buf = ByteArray(min(remaining, 16 * 1024))
            val read = stream.read(buf, 0, buf.size)
            if (read <= 0) error("Unexpected EOF reading $n bytes")
            System.arraycopy(buf, 0, out, off, read)
            off += read
            remaining -= read
        }
        return out
    }

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    companion object {
        private const val DATA_CHUNK = 64 * 1024

        // ADB sync expects the mode as decimal; 0644 (octal) => 420 (decimal) is not correct here.
        // Historically ADB uses "path,mode" where mode is the full st_mode bits: 0100644 (octal) => 33188 (decimal).
        const val MODE_0644 = 33188
    }
}

