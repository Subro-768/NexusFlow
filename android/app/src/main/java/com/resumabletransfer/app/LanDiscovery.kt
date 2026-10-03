package com.resumabletransfer.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * UDP broadcast peer discovery for NexusFlow.
 *
 * ## Why this exists
 *
 * `NsdManager`/mDNS does not work on an Android hotspot. SoftAP does not
 * forward multicast between the AP interface and clients, so the two devices
 * never see each other's mDNS announcements. This was the actual cause of
 * "device not showing up when both are on my phone's hotspot".
 *
 * UDP *broadcast* is handled by the local link and does traverse a hotspot:
 * the phone receives it locally (it is the gateway) and the responder socket is
 * bound to `0.0.0.0`. So broadcast is the primary mechanism here, with
 * [com.resumabletransfer.app.NsdHelper] still contributing peers on a real LAN
 * where mDNS is cheap and reliable.
 *
 * ## Wire protocol
 *
 * Identical to `desktop/lan_discovery.py`, so phone and desktop interoperate:
 *  - query `{"t":"q"}`
 *  - hello  `{"t":"h","n":name,"p":port,"v":1,"id":ip}`
 */
object LanDiscovery {

    private const val TAG = "LanDiscovery"

    /** Kept in sync with the desktop implementation. */
    private const val DISCOVERY_PORT = 47777
    private const val ANNOUNCE_INTERVAL_MS = 3_000L
    private const val SCAN_INTERVAL_MS = 4_000L
    private const val PEER_TIMEOUT_MS = 12_000L
    private const val QUERY_RECV_WINDOW_MS = 1_200

    data class LanPeer(
        val name: String,
        val host: String,
        val port: Int,
        val key: String
    ) {
        override fun toString(): String = "LanPeer($name@$host:$port)"
    }

    // ── state ────────────────────────────────────────────────────────────
    private val peers = ConcurrentHashMap<String, Pair<LanPeer, Long>>()
    private val ownIps = mutableSetOf<String>()

    private var responderRunning = AtomicBoolean(false)
    private var scannerRunning = AtomicBoolean(false)
    private var wakeLock: WifiManager.MulticastLock? = null

    /** Fired (on a background thread) when the peer list changes. */
    @Volatile
    var onPeersChanged: ((List<LanPeer>) -> Unit)? = null

    val isAdvertising: Boolean get() = responderRunning.get()
    val isScanning: Boolean get() = scannerRunning.get()

    // ── addressing helpers ───────────────────────────────────────────────

