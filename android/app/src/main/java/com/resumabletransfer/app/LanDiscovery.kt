package com.resumabletransfer.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * LanDiscovery — UDP broadcast peer discovery.
 *
 * WHY NOT mDNS?
 * Android's SoftAP (hotspot) does NOT forward multicast packets between
 * Wi-Fi clients. NsdManager / mDNS silently finds nothing when two
 * devices share a phone hotspot. UDP *broadcast* to 255.255.255.255 IS
 * delivered locally by the IP stack on every device, so it works there.
 *
 * Wire protocol (identical to desktop/lan_discovery.py):
 *   query → {"t":"q"}
 *   hello → {"t":"h","n":<name>,"p":<port>,"v":1,"id":<own_ip>}
 *
 * Port: 47777 (distinct from HTTP transfer port 8000)
 */
object LanDiscovery {

    private const val TAG = "LanDiscovery"
    const val DISCOVERY_PORT = 47777
    private const val SCAN_INTERVAL_MS  = 4_000L
    private const val ANNOUNCE_INTERVAL_MS = 3_000L
    private const val PEER_TIMEOUT_MS   = 15_000L

    /** A peer discovered on the LAN. */
    data class LanPeer(
        val name: String,
        val host: String,
        val port: Int,
        val key: String,
        val lastSeen: Long = System.currentTimeMillis()
    )

    // ── Public callbacks ───────────────────────────────────────────────────────

    /** Called on a background thread whenever the peer list changes. */
    @Volatile var onPeersChanged: ((List<LanPeer>) -> Unit)? = null

    // ── Internal state ─────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var scanJob: Job? = null
    private var advertiseJob: Job? = null
    private var listenJob: Job? = null

    private val peerMap = mutableMapOf<String, LanPeer>()
    private val peerLock = Any()

