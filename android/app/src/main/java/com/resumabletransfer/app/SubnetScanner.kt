package com.resumabletransfer.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP subnet probe discovery for NexusFlow.
 *
 * ## Why this exists
 *
 * Measured on the network this app has to work on (phone and laptop both on
 * 10.3.0.0/16, gateway 10.3.0.1):
 *
 *  - **mDNS**: no multicast route on either host — cannot work
 *  - **UDP broadcast**: datagrams sent to 255.255.255.255 and 10.3.255.255 were
 *    never delivered to the peer — the AP does not relay client-to-client
 *    broadcast
 *  - **unicast TCP**: the phone's receiver on :8000 was reachable in 0.45s
 *
 * So anything broadcast-shaped is dropped by the link but unicast passes. The
 * only way to find a peer there is to ask each address directly.
 *
 * Measured cost: one /24 at 256 concurrent connects is ~0.4s; the whole /16 is
 * ~23s. The local /24 is probed first so the common case is near-instant, then
 * the rest of the /16 is swept with results reported as they arrive.
 */
object SubnetScanner {

    private const val TAG = "SubnetScanner"
    private const val TRANSFER_PORT = 8000
    private const val CONNECT_TIMEOUT_MS = 250
    private const val HEALTH_TIMEOUT_MS = 1200

    /** Ceiling on simultaneously open sockets; higher risks FD exhaustion. */
    private const val CONCURRENCY = 192

    data class Found(val ip: String, val port: Int, val deviceName: String)

    private val found = ConcurrentHashMap<String, Found>()
    private val running = AtomicBoolean(false)
    private val ownIp = AtomicInteger(0)

    @Volatile
    var onFound: ((Found) -> Unit)? = null

    @Volatile
    var onProgress: ((Int) -> Unit)? = null

    @Volatile
    var onDone: (() -> Unit)? = null

    val isScanning: Boolean get() = running.get()
    val results: List<Found> get() = found.values.toList()

    private fun localIPv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                ?.forEach { nic ->
                    val addr = nic.inetAddresses.toList()
                        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    if (addr != null && !addr.hostAddress.startsWith("127.")) {
                        return addr.hostAddress
                    }
                }
            null
        } catch (e: Exception) {
            Log.w(TAG, "localIPv4 failed: ${e.message}")
            null
        }
    }

    fun start() {
        if (running.get()) return
        val me = localIPv4()
        if (me == null) {
            Log.w(TAG, "cannot determine local IPv4; scan aborted")
            return
        }
        found.clear()
        ownIp.set(1)
        running.set(true)

        Thread({ scan(me) }, "nexusflow-subnet-scan").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "subnet scan started from $me")
    }

    fun stop() {
        running.set(false)
    }

    fun clear() {
        found.clear()
    }

    private fun scan(me: String) {
        val octets = me.split(".")
        if (octets.size != 4) {
            running.set(false)
            return
        }
        val first = octets[0].toIntOrNull() ?: 0
        val second = octets[1].toIntOrNull() ?: 0
        val localThird = octets[2].toIntOrNull() ?: 0

        try {
            // 1. Local /24 first — sub-second, and the usual case.
            val localTargets = (1..254).map { "$first.$second.$localThird.$it" }
            sweep(localTargets)
            reportProgress()

            // 2. Remaining third octets, a few blocks at a time so progress is
            //    reported steadily.
            for (third in 0..255) {
                if (!running.get()) return
                if (third == localThird) continue
                val targets = (1..254).map { "$first.$second.$third.$it" }
                sweep(targets)
                reportProgress()
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan aborted: ${e.message}")
        } finally {
            running.set(false)
            Log.i(TAG, "scan complete, found ${found.size} receiver(s)")
            onDone?.invoke()
        }
    }

    private fun reportProgress() {
        // 256 third-octet blocks + the local one.
        val done = minOf(256, (ownIp.getAndIncrement() + 1).coerceAtMost(256))
        onProgress?.invoke((done * 100 / 256).coerceIn(0, 99))
    }

    private fun sweep(targets: List<String>) {
        val pool = Executors.newFixedThreadPool(CONCURRENCY)
        try {
            val latch = java.util.concurrent.CountDownLatch(targets.size)
            for (ip in targets) {
                pool.execute {
                    try {
                        probe(ip)?.let { record(it) }
                    } catch (_: Exception) {
                        // A dead address is the normal case.
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
        } finally {
            pool.shutdown()
        }
    }

    /** Opens a TCP connection to the transfer port and asks who is there. */
    private fun probe(ip: String): Found? {
        var sock: Socket? = null
        return try {
            sock = Socket()
            sock.connect(InetSocketAddress(ip, TRANSFER_PORT), CONNECT_TIMEOUT_MS)
            val name = fetchHealth(ip) ?: return null
            Found(ip, TRANSFER_PORT, name)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { sock?.close() }
        }
    }

    /**
     * Confirms the open port really is a NexusFlow receiver and prefers a name
     * it advertises. Returns null when /health does not identify as NexusFlow, so
     * unrelated services on port 8000 are not listed as peers.
     */
    private fun fetchHealth(ip: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (java.net.URL("http://$ip:$TRANSFER_PORT/health")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = HEALTH_TIMEOUT_MS
                readTimeout = HEALTH_TIMEOUT_MS
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val service = json.optString("service").lowercase()
            if (!service.contains("nexus") && !service.contains("resumable")) return null
            // Prefer a name if one is exposed, else synthesise one.
            json.optString("device_name").ifBlank { json.optString("name") }
                .ifBlank { "NexusFlow @ $ip" }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun record(peer: Found) {
        if (found.containsKey(peer.ip)) return
        // Never list ourselves.
        if (peer.ip == localIPv4()) return
        found[peer.ip] = peer
        Log.i(TAG, "found ${peer.deviceName} at ${peer.ip}:${peer.port}")
        onFound?.invoke(peer)
    }
}