package com.resumabletransfer.app.ui

import java.util.Locale
import kotlin.math.round

/**
 * Byte/speed formatting helpers.
 *
 * The previous implementation in TransferScreen.kt picked its unit with
 * `log10(bytes)/log10(1024)`, which had two real bugs:
 *
 *  1. `formatFileSize(1_048_575)` returned "1024.00 KB" instead of rolling over
 *     to "1.00 MB" — verified by running the old code.
 *  2. `units[digitGroups]` threw ArrayIndexOutOfBoundsException at >= 1 PB
 *     (index 5 on a 5-element array).
 *
 * It also used `String.format` with the default locale, so a German device
 * rendered "1,00 MB". Formatting now goes through Locale.US deliberately: a
 * byte count is a technical quantity, and a comma decimal separator reads as a
 * thousands separator to anything parsing the label.
 */
object Format {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    private const val BASE = 1024.0

    private fun unitFor(bytes: Long): Int {
        var unit = 0
        while (unit < UNITS.lastIndex && bytes >= (BASE.toLong() shl (10 * (unit + 1)))) {
            unit++
        }
        return unit
    }

    /** e.g. 1_048_575 -> "1.00 MB", 2_147_483_648 -> "2.00 GB", 0 -> "0 B". */
    fun fileSize(bytes: Long, decimals: Int = 2): String {
        if (bytes <= 0L) return "0 B"
        var unit = unitFor(bytes)
        var value = bytes / Math.pow(BASE, unit.toDouble())
        // Re-derive if rounding pushes the value up to the next unit's floor
        // (e.g. 1_048_575 bytes renders as "1024.00 KB" at 2 decimals).
        if (round(value * pow10(decimals)) / pow10(decimals) >= BASE && unit < UNITS.lastIndex) {
            unit++
            value = bytes / Math.pow(BASE, unit.toDouble())
        }
        return String.format(Locale.US, "%." + decimals + "f %s", value, UNITS[unit])
    }

    private fun pow10(n: Int): Double {
        var r = 1.0
        repeat(n) { r *= 10.0 }
        return r
    }

    /** e.g. 5_242_880 -> "5.0 MB/s". */
    fun speed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
        return if (mbPerSec >= 1.0) {
            String.format(Locale.US, "%.1f MB/s", mbPerSec)
        } else {
            String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
        }
    }

    /** e.g. 3661 -> "1h 1m". Negative input yields the caller's unknown marker. */
    fun eta(seconds: Long): String = when {
        seconds < 0 -> "--"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

/** Kept as top-level aliases so existing call sites stay source-compatible. */
fun formatFileSize(bytes: Long): String = Format.fileSize(bytes)
fun formatSpeed(bytesPerSec: Long): String = Format.speed(bytesPerSec)
fun formatEta(seconds: Long): String = Format.eta(seconds)
