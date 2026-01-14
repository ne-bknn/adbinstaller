package com.ne_bknn.adbinstaller.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.core.app.NotificationManagerCompat
import com.ne_bknn.adbinstaller.install.AdbInstaller
import com.ne_bknn.adbinstaller.logging.AppLog
import com.ne_bknn.adbinstaller.state.PairingStateStore

class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val results = RemoteInput.getResultsFromIntent(intent)
        val code = results?.getCharSequence(PairingNotification.REMOTE_INPUT_KEY_CODE)?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                pending.finish()
                return
            }

        val host = intent.getStringExtra(PairingNotification.EXTRA_HOST)
        val pairingPort = intent.getIntExtra(PairingNotification.EXTRA_PAIRING_PORT, -1)
        if (host.isNullOrBlank() || pairingPort <= 0) {
            PairingNotification.showPairingProgress(
                context,
                host = host ?: "?",
                pairingPort = pairingPort,
                message = "Missing host/port for pairing. Open the app and select a device first.",
                isOngoing = false,
            )
            pending.finish()
            return
        }

        // Best-effort: dismiss the input notification; we will post progress/result separately.
        runCatching { NotificationManagerCompat.from(context).cancel(PairingNotification.NOTIFICATION_ID) }

        Thread {
            try {
                PairingNotification.showPairingProgress(
                    context,
                    host = host,
                    pairingPort = pairingPort,
                    message = "Pairing with $host:$pairingPort …",
                    isOngoing = true,
                )

                val installer = AdbInstaller(context.applicationContext).apply {
                    traceEnabled = true
                    onLog = { line ->
                        // Keep logcat useful even when pairing happens from the notification.
                        AppLog.t("AdbInstaller", "[notif] $line")
                    }
                }

                installer.pair(host = host, pairingPort = pairingPort, pairingCode = code)
                PairingStateStore(context.applicationContext).setPaired(true)

                PairingNotification.showPairingProgress(
                    context,
                    host = host,
                    pairingPort = pairingPort,
                    message = "Paired successfully.",
                    isOngoing = false,
                )
            } catch (t: Throwable) {
                AppLog.e("AdbInstaller", "Pair from notification failed", t)
                PairingNotification.showPairingProgress(
                    context,
                    host = host,
                    pairingPort = pairingPort,
                    message = "Pair failed: ${t.message ?: t::class.java.simpleName}",
                    isOngoing = false,
                )
            } finally {
                pending.finish()
            }
        }.start()
    }
}


