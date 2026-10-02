package com.resumabletransfer.app

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * One file handed to the app by the Android Sharesheet.
 *
 * [stagedFile] is a copy inside app-private storage. See [SharedFileStager] for
 * why the copy is necessary.
 */
@androidx.compose.runtime.Immutable
data class SharedFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val stagedFile: File?
) {
    /** True when the file was readable and metadata was recovered. */
    val isUsable: Boolean get() = stagedFile != null && stagedFile.exists()
}

/**
 * Stages incoming share URIs into app-private storage.
 *
 * ## Why a copy is needed
 *
 * A share grants a *temporary* read permission on a `content://` URI that lasts
 * only as long as the receiving Activity's task. Transfers here can run for
 * many minutes from a foreground service after the Activity is gone, so holding
 * the raw URI is not safe: the grant can be revoked mid-transfer and
 * `ContentResolver.openInputStream()` starts throwing.
 *
 * `ACTION_SEND` URIs also have no persistable permission to take (unlike
 * `OpenDocument`, which the in-app picker uses), so the only reliable way to keep
 * the bytes readable for the life of the transfer is to copy them somewhere we
 * own. Files land in `cacheDir/shared/`, which the system may reclaim under
 * storage pressure, and are deleted by [cleanupStale] once no longer referenced.
 *
 * The content is written byte-for-byte, so the SHA-256 the transfer computes is
 * the hash of the original file.
 */
object SharedFileStager {

    private const val TAG = "SharedFileStager"
    private const val STAGE_DIR = "shared"
    private const val MAX_ENTRIES = 500

    /** How long a staged file is kept before it is considered reclaimable. */
    private const val RETENTION_MS = 24L * 60L * 60L * 1000L

    private fun stageDir(context: Context): File =
        File(context.cacheDir, STAGE_DIR).apply { if (!exists()) mkdirs() }

    /**
     * Copies [uri] into app storage and returns a [SharedFile].
     *
     * Never throws: an unreadable URI or a missing permission comes back with
     * `stagedFile == null` so the UI can explain the failure instead of crashing.
     */
    fun stage(context: Context, uri: Uri, fallbackMime: String?): SharedFile {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: fallbackMime ?: "application/octet-stream"

        val displayName = queryName(resolver, uri) ?: uri.lastPathSegment ?: "shared_file"
        val declaredSize = querySize(resolver, uri)

        val target = File(stageDir(context), stagedName(uri, displayName))
        return try {
            // COPY CANCELLED so a slow or huge provider doesn't ANR the UI thread.
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                    }
                    if (copied == 0L) {
                        // A zero-byte share is legitimate (empty file), but a
                        // provider that hands back nothing is not. Trust a
                        // declared size of 0 as an intentionally empty file.
                        if (declaredSize != null && declaredSize > 0L) {
                            throw IllegalStateException("provider returned no data")
                        }
                    }
                }
            } ?: throw IllegalStateException("no stream for uri")

            val actual = target.length()
            SharedFile(
                uri = uri,
                name = displayName,
                sizeBytes = if (actual > 0L) actual else (declaredSize ?: 0L),
                mimeType = mime,
                stagedFile = target
            )
        } catch (e: Exception) {
            Log.w(TAG, "stage failed for $uri: ${e.message}")
            target.delete()
            SharedFile(
                uri = uri,
                name = displayName,
                sizeBytes = declaredSize ?: 0L,
                mimeType = mime,
                stagedFile = null
            )
        }
    }

    /** Stages several URIs, preserving order and reporting per-file failures. */
    fun stageAll(context: Context, uris: List<Uri>, mime: String?): List<SharedFile> =
        uris.map { stage(context, it, mime) }

    /**
     * Stable per-URI key embedded in staged filenames.
     *
     * Lets [stagedFileFor] re-attach the queue to already-copied files after a
     * configuration change, instead of copying the shared content twice. A
     * truncated MD5 is ample here: it only has to be unique within one app's
     * cache directory, not resistant to collision attacks.
     */
    private fun uriKey(uri: Uri): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(uri.toString().toByteArray(Charsets.UTF_8))
        return (0 until 6)
            .map { String.format(Locale.US, "%02x", bytes[it]) }
            .joinToString("")
    }

    /**
     * Returns the on-disk copy previously staged for [uri], or null when it has
     * been deleted or reclaimed from the cache.
     */
    fun stagedFileFor(context: Context, uri: Uri): File? {
        val prefix = "share_${uriKey(uri)}_"
        return stageDir(context).listFiles()?.firstOrNull { candidate ->
            candidate.isFile && candidate.getName().startsWith(prefix)
        }
    }

    /** Rebuilds a [SharedFile] for a URI that is already staged. */
    fun describeExisting(context: Context, uri: Uri): SharedFile {
        val file = stagedFileFor(context, uri)
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val displayName = file?.getName()?.substringAfterLast('_')?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "shared_file"
        return SharedFile(
            uri = uri,
            name = displayName,
            sizeBytes = file?.length() ?: 0L,
            mimeType = mime,
            stagedFile = file
        )
    }

    /**
     * Deletes staged files older than the retention window.
     *
     * Only touches this class's own directory, and never removes a file newer
     * than the window, so a transfer that has been queued but not started is
     * safe.
     */
    fun cleanupStale(context: Context) {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            stageDir(context).listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    if (file.delete()) {
                        Log.i(TAG, "cleaned stale staged file ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanup failed: ${e.message}")
        }
    }

    /** Removes every staged file. Called when the share is dismissed. */
    fun clearAll(context: Context) {
        try {
            stageDir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "clearAll failed: ${e.message}")
        }
    }

    /** Keeps the staged directory bounded so a long session cannot fill storage. */
    fun trimToBudget(context: Context) {
        try {
            val files = stageDir(context).listFiles()?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            if (files.size <= MAX_ENTRIES) return
            files.drop(MAX_ENTRIES).forEach { old ->
                if (System.currentTimeMillis() - old.lastModified() > RETENTION_MS) {
                    old.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "trim failed: ${e.message}")
        }
    }

    /**
     * Staged filename: `share_<uriKey>_<timestamp>_<sanitised name>`.
     *
     * The uri key makes the name stable and unique per source so a rebuilt queue
     * can find its copy; the timestamp keeps repeat shares of the same file from
     * colliding; the readable suffix makes the cache directory diagnosable.
     */
    private fun stagedName(uri: Uri, displayName: String): String =
        "share_${uriKey(uri)}_${System.currentTimeMillis()}_${sanitise(displayName)}"

    private fun sanitise(name: String): String {
        val base = File(name).name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (base.isBlank() || base == "." || base == "..") "shared_file" else base
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "name query failed: ${e.message}")
            null
        } finally {
            cursor?.close()
        }
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
}
