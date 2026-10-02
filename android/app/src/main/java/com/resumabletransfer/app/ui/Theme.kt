package com.resumabletransfer.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Solora Energy Dashboard Palette
val SoloraBgDark = Color(0xFF090C10)
val SoloraSurface = Color(0xFF121824)
val SoloraSurfaceElevated = Color(0xFF1A2232)
val SoloraSurfaceCard = Color(0xFF151C28)
val SoloraBorder = Color(0xFF232D40)

val SoloraNeonLime = Color(0xFFD4FF00)       // High Energy Solar Neon
val SoloraEnergyGreen = Color(0xFF00E599)    // Real-time Flow Green
val SoloraSolarAmber = Color(0xFFFFB703)     // Battery/Warm Amber
val SoloraAlertRed = Color(0xFFFF4D4D)       // Interrupted / Warning
val SoloraCyan = Color(0xFF00D4FF)           // Connectivity Cyan

val SoloraTextPrimary = Color(0xFFF8FAFC)
val SoloraTextSecondary = Color(0xFF94A3B8)
val SoloraTextMuted = Color(0xFF64748B)

private val SoloraDarkColorScheme = darkColorScheme(
    primary = SoloraNeonLime,
    onPrimary = Color(0xFF090C10),
    primaryContainer = Color(0xFF1E2D12),
    onPrimaryContainer = SoloraNeonLime,
    secondary = SoloraEnergyGreen,
    onSecondary = Color(0xFF090C10),
    secondaryContainer = Color(0xFF102D22),
    onSecondaryContainer = SoloraEnergyGreen,
    tertiary = SoloraCyan,
    background = SoloraBgDark,
    onBackground = SoloraTextPrimary,
    surface = SoloraSurface,
    onSurface = SoloraTextPrimary,
    surfaceVariant = SoloraSurfaceElevated,
    onSurfaceVariant = SoloraTextSecondary,
    outline = SoloraBorder,
    error = SoloraAlertRed,
    onError = Color.White
)

@Composable
fun ResumableTransferTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SoloraDarkColorScheme,
        content = content
    )
}
