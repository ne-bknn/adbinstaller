package com.ne_bknn.adbinstaller.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.ne_bknn.adbinstaller.MainActivity
import com.ne_bknn.adbinstaller.R
import com.ne_bknn.adbinstaller.logging.AppLog

object PairingNotification {
    const val CHANNEL_ID = "pairing"
    const val NOTIFICATION_ID = 1001

    const val EXTRA_HOST = "extra_host"
    const val EXTRA_PAIRING_PORT = "extra_pairing_port"
    const val EXTRA_CONNECT_PORT = "extra_connect_port"
    const val EXTRA_SERVICE_NAME = "extra_service_name"

    const val EXTRA_PAIRING_CODE = "extra_pairing_code"

    const val REMOTE_INPUT_KEY_CODE = "remote_input_pairing_code"

    const val NOTIFICATION_ID_PROGRESS = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wireless debugging pairing",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Enter Wireless debugging pairing code"
        }
        nm.createNotificationChannel(channel)
    }

    fun canPostNotifications(context: Context): Boolean {
        // minSdk=34 so this permission exists; still keep the check explicit.
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * @return null if notifications can be shown, otherwise a human-readable reason.
     */
    fun cannotNotifyReason(context: Context): String? {
        if (!canPostNotifications(context)) return "Notifications permission not granted."

        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return "Notifications are disabled for this app."

        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = nm.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return "Pairing notification channel is disabled."
            }
        }

        return null
    }

    fun openAppNotificationSettings(context: Context) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(i) }
    }

    fun openChannelNotificationSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 26) {
            openAppNotificationSettings(context)
            return
        }
        val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(i) }.onFailure {
            openAppNotificationSettings(context)
        }
    }

    fun show(
        context: Context,
        host: String,
        pairingPort: Int,
        connectPort: Int?,
        serviceName: String?,
        onStatus: ((String) -> Unit)? = null,
    ): Boolean {
        ensureChannel(context)
        val reason = cannotNotifyReason(context)
        if (reason != null) {
            onStatus?.invoke(reason)
            AppLog.i("AdbInstaller", "Not posting notification: $reason")
            return false
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PAIRING_PORT, pairingPort)
            connectPort?.let { putExtra(EXTRA_CONNECT_PORT, it) }
            serviceName?.let { putExtra(EXTRA_SERVICE_NAME, it) }
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val applyIntent = Intent(context, PairingCodeReceiver::class.java).apply {
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PAIRING_PORT, pairingPort)
            connectPort?.let { putExtra(EXTRA_CONNECT_PORT, it) }
            serviceName?.let { putExtra(EXTRA_SERVICE_NAME, it) }
        }
        val applyPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            applyIntent,
            // RemoteInput requires a mutable PendingIntent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY_CODE)
            .setLabel("Pairing code")
            .build()

        val title = "ADB pairing"
        val text = "Enter PIN for $host:$pairingPort"

        val posted = runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                // Use a guaranteed icon resource; some devices crash if a notification uses
                // an unsupported/vector-only icon as the small icon.
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH) // <26 only
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent)
                .addAction(
                    NotificationCompat.Action.Builder(
                        R.mipmap.ic_launcher,
                        "Apply",
                        applyPendingIntent,
                    ).addRemoteInput(remoteInput).build(),
                )
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notif)

            // Debug/verification: did the system actually register it as active?
            // If this says "present=true" but you still don't see it, it's likely hidden/silent/DND/UI grouping.
            if (Build.VERSION.SDK_INT >= 23) {
                val active = nm.activeNotifications
                val present = active.any { it.id == NOTIFICATION_ID }
                AppLog.d("AdbInstaller", "Notification active? id=$NOTIFICATION_ID present=$present total=${active.size}")
                onStatus?.invoke("NotificationManager: active=$present total=${active.size}")
            } else {
                onStatus?.invoke("NotificationManager: posted")
            }
            true
        }.onFailure { t ->
            AppLog.e("AdbInstaller", "Failed to post pairing notification", t)
            onStatus?.invoke("Failed to post notification: ${t.message ?: t::class.java.simpleName}")
        }.getOrDefault(false)

        if (posted) {
            onStatus?.invoke("Pairing notification posted.")
        }
        return posted
    }

    fun showPairingProgress(
        context: Context,
        host: String,
        pairingPort: Int,
        message: String,
        isOngoing: Boolean,
    ) {
        ensureChannel(context)
        if (cannotNotifyReason(context) != null) return

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PAIRING_PORT, pairingPort)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            3,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ADB pairing")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(isOngoing)
            .setAutoCancel(!isOngoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppPendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_PROGRESS, notif)
        }.onFailure { t ->
            AppLog.e("AdbInstaller", "Failed to post pairing progress notification", t)
        }
    }
}


