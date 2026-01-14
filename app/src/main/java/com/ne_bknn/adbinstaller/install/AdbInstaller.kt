package com.ne_bknn.adbinstaller.install

import android.content.Context
import android.os.Build
import com.ne_bknn.adbinstaller.apk.ApkSource
import com.ne_bknn.adbinstaller.adb.AdbServices
import com.ne_bknn.adbinstaller.adb.SyncClient
import com.ne_bknn.adbinstaller.crypto.SoftwareBackedKeys
import com.ne_bknn.adbinstaller.logging.AppLog
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.PairingConnectionCtx
import java.util.concurrent.TimeUnit

/**
 * High-level orchestrator used by the UI.
 *
 * Implementation will be completed in subsequent todos (pairing, transport, sync/install).
 */
class AdbInstaller(
    private val appContext: Context,
) {
    var onLog: ((String) -> Unit)? = null
    var traceEnabled: Boolean = false
    private var adb: AdbConnection? = null
    private var connectedHost: String? = null
    private var connectedPort: Int? = null

    fun pair(host: String, pairingPort: Int, pairingCode: String) {
        withTraceLevel {
            AppLog.i(TAG, "Pairing… host=$host pairingPort=$pairingPort", ui = onLog)

            val keys = SoftwareBackedKeys(appContext, KEY_BASENAME) { line ->
                // Make key/cert steps visible in logcat too (at TRACE/VERBOSE).
                AppLog.t(TAG, line, ui = onLog)
            }.getOrCreate()
            AppLog.t(
                TAG,
                "TLS identity: keyAlg=${keys.privateKey.algorithm} certType=${keys.certificate.type} " +
                    "subject=${keys.certificate.subjectX500Principal.name} serial=${keys.certificate.serialNumber} " +
                    "notBefore=${keys.certificate.notBefore} notAfter=${keys.certificate.notAfter}",
                ui = onLog,
            )

            val deviceName = "adbinstaller-${Build.MODEL}".take(64)
            AppLog.t(TAG, "Creating PairingConnectionCtx (deviceName=$deviceName)…", ui = onLog)

            try {
                PairingConnectionCtx(
                    host,
                    pairingPort,
                    pairingCode.toByteArray(Charsets.UTF_8),
                    keys.privateKey,
                    keys.certificate,
                    deviceName,
                ).use { ctx ->
                    AppLog.t(TAG, "Starting TLS pairing handshake…", ui = onLog)
                    ctx.start()
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "Pairing exception: ${t::class.java.name}: ${t.message}", t, ui = onLog)
                throw t
            }

            AppLog.i(TAG, "Paired successfully.", ui = onLog)
        }
    }

    fun connect(host: String, connectPort: Int) {
        withTraceLevel {
            AppLog.i(TAG, "Connecting… host=$host connectPort=$connectPort", ui = onLog)

            val keys = SoftwareBackedKeys(appContext, KEY_BASENAME) { line ->
                AppLog.t(TAG, line, ui = onLog)
            }.getOrCreate()
            val deviceName = "adbinstaller-${Build.MODEL}".take(64)

            adb?.close()
            adb = try {
                AdbConnection.create(host, connectPort, keys.privateKey, keys.certificate).also {
                    it.setDeviceName(deviceName)
                    AppLog.t(TAG, "Connecting transport (timeout=20s)…", ui = onLog)
                    val ok = it.connect(20, TimeUnit.SECONDS, true)
                    check(ok && it.isConnectionEstablished()) { "ADB connection not established" }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "Connect exception: ${t::class.java.name}: ${t.message}", t, ui = onLog)
                throw t
            }

            AppLog.i(TAG, "Connected.", ui = onLog)
            connectedHost = host
            connectedPort = connectPort
        }
    }

    fun ensureConnected(host: String, connectPort: Int) {
        val existing = adb
        val ok = try {
            existing != null && existing.isConnectionEstablished()
        } catch (_: Throwable) {
            false
        }
        if (ok && connectedHost == host && connectedPort == connectPort) return
        connect(host = host, connectPort = connectPort)
    }

    fun install(apk: ApkSource) {
        val adb = requireNotNull(adb) { "Not connected." }
        log("Installing… ${apk.displayName} (${apk.sizeBytes} bytes)")

        val remoteName = "adbinstaller_${System.currentTimeMillis()}.apk"
        val remotePath = "/data/local/tmp/$remoteName"

        log("Pushing to $remotePath …")
        SyncClient(adb).push(localFile = apk.stagedFile, remotePath = remotePath)
        log("Push complete.")

        // Use `cmd package install` so we avoid the normal package installer UI.
        log("Running package install…")
        val output = AdbServices.shell(adb, "cmd package install -r \"$remotePath\"")
        log(output.trim())

        // Basic success check; we'll also surface full output in the UI status log.
        check(output.contains("Success")) { "Install did not report Success" }

        // Best-effort cleanup; ignore failures.
        runCatching { AdbServices.shell(adb, "rm -f \"$remotePath\"") }

        log("Install finished.")
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    private inline fun <T> withTraceLevel(block: () -> T): T {
        val prev = AppLog.level
        AppLog.level = if (traceEnabled) AppLog.Level.TRACE else AppLog.Level.INFO
        return try {
            block()
        } finally {
            AppLog.level = prev
        }
    }

    private companion object {
        private const val TAG = "AdbInstaller"
        const val KEY_BASENAME = "adbinstaller_adb_tls"
    }
}

