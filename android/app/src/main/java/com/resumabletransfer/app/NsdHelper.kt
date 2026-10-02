package com.resumabletransfer.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PeerDevice(
    val name: String,
    val host: String,
    val port: Int
)

class NsdHelper(private val context: Context) {

    companion object {
        private const val TAG = "NsdHelper"
        const val SERVICE_TYPE = "_nexusflow._tcp."
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Queue for serialized resolves (NsdManager only supports one resolve at a time)
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private val resolvedPeers = mutableMapOf<String, PeerDevice>()

    // ─── Registration (Receiver Side) ────────────────────────────────────────

    fun registerService(deviceName: String, port: Int) {
        unregisterService()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName.take(63)
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed with error code: $errorCode")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed with error code: $errorCode")
            }
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service registered: ${info.serviceName}")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service unregistered: ${info.serviceName}")
            }
        }

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener!!
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service: ${e.message}")
        }
    }

    fun unregisterService() {
        val listener = registrationListener ?: return
        try {
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD unregister error: ${e.message}")
        }
        registrationListener = null
    }

    // ─── Discovery (Sender Side) ──────────────────────────────────────────────

    fun startDiscovery(ownIps: List<String>) {
        stopDiscovery()
        synchronized(resolvedPeers) {
            resolvedPeers.clear()
            _peers.value = emptyList()
        }
        synchronized(resolveQueue) {
            resolveQueue.clear()
            isResolving = false
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "NSD discovery started for $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "NSD discovery stopped")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD service found: ${serviceInfo.serviceName}")
                synchronized(resolveQueue) {
                    resolveQueue.addLast(serviceInfo)
                    if (!isResolving) resolveNext(ownIps)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD service lost: ${serviceInfo.serviceName}")
                synchronized(resolvedPeers) {
                    resolvedPeers.remove(serviceInfo.serviceName)
                    _peers.value = resolvedPeers.values.toList()
                }
            }
        }

        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener!!
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery: ${e.message}")
        }
    }

    private fun resolveNext(ownIps: List<String>) {
        val info = synchronized(resolveQueue) {
            if (resolveQueue.isEmpty()) {
                isResolving = false
                return
            }
            isResolving = true
            resolveQueue.removeFirst()
        }

        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    synchronized(resolveQueue) { resolveNext(ownIps) }
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (host == null) {
                        synchronized(resolveQueue) { resolveNext(ownIps) }
                        return
                    }
                    if (host !in ownIps) {
                        val peer = PeerDevice(
                            name = serviceInfo.serviceName,
                            host = host,
                            port = serviceInfo.port
                        )
                        synchronized(resolvedPeers) {
                            resolvedPeers[serviceInfo.serviceName] = peer
                            _peers.value = resolvedPeers.values.toList()
                        }
                        Log.i(TAG, "Resolved peer: ${peer.name} @ ${peer.host}:${peer.port}")
                    } else {
                        Log.d(TAG, "Filtered out own device at $host")
                    }
                    synchronized(resolveQueue) { resolveNext(ownIps) }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "resolveService error: ${e.message}")
            synchronized(resolveQueue) { resolveNext(ownIps) }
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD stop discovery error: ${e.message}")
        }
        discoveryListener = null
        synchronized(resolveQueue) {
            resolveQueue.clear()
            isResolving = false
        }
    }

    fun destroy() {
        stopDiscovery()
        unregisterService()
    }
}