    @Volatile private var ownIps: Set<String> = emptySet()
    @Volatile private var deviceName: String = "NexusFlow Device"
    @Volatile private var servicePort: Int = 8000
    private var multicastLock: WifiManager.MulticastLock? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    fun startAdvertising(context: Context, name: String, port: Int) {
        acquireMulticastLock(context)
        deviceName = name.ifBlank { "NexusFlow Device" }
        servicePort = port
        ownIps = getOwnIps(context)
        stopAdvertise()

        // Periodic hello broadcasts
        advertiseJob = scope.launch {
            Log.i(TAG, "Advertising '$deviceName' on port $servicePort, ownIps=$ownIps")
            while (isActive) {
                broadcastHello()
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }
        ensureListener()
    }

    fun stopAdvertising() = stopAdvertise()

    fun startScanning(context: Context) {
        acquireMulticastLock(context)
        ownIps = getOwnIps(context)
        clearPeers()
        scanJob?.cancel()
        scanJob = scope.launch {
            Log.i(TAG, "LAN scan started (ownIps=$ownIps)")
            while (isActive) {
                sendQuery()
                delay(SCAN_INTERVAL_MS)
                evictStale()
            }
        }
        ensureListener()
    }

    fun stopScanning() {
        scanJob?.cancel(); scanJob = null
        listenJob?.cancel(); listenJob = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock(context: Context) {
        try {
            if (multicastLock == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wm?.createMulticastLock("nexusflow_lan_lock")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.let {
                if (!it.isHeld) it.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "acquireMulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "releaseMulticastLock: ${e.message}")
        }
    }

    /** Trigger an immediate scan query. Used by the Refresh button. */
    fun scanNow() = scope.launch { sendQuery() }

    fun getPeers(): List<LanPeer> = synchronized(peerLock) { peerMap.values.toList() }

    fun destroy() {
        stopAdvertise()
        stopScanning()
    }

    // ── Networking helpers ─────────────────────────────────────────────────────

    private fun stopAdvertise() {
        advertiseJob?.cancel(); advertiseJob = null
    }

    /**
     * Build the list of our own IPv4 addresses.
     * Uses WifiManager for the primary Wi-Fi address plus NetworkInterface
     * enumeration so hotspot/AP and VPN addresses are also included.
     */
    private fun getOwnIps(context: Context): Set<String> {
        val result = mutableSetOf<String>()
        // WifiManager gives us the active Wi-Fi address quickly
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wm.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff, (ipInt shr 8) and 0xff,
                    (ipInt shr 16) and 0xff, (ipInt shr 24) and 0xff
                )
                result += ip
            }
        } catch (_: Exception) {}
        // NetworkInterface covers hotspot/AP interface, USB tethering, etc.
        try {
            for (iface in NetworkInterface.getNetworkInterfaces() ?: return result) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.contains(":")) continue  // skip IPv6
                    result += ip
                }
            }
        } catch (_: Exception) {}
        Log.d(TAG, "Own IPs: $result")
        return result
    }

    /** Broadcast addresses to try: limited + directed subnet. */
    private fun broadcastTargets(): List<String> {
        val out = mutableListOf("255.255.255.255")
        for (ip in ownIps) {
            val parts = ip.split(".")
            if (parts.size == 4 && !ip.startsWith("127.")) {
                out += "${parts[0]}.${parts[1]}.${parts[2]}.255"
            }
        }
        try {
            val nics = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nic in nics) {
                if (!nic.isUp || nic.isLoopback) continue
                for (ia in nic.interfaceAddresses) {
                    val bcast = ia.broadcast?.hostAddress
                    if (bcast != null && !bcast.startsWith("127.")) {
                        out.add(bcast)
                    }
                }
            }
        } catch (_: Exception) {}
        return out.distinct()
    }

    private fun myHello(): String {
        val ownIp = ownIps.firstOrNull { !it.startsWith("127.") } ?: "127.0.0.1"
        return JSONObject()
            .put("t", "h")
            .put("n", deviceName)
            .put("p", servicePort)
            .put("v", 1)
            .put("id", ownIp)
            .toString()
    }

    private fun broadcastHello() {
        val hello = myHello().toByteArray(Charsets.UTF_8)
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                for (addr in broadcastTargets()) {
                    runCatching {
                        sock.send(DatagramPacket(
                            hello, hello.size,
                            InetAddress.getByName(addr), DISCOVERY_PORT
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "broadcastHello error: ${e.message}")
        }
    }

    private fun sendQuery() {
        val query = JSONObject().put("t", "q").toString().toByteArray(Charsets.UTF_8)
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 1500
                for (addr in broadcastTargets()) {
                    runCatching {
                        sock.send(DatagramPacket(
                            query, query.size,
                            InetAddress.getByName(addr), DISCOVERY_PORT
                        ))
                    }
                }
                // Short receive window to pick up direct replies
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 1500
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                        handleMsg(String(pkt.data, 0, pkt.length), pkt.address?.hostAddress ?: "", sock)
                    } catch (_: java.net.SocketTimeoutException) { break }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendQuery error: ${e.message}")
        }
    }

    /** Start the permanent listener (idempotent). */
    private fun ensureListener() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            Log.i(TAG, "UDP listener starting on port $DISCOVERY_PORT")
            try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                    broadcast = true
                    soTimeout = 2000
                }.use { sock ->
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val pkt = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(pkt)
                        } catch (_: java.net.SocketTimeoutException) { continue }
                        handleMsg(
                            String(pkt.data, 0, pkt.length),
                            pkt.address?.hostAddress ?: "",
                            sock
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP listener exited: ${e.message}")
            }
        }
    }

    private fun handleMsg(text: String, senderIp: String, sock: DatagramSocket) {
        val msg = try { JSONObject(text) } catch (_: Exception) { return }
        when (msg.optString("t")) {
            "q" -> {
                // Someone is asking "who's out there?"
                if (advertiseJob?.isActive == true && senderIp !in ownIps) {
                    val hello = myHello().toByteArray(Charsets.UTF_8)
                    runCatching {
                        sock.send(DatagramPacket(
                            hello, hello.size,
                            InetAddress.getByName(senderIp), DISCOVERY_PORT
                        ))
                    }
                    broadcastHello()
                }
            }
            "h" -> {
                val host = msg.optString("id").ifBlank { senderIp }
                if (host in ownIps || senderIp in ownIps) return
                val name = msg.optString("n").ifBlank { host }
                val port = msg.optInt("p", 8000)
                val key  = "${name}|${host}"
                val peer = LanPeer(name, host, port, key)
                var changed = false
                synchronized(peerLock) {
                    if (peerMap[key] == null || peerMap[key]!!.lastSeen < peer.lastSeen) {
                        peerMap[key] = peer
                        changed = true
                    }
                }
                if (changed) {
                    Log.i(TAG, "LAN peer: $name @ $host:$port")
                    onPeersChanged?.invoke(getPeers())
                }
            }
        }
    }

    private fun evictStale() {
        val cutoff = System.currentTimeMillis() - PEER_TIMEOUT_MS
        var changed = false
        synchronized(peerLock) {
            val stale = peerMap.keys.filter { (peerMap[it]?.lastSeen ?: 0) < cutoff }
            stale.forEach { peerMap.remove(it); changed = true }
            if (changed) {}
        }
        if (changed) onPeersChanged?.invoke(getPeers())
    }

    private fun clearPeers() {
        synchronized(peerLock) {
            peerMap.clear()
        }
        onPeersChanged?.invoke(emptyList())
    }
}
