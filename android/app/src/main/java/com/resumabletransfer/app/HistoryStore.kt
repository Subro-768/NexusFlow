package com.resumabletransfer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One file that moved, in either direction.
 *
 * [direction] is [DIRECTION_SENT] when this device uploaded the file and
 * [DIRECTION_RECEIVED] when it was the receiver.
 */
@androidx.compose.runtime.Immutable
data class HistoryEntry(
    val id: String,
    val peerName: String,
    val peerHost: String,
    val port: Int,
    val filename: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val status: String,
    val timestampMillis: Long,
    val direction: String,
    val sha256: String? = null
) {
    val isComplete: Boolean get() = status.equals("COMPLETED", ignoreCase = true)

    companion object {
        const val DIRECTION_SENT = "SENT"
        const val DIRECTION_RECEIVED = "RECEIVED"
    }
}

/** A peer with its transfers rolled up. */
@androidx.compose.runtime.Immutable
data class PeerHistory(
    val key: String,
    val displayName: String,
    val host: String,
    val entries: List<HistoryEntry>
) {
    val fileCount: Int get() = entries.size
    val totalBytes: Long get() = entries.sumOf { it.totalBytes }
    val lastActivityMillis: Long get() = entries.maxOfOrNull { it.timestampMillis } ?: 0L
}

/**
 * Device-local transfer history.
 *
 * The old implementation called `GET /transfers` on whichever server the app
 * was currently pointed at, so the History screen only ever showed transfers
 * for the one device selected at that moment, and switching peers made the list
 * appear empty. This store keeps every transfer on the phone, keyed by peer, so
 * the list is cumulative and survives switching targets, restarts and being
 * offline.
 *
 * Backed by a single JSON blob in SharedPreferences: the volume is small (one
 * entry per file transfer) and it keeps writes atomic.
 */
class HistoryStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "nexusflow_history"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 500

        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) {
                instance ?: HistoryStore(context).also { instance = it }
            }
    }

    private fun readAll(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(
                    id = o.optString("id"),
                    peerName = o.optString("peerName"),
                    peerHost = o.optString("peerHost"),
                    port = o.optInt("port", 8000),
                    filename = o.optString("filename"),
                    totalBytes = o.optLong("totalBytes"),
                    transferredBytes = o.optLong("transferredBytes"),
                    status = o.optString("status"),
                    timestampMillis = o.optLong("timestampMillis"),
                    direction = o.optString("direction", HistoryEntry.DIRECTION_SENT),
                    sha256 = if (o.isNull("sha256")) null else o.optString("sha256")
                )
            }
        } catch (e: Exception) {
            // A corrupt blob must not break the screen; start clean instead.
            emptyList()
        }
    }

    private fun writeAll(entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.take(MAX_ENTRIES).forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("peerName", e.peerName)
                    put("peerHost", e.peerHost)
                    put("port", e.port)
                    put("filename", e.filename)
                    put("totalBytes", e.totalBytes)
                    put("transferredBytes", e.transferredBytes)
                    put("status", e.status)
                    put("timestampMillis", e.timestampMillis)
                    put("direction", e.direction)
                    if (e.sha256 != null) put("sha256", e.sha256)
                }
            )
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    /**
     * Inserts or updates an entry, de-duplicating on [HistoryEntry.id] so a
     * progress update replaces its earlier record instead of adding a new row.
     */
    @Synchronized
    fun upsert(entry: HistoryEntry) {
        val current = readAll().toMutableList()
        val existing = current.indexOfFirst { it.id == entry.id && it.id.isNotEmpty() }
        if (existing >= 0) current[existing] = entry else current.add(0, entry)
        writeAll(current.sortedByDescending { it.timestampMillis })
    }

    @Synchronized
    fun all(): List<HistoryEntry> = readAll().sortedByDescending { it.timestampMillis }

    /**
     * Groups every stored transfer by peer.
     *
     * Keyed on `name|host|port` so the same physical device stays one row even
     * if it is rediscovered with a different address.
     */
    @Synchronized
    fun peers(): List<PeerHistory> {
        val grouped = all().groupBy { "${it.peerName}|${it.peerHost}|${it.port}" }
        return grouped.values
            .map { list ->
                val first = list.first()
                PeerHistory(
                    key = "${first.peerName}|${first.peerHost}|${first.port}",
                    // Fall back to the host if the peer never advertised a name.
                    displayName = first.peerName.ifBlank { first.peerHost },
                    host = first.peerHost,
                    entries = list.sortedByDescending { it.timestampMillis }
                )
            }
            .sortedByDescending { it.lastActivityMillis }
    }

    @Synchronized
    fun clear() = writeAll(emptyList())
}
