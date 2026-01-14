package com.ne_bknn.adbinstaller

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ne_bknn.adbinstaller.apk.ApkSource
import com.ne_bknn.adbinstaller.install.AdbInstaller
import com.ne_bknn.adbinstaller.logging.AppLog
import com.ne_bknn.adbinstaller.logging.LogStore
import com.ne_bknn.adbinstaller.mdns.AdbMdnsDiscovery
import com.ne_bknn.adbinstaller.notifications.PairingNotification
import com.ne_bknn.adbinstaller.ui.theme.ADBInstallerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IncomingData(
    val pairingCode: String? = null,
    val host: String? = null,
    val pairingPort: Int? = null,
    val connectPort: Int? = null,
    val serviceName: String? = null,
) {
    companion object {
        fun fromIntent(intent: Intent?): IncomingData {
            if (intent == null) return IncomingData()
            val pairingCode = intent.getStringExtra(PairingNotification.EXTRA_PAIRING_CODE)
            val host = intent.getStringExtra(PairingNotification.EXTRA_HOST)
            val pairingPort = intent.getIntExtra(PairingNotification.EXTRA_PAIRING_PORT, -1).takeIf { it > 0 }
            val connectPort = intent.getIntExtra(PairingNotification.EXTRA_CONNECT_PORT, -1).takeIf { it > 0 }
            val serviceName = intent.getStringExtra(PairingNotification.EXTRA_SERVICE_NAME)
            return IncomingData(
                pairingCode = pairingCode,
                host = host,
                pairingPort = pairingPort,
                connectPort = connectPort,
                serviceName = serviceName,
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf(IncomingData())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        incoming = IncomingData.fromIntent(intent)
        setContent {
            ADBInstallerTheme {
                MainScreen(
                    incoming = incoming,
                    onConsumeIncoming = { incoming = IncomingData() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming = IncomingData.fromIntent(intent)
    }
}

@Composable
private fun MainScreen(
    incoming: IncomingData,
    onConsumeIncoming: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Step A: Pair
    var host by rememberSaveable { mutableStateOf("") }
    var pairingPortText by rememberSaveable { mutableStateOf("") }
    var pairingCode by rememberSaveable { mutableStateOf("") }

    // Auto-discovery (mDNS)
    var discoveredDevices by remember { mutableStateOf<List<AdbMdnsDiscovery.Device>>(emptyList()) }
    var selectedServiceName by rememberSaveable { mutableStateOf<String?>(null) }
    var lastNotifiedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var lastNotifiedAtMs by rememberSaveable { mutableStateOf(0L) }

    // Connect port (auto-filled via mDNS). Used for auto-connect on install.
    var connectPortText by rememberSaveable { mutableStateOf("") }

    // Step C: Pick + Install
    var selectedApk by remember { mutableStateOf<ApkSource?>(null) }

    var isBusy by remember { mutableStateOf(false) }
    val logStore = remember { LogStore(context.applicationContext) }

    val installer = remember { AdbInstaller(context.applicationContext) }
    var adbStatusText by remember { mutableStateOf("ADB: disconnected") }
    var isAdbConnected by remember { mutableStateOf(false) }
    var lastAutoConnectAtMs by remember { mutableStateOf(0L) }

    val notifPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        logStore.append(if (granted) "Notifications permission granted." else "Notifications permission denied.")
    }

    val apkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            try {
                val apk = withContext(Dispatchers.IO) { ApkSource.fromUri(context, uri) }
                selectedApk = apk
                logStore.append("Picked APK: ${apk.displayName} (${apk.sizeBytes} bytes)")
            } catch (t: Throwable) {
                logStore.append("Pick failed: ${t.message ?: t::class.java.simpleName}")
            } finally {
                isBusy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        installer.onLog = { line -> logStore.append(line.trimEnd()) }
    }

    LaunchedEffect(Unit) {
        installer.traceEnabled = true
        AppLog.level = AppLog.Level.TRACE
        logStore.append("Trace logs enabled.")
    }

    LaunchedEffect(incoming) {
        // Apply pairing details received from notification.
        val any =
            (incoming.pairingCode != null) ||
                (incoming.host != null) ||
                (incoming.pairingPort != null) ||
                (incoming.connectPort != null)
        if (!any) return@LaunchedEffect

        incoming.host?.let { host = it }
        incoming.pairingPort?.let { pairingPortText = it.toString() }
        incoming.connectPort?.let { connectPortText = it.toString() }
        incoming.pairingCode?.let { pairingCode = it }

        logStore.append("Received pairing data from notification.")
        onConsumeIncoming()
    }

    fun applyDevice(device: AdbMdnsDiscovery.Device) {
        device.hostString?.let { host = it }
        device.pairingPort?.let { pairingPortText = it.toString() }
        device.connectPort?.let { connectPortText = it.toString() }
    }

    DisposableEffect(Unit) {
        val discovery = AdbMdnsDiscovery(context.applicationContext)
        discovery.start(
            onUpdate = { list ->
                discoveredDevices = list

                if (selectedServiceName == null) {
                    val single = list.singleOrNull()
                    if (single?.hostString != null && single.pairingPort != null) {
                        selectedServiceName = single.serviceName
                        applyDevice(single)
                    }
                } else {
                    val selected = list.firstOrNull { it.serviceName == selectedServiceName }
                    if (selected != null) applyDevice(selected)
                }

                // Auto-post pairing notification once mDNS yields a usable endpoint.
                val candidate = list.firstOrNull { it.serviceName == selectedServiceName }
                    ?: list.singleOrNull()
                val h = candidate?.hostString
                val p = candidate?.pairingPort
                val c = candidate?.connectPort

                // Background auto-connect + UI indication.
                if (!h.isNullOrBlank() && c != null) {
                    val connectKey = "$h:$c"
                    if (installer.isConnectedTo(h, c)) {
                        isAdbConnected = true
                        adbStatusText = "ADB: connected to $connectKey"
                    } else {
                        isAdbConnected = false
                        adbStatusText = "ADB: connecting to $connectKey…"

                        val now = System.currentTimeMillis()
                        if (now - lastAutoConnectAtMs > 10_000) {
                            lastAutoConnectAtMs = now
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        installer.ensureConnected(host = h, connectPort = c)
                                    }
                                    isAdbConnected = true
                                    adbStatusText = "ADB: connected to $connectKey"
                                } catch (t: Throwable) {
                                    isAdbConnected = false
                                    adbStatusText = "ADB: disconnected (connect failed)"
                                    logStore.append("Auto-connect failed: ${t.message ?: t::class.java.simpleName}")
                                    AppLog.e("AdbInstaller", "Auto-connect failed", t)
                                }
                            }
                        }
                    }
                } else {
                    isAdbConnected = false
                    adbStatusText = "ADB: waiting for connect port discovery"
                }
                if (!h.isNullOrBlank() && p != null) {
                    val key = "$h:$p"
                    val now = System.currentTimeMillis()
                    val shouldNotify =
                        (lastNotifiedKey != key) || (now - lastNotifiedAtMs > 15_000)

                    if (shouldNotify) {
                        // Don't interrupt the user with permission prompts here; just no-op if blocked.
                        if (PairingNotification.cannotNotifyReason(context) == null) {
                            PairingNotification.show(
                                context = context,
                                host = h,
                                pairingPort = p,
                                connectPort = candidate.connectPort,
                                serviceName = candidate.serviceName,
                                onStatus = { msg -> logStore.append(msg.trimEnd()) },
                            )
                            lastNotifiedKey = key
                            lastNotifiedAtMs = now
                        }
                    }
                }
            },
            onLog = { line -> logStore.append(line.trimEnd()) },
        )
        onDispose { discovery.close() }
    }

    AppScaffold(
        isBusy = isBusy,
        onOpenLogs = {
            context.startActivity(
                Intent(context, LogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        content = { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepHeader(title = "Step A — Pair (Wireless debugging)") {
                    Text("Enable Developer Options → Wireless debugging → Pair device with pairing code.")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        enabled = !isBusy,
                        onClick = {
                            // Best-effort: OEMs differ. Try specific action first, then fall back.
                            val intents = listOf(
                                Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                                Intent(Settings.ACTION_SETTINGS),
                            ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

                            val launched = intents.any { i ->
                                try {
                                    context.startActivity(i)
                                    true
                                } catch (_: ActivityNotFoundException) {
                                    false
                                }
                            }
                            if (!launched) logStore.append("Could not open settings.")
                        },
                    ) { Text("Open Wireless debugging") }

                    Button(
                        enabled = !isBusy,
                        onClick = {
                            if (!PairingNotification.canPostNotifications(context)) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@Button
                            }

                            val port = pairingPortText.toIntOrNull()
                            if (host.isBlank() || port == null) {
                                logStore.append("Select a device so host+pairing port are known.")
                                return@Button
                            }
                            val ok = PairingNotification.show(
                                context = context,
                                host = host,
                                pairingPort = port,
                                connectPort = connectPortText.toIntOrNull(),
                                serviceName = selectedServiceName,
                                onStatus = { msg -> logStore.append(msg.trimEnd()) },
                            )
                            if (!ok) {
                                // If notifications are blocked at the OS/channel level, help user jump there.
                                logStore.append("If you don't see a permission prompt, notifications may be blocked in Settings.")
                                PairingNotification.openChannelNotificationSettings(context)
                            }
                        },
                    ) { Text("PIN via notification") }
                }

                StepHeader(title = "Auto-detect device (mDNS)") {
                    Text("If your device is on the same Wi‑Fi, it should appear below. Then you only need to enter the PIN.")
                }

                if (discoveredDevices.isEmpty()) {
                    Text("Searching for Wireless debugging devices…")
                } else {
                    discoveredDevices.forEach { dev ->
                        val selected = selectedServiceName == dev.serviceName
                        val summary = buildString {
                            append(dev.hostString ?: "?")
                            append("  pairing:")
                            append(dev.pairingPort?.toString() ?: "?")
                            append("  connect:")
                            append(dev.connectPort?.toString() ?: "?")
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isBusy) {
                                    selectedServiceName = dev.serviceName
                                    applyDevice(dev)
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(if (selected) "✓ ${dev.serviceName}" else dev.serviceName)
                            Text(summary)
                        }
                    }
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = pairingCode,
                    onValueChange = { pairingCode = it },
                    label = { Text("Pairing code (PIN)") },
                    singleLine = true,
                )

                Button(
                    enabled = !isBusy &&
                        pairingCode.isNotBlank() &&
                        host.isNotBlank() &&
                        pairingPortText.toIntOrNull() != null,
                    onClick = {
                        scope.launch {
                            isBusy = true
                            try {
                                discoveredDevices.firstOrNull { it.serviceName == selectedServiceName }?.let {
                                    applyDevice(it)
                                }
                                val port = pairingPortText.toIntOrNull() ?: error("Invalid pairing port")
                                withContext(Dispatchers.IO) {
                                    installer.pair(host = host, pairingPort = port, pairingCode = pairingCode)
                                }
                            } catch (t: Throwable) {
                                logStore.append("Pair failed: ${t.message ?: t::class.java.simpleName}")
                                // Also emit to logcat so `adb logcat AdbInstaller:V '*':S` captures it.
                                AppLog.e("AdbInstaller", "Pair failed (caught in UI)", t)
                                logStore.append(AppLog.throwableToMultilineString(t))
                            } finally {
                                isBusy = false
                            }
                        }
                    }
                ) { Text("Pair") }

                Spacer(Modifier.height(12.dp))
                StepHeader(title = "Step C — Pick & Install (.apk)") {}

                Text("Connect port (auto): ${connectPortText.ifEmpty { "?" }}")
                Text(adbStatusText)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !isBusy,
                        onClick = {
                            apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                        }
                    ) { Text("Pick APK") }

                    Button(
                        enabled = !isBusy && selectedApk != null && isAdbConnected,
                        onClick = {
                            val apk = selectedApk ?: return@Button
                            scope.launch {
                                isBusy = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        val connectPort = connectPortText.toIntOrNull()
                                            ?: error("Connect port unknown (not discovered yet)")
                                        installer.ensureConnected(host = host, connectPort = connectPort)
                                        installer.install(apk)
                                    }
                                } catch (t: Throwable) {
                                    logStore.append("Install failed: ${t.message ?: t::class.java.simpleName}")
                                    AppLog.e("AdbInstaller", "Install failed (caught in UI)", t)
                                    logStore.append(AppLog.throwableToMultilineString(t))
                                } finally {
                                    isBusy = false
                                }
                            }
                        }
                    ) { Text("Install via ADB") }
                }

                if (selectedApk != null) {
                    Text("Selected: ${selectedApk!!.displayName}")
                } else {
                    Text("No APK selected.")
                }
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ADBInstallerTheme {
        MainScreen(incoming = IncomingData(), onConsumeIncoming = {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    isBusy: Boolean,
    onOpenLogs: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ADB Installer ${BuildConfig.GIT_SHA}") },
                actions = {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp))
                    }
                    IconButton(onClick = onOpenLogs) {
                        Text("Logs")
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

@Composable
private fun StepHeader(
    title: String,
    subtitle: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title)
        subtitle()
    }
}