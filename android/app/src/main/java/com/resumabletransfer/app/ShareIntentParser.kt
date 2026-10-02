package com.resumabletransfer.app

import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Extracts shared file URIs from an incoming Sharesheet intent.
 *
 * Handles the three shapes a share can arrive in:
 *  - `ACTION_SEND` with a single `EXTRA_STREAM` item
 *  - `ACTION_SEND_MULTIPLE` with several `EXTRA_STREAM` items
 *  - either action with `ClipData` instead of (or alongside) `EXTRA_STREAM`,
 *    which some apps use and which also carries the granted URI permission
 *
 * Duplicates are removed by URI while preserving order: several apps populate
 * both `EXTRA_STREAM` and `ClipData` with the same item, and sharing the same
 * file twice would otherwise queue it twice.
 */
object ShareIntentParser {

    private const val TAG = "ShareIntentParser"

    /** True when this intent is a share the app should handle. */
    fun isShare(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }

    /**
     * Returns the shared URIs in order, or an empty list for anything that is
     * not a file share (a plain text share, for instance).
     */
    fun extractUris(intent: Intent?): List<Uri> {
        if (!isShare(intent)) return emptyList()
        val intentRef = intent ?: return emptyList()

        val out = LinkedHashSet<Uri>()

        // 1. EXTRA_STREAM: a single Uri for SEND, an ArrayList for SEND_MULTIPLE.
        //    String forms also occur in the wild (some senders, and
        //    `adb shell am start --esa`, put plain string URIs in the extra).
        //
        //    Each Bundle getter is type-checked and returns null on a mismatch,
        //    so the shapes are probed in turn rather than guessing one
        //    Parcelable type: a typed getParcelableExtra<Uri>() would throw
        //    ClassCastException on a String list, and the untyped get() returns
        //    null for anything not declared Parcelable.
        val extras = intentRef.extras
        if (extras != null) {
            @Suppress("DEPRECATION")
            val parcelable: Any? = try {
                extras.getParcelable(Intent.EXTRA_STREAM)
            } catch (e: Exception) {
                null
            }

            when (parcelable) {
                is Uri -> out.add(parcelable)
                is ArrayList<*> -> parcelable.forEach { item ->
                    when (item) {
                        is Uri -> out.add(item)
                        is String -> addParsed(out, item)
                    }
                }
                is Iterable<*> -> parcelable.forEach { item ->
                    when (item) {
                        is Uri -> out.add(item)
                        is String -> addParsed(out, item)
                    }
                }
            }

            @Suppress("DEPRECATION")
            val stringList: ArrayList<String>? = try {
                extras.getStringArrayList(Intent.EXTRA_STREAM)
            } catch (e: Exception) {
                null
            }
            stringList?.forEach { addParsed(out, it) }

            val single: String? = try {
                extras.getString(Intent.EXTRA_STREAM)
            } catch (e: Exception) {
                null
            }
            if (single != null) addParsed(out, single)
        }

        // 2. ClipData: covers shares that only populate ClipData, and is where
        //    FLAG_GRANT_READ_URI_PERMISSION is reliably attached.
        val clip = intentRef.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                val uri = item.uri ?: continue
                // Skip inline text items; only URIs are transferable.
                if (item.uri != null) out.add(uri)
            }
        }

        val result = out.toList()
        if (result.isEmpty()) {
            Log.i(TAG, "share intent carried no file URIs (action=${intentRef.action})")
        }
        return result
    }

    /** Adds `Uri.parse(raw)` to [out] when it parses to something usable. */
    private fun addParsed(out: MutableSet<Uri>, raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        runCatching { Uri.parse(trimmed) }
            .getOrNull()
            ?.takeIf { it.scheme != null }
            ?.let(out::add)
    }

    /** MIME type declared by the sender, used as a fallback when the provider has none. */
    fun extractMime(intent: Intent?): String? = intent?.type

    /**
     * Takes a persistable read grant where the sender offered one.
     *
     * Shares from most apps grant a transient permission tied to the Activity
     * task, which [SharedFileStager] works around by copying. Some senders (and
     * file managers using `OpenDocument`) do offer persistable access, so it is
     * worth taking when available -- it makes the copy a safety net rather than
     * the only defence.
     */
    fun tryTakePersistable(
        resolver: android.content.ContentResolver,
        intent: Intent?
    ) {
        val intentRef = intent ?: return
        val flags = intentRef.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        val uris = extractUris(intentRef)
        if (flags == 0 || uris.isEmpty()) return
        uris.forEach { uri ->
            try {
                resolver.takePersistableUriPermission(uri, flags)
            } catch (e: SecurityException) {
                // Expected for transient-only grants; the staged copy covers us.
                Log.d(TAG, "no persistable grant for $uri: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "persistable grant failed for $uri: ${e.message}")
            }
        }
    }
}