    /** This device's address on the interface that can reach peers. */
    fun localIp(): String {
        // Prefer the hotspot-facing / wlan address over cellular.
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                ?.forEach { nic ->
                    val addr = nic.inetAddresses.toList()
                        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                        ?.hostAddress
                    if (addr != null && !addr.startsWith("127.")) return addr
                }
        } catch (e: Exception) {
            Log.w(TAG, "localIp enumeration failed: ${e.message}")
        }
        return "127.0.0.1"
    }

    /** Limited broadcast plus each interface's directed subnet broadcast. */
    private fun broadcastTargets(): List<String> {
        val out = mutableListOf("255.255.255.255")
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.forEach { nic ->
                    val v4 = nic.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    v4.firstOrNull()?.let { a ->
                        val parts = a.hostAddress?.split(".") ?: return@forEach
                        if (parts.size == 4 && !a.hostAddress.startsWith("127.")) {
                            out.add(parts.subList(0, 3).joinToString(".") + ".255")
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "broadcast enumeration failed: ${e.message}")
        }
        return out.distinct()
    }

    private fun identity(name: String, servicePort: Int): JSONObject =
        JSONObject().apply {
            put("t", "h")
            put("n", name)
            put("p", servicePort)
            put("v", 1)
            put("id", localIp())
        }

    /**
     * Acquires a multicast lock.
     *
     * Not strictly needed for broadcast, but cheap insurance: some WiFi chips in
     * power-save mode drop broadcast frames the same way they drop multicast.
     */
    private fun acquireMulticastLock(context: Context) {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wakeLock = wm.createMulticastLock("nexusflow-lan")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock unavailable: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            wakeLock?.release()
        } catch (e: Exception) {
            // already released
        }
        wakeLock = null
    }

    // ── responder (receiver side) ────────────────────────────────────────

    /**
     * Start announcing this device. Call when the receiver is enabled.
     *
     * Binds to `0.0.0.0` so it answers on the hotspot interface too, not just
     * the primary one.
     */
    fun startAdvertising(context: Context, deviceName: String, servicePort: Int): Boolean {
        stopAdvertising()
        ownIps.clear()
        ownIps.add(localIp())
        acquireMulticastLock(context)
        responderRunning.set(true)

        thread(name = "nexusflow-lan-responder", isDaemon = true) {
            // The responder owns DISCOVERY_PORT; the scanner uses ephemeral ports, so
        // the two never contend for the same number. Binding the wildcard
        // address matters on a hotspot, where the reachable address is the
        // SoftAP one rather than the primary interface.
        val sock = DatagramSocket(DISCOVERY_PORT, InetAddress.getByName("0.0.0.0"))
            try {
                sock.broadcast = true
                sock.soTimeout = 1000

                val payload = identity(deviceName, servicePort).toString().toByteArray()
                var nextAnnounce = 0L

                while (responderRunning.get()) {
                    val now = System.currentTimeMillis()
                    if (now >= nextAnnounce) {
                        for (target in broadcastTargets()) {
                            try {
                                sock.send(
                                    DatagramPacket(
                                        payload, payload.size,
                                        InetAddress.getByName(target), DISCOVERY_PORT
                                    )
                                )
                            } catch (e: Exception) {
                                // A single unreachable broadcast address is fine.
                            }
                        }
                        nextAnnounce = now + ANNOUNCE_INTERVAL_MS
                    }

                    val buf = ByteArray(4096)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        break
                    }

                    val msg = runCatching {
                        JSONObject(String(packet.data, 0, packet.length))
                    }.getOrNull() ?: continue

                    when (msg.optString("t")) {
                        "q" -> {
                            val from = packet.address?.hostAddress
                            // Reply unicast to the asker, then broadcast so any
                            // passive listener also learns about us.
                            if (from != null && from !in ownIps) {
                                runCatching {
                                    sock.send(
                                        DatagramPacket(
                                            payload, payload.size,
                                            InetAddress.getByName(from), DISCOVERY_PORT
                                        )
                                    )
                                }
                            }
                            for (target in broadcastTargets()) {
                                runCatching {
                                    sock.send(
                                        DatagramPacket(
                                            payload, payload.size,
                                            InetAddress.getByName(target), DISCOVERY_PORT
                                        )
                                    )
                                }
                            }
                        }
                        "h" -> {
                            val from = packet.address?.hostAddress
                            val host = msg.optString("id").ifBlank { from ?: "" }
                            if (host.isBlank()) continue
                            if (host in ownIps || (from != null && from in ownIps)) continue
                            val peer = LanPeer(
                                name = msg.optString("n").ifBlank { host },
                                host = host,
                                port = msg.optInt("p", 8000),
                                key = "${msg.optString("n")}|$host"
                            )
                            val changed = notePeer(peer)
                            if (changed) fire()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "responder stopped: ${e.message}")
            } finally {
                runCatching { sock.close() }
            }
        }
        Log.i(TAG, "Advertising '$deviceName' on port $servicePort")
        return true
    }

    fun stopAdvertising() {
        responderRunning.set(false)
        releaseMulticastLock()
    }

    // ── browser (sender side) ────────────────────────────────────────────

    /** Start scanning for peers in the background. */
    fun startScanning(context: Context) {
        stopScanning()
        ownIps.clear()
        ownIps.add(localIp())
        acquireMulticastLock(context)
        scannerRunning.set(true)

        thread(name = "nexusflow-lan-scan", isDaemon = true) {
            while (scannerRunning.get()) {
                scanOnce()
                expireStale()
                try {
                    Thread.sleep(SCAN_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
        }
        Log.i(TAG, "Scanning for peers on $DISCOVERY_PORT")
    }

    fun stopScanning() {
        scannerRunning.set(false)
        releaseMulticastLock()
    }

    /** Immediate query, used by the Refresh button. */
    fun scanNow() {
        thread(name = "nexusflow-lan-scan-now", isDaemon = true) { scanOnce() }
    }

    private fun scanOnce() {
        val sock = DatagramSocket()
        try {
            sock.broadcast = true
            sock.soTimeout = 300
            val query = JSONObject().put("t", "q").toString().toByteArray()
            for (target in broadcastTargets()) {
                runCatching {
                    sock.send(
                        DatagramPacket(
                            query, query.size, InetAddress.getByName(target), DISCOVERY_PORT
                        )
                    )
                }
            }

            var changed = false
            val deadline = System.currentTimeMillis() + QUERY_RECV_WINDOW_MS
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val msg = runCatching {
                    JSONObject(String(packet.data, 0, packet.length))
                }.getOrNull() ?: continue
                if (msg.optString("t") != "h") continue

                val from = packet.address?.hostAddress
                val host = msg.optString("id").ifBlank { from ?: "" }
                if (host.isBlank()) continue
                if (host in ownIps || (from != null && from in ownIps)) continue

                val peer = LanPeer(
                    name = msg.optString("n").ifBlank { host },
                    host = host,
                    port = msg.optInt("p", 8000),
                    key = "${msg.optString("n")}|$host"
                )
                if (notePeer(peer)) changed = true
            }
            if (changed) fire()
        } catch (e: Exception) {
            Log.w(TAG, "scan failed: ${e.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun notePeer(peer: LanPeer): Boolean {
        val previous = peers[peer.key]
        peers[peer.key] = peer to System.currentTimeMillis()
        return previous?.first?.host != peer.host
    }

    private fun expireStale() {
        val cutoff = System.currentTimeMillis() - PEER_TIMEOUT_MS
        var changed = false
        val it = peers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.second < cutoff) {
                it.remove()
                changed = true
            }
        }
        if (changed) fire()
    }

    private fun fire() {
        onPeersChanged?.invoke(getPeers())
    }

    fun getPeers(): List<LanPeer> =
        peers.values.map { it.first }.sortedBy { it.name }

    fun clearPeers() {
        peers.clear()
    }

    /** Maps a broadcast peer onto the shared [PeerDevice] shape the UI uses. */
    fun PeerDevice(name: String, host: String, port: Int) =
        com.resumabletransfer.app.PeerDevice(name, host, port)
}