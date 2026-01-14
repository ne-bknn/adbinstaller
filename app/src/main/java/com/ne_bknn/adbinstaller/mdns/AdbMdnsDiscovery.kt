package com.ne_bknn.adbinstaller.mdns

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.Executor

/**
 * Discovers Android "Wireless debugging" endpoints via mDNS.
 *
 * Android advertises (at least) two services:
 * - Pairing:  _adb-tls-pairing._tcp.
 * - Connect:  _adb-tls-connect._tcp.
 *
 * This class merges both into a single per-device view keyed by NSD serviceName.
 */
class AdbMdnsDiscovery(
    appContext: Context,
    private val callbackExecutor: Executor = appContext.mainExecutor,
) : Closeable {
    data class Device(
        val serviceName: String,
        val host: InetAddress?,
        val pairingPort: Int?,
        val connectPort: Int?,
    ) {
        val hostString: String? get() = host?.hostAddress
    }

    private val context = appContext.applicationContext
    private val nsd = requireNotNull(context.getSystemService(NsdManager::class.java)) {
        "NsdManager not available"
    }

    private val devicesByName = linkedMapOf<String, MutableDevice>()
    private var onUpdate: ((List<Device>) -> Unit)? = null
    private var onLog: ((String) -> Unit)? = null

    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null

    private data class MutableDevice(
        var host: InetAddress? = null,
        var pairingPort: Int? = null,
        var connectPort: Int? = null,
    )

    fun start(
        onUpdate: (List<Device>) -> Unit,
        onLog: (String) -> Unit = {},
    ) {
        this.onUpdate = onUpdate
        this.onLog = onLog

        if (pairingListener != null || connectListener != null) {
            // Already started.
            publish()
            return
        }

        pairingListener = createDiscoveryListener(SERVICE_TYPE_PAIRING)
        connectListener = createDiscoveryListener(SERVICE_TYPE_CONNECT)

        nsd.discoverServices(
            SERVICE_TYPE_PAIRING,
            NsdManager.PROTOCOL_DNS_SD,
            null as Network?,
            callbackExecutor,
            requireNotNull(pairingListener),
        )
        nsd.discoverServices(
            SERVICE_TYPE_CONNECT,
            NsdManager.PROTOCOL_DNS_SD,
            null as Network?,
            callbackExecutor,
            requireNotNull(connectListener),
        )

        log("mDNS scan started.")
    }

    override fun close() {
        pairingListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        connectListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        pairingListener = null
        connectListener = null
        log("mDNS scan stopped.")
    }

    private fun createDiscoveryListener(serviceType: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("mDNS start failed ($serviceType): $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("mDNS stop failed ($serviceType): $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                log("mDNS discovery started: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                log("mDNS discovery stopped: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Resolve to get address and port.
                nsd.resolveService(
                    serviceInfo,
                    callbackExecutor,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            log("mDNS resolve failed (${serviceInfo.serviceName}): $errorCode")
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val name = resolved.serviceName
                            val entry = devicesByName.getOrPut(name) { MutableDevice() }
                            entry.host = resolved.host ?: entry.host

                            when (serviceType) {
                                SERVICE_TYPE_PAIRING -> entry.pairingPort = resolved.port
                                SERVICE_TYPE_CONNECT -> entry.connectPort = resolved.port
                                else -> {
                                    // Some devices return a normalized serviceType; be tolerant.
                                    if (resolved.serviceType.contains("adb-tls-pairing")) {
                                        entry.pairingPort = resolved.port
                                    } else if (resolved.serviceType.contains("adb-tls-connect")) {
                                        entry.connectPort = resolved.port
                                    }
                                }
                            }

                            publish()
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                val entry = devicesByName[name] ?: return

                // Only clear the port relevant to the service type; keep the other if present.
                when (serviceInfo.serviceType) {
                    SERVICE_TYPE_PAIRING -> entry.pairingPort = null
                    SERVICE_TYPE_CONNECT -> entry.connectPort = null
                }

                // If nothing remains, remove the device entirely.
                if (entry.pairingPort == null && entry.connectPort == null) {
                    devicesByName.remove(name)
                }
                publish()
            }
        }
    }

    private fun publish() {
        val list = devicesByName.entries.map { (name, m) ->
            Device(
                serviceName = name,
                host = m.host,
                pairingPort = m.pairingPort,
                connectPort = m.connectPort,
            )
        }.sortedWith(compareBy({ it.hostString ?: "\uFFFF" }, { it.serviceName }))
        onUpdate?.invoke(list)
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    private companion object {
        // Trailing dot is the conventional form for NSD service types.
        const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp."
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp."
    }
}


