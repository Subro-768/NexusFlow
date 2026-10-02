package com.resumabletransfer.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Solora Energy Dashboard Palette ────────────────────────────────────────
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
// Lightened from #64748B: the old value measured 3.35:1 on SurfaceElevated and
// 3.59:1 on SurfaceCard, below the WCAG AA 4.5:1 floor for small text.
val SoloraTextMuted = Color(0xFF8A97AD)
val SoloraTextOnAccent = Color(0xFF090C10)

// Extra surfaces needed by the light scheme.
private val SoloraLightBg = Color(0xFFF4F6FA)
private val SoloraLightSurface = Color(0xFFFFFFFF)
private val SoloraLightBorder = Color(0xFFC3CCDA)
private val SoloraLightOnAccent = Color(0xFF1B2430)

/**
 * Semantic accent for a transfer status. Screens previously hardcoded
 * `when (status) -> Color` blocks, which meant the mapping could drift between
 * screens and could not be themed. Keeping it in the theme makes it a single
 * source of truth.
 */
enum class StatusAccent { NEUTRAL, ACTIVE, GOOD, WARN, BAD }

data class SoloraStatusColors(
    val accent: Color,
    val container: Color,
    val onContainer: Color
)

val LocalSoloraStatusColors = staticCompositionLocalOf {
    SoloraStatusColors(
        accent = SoloraNeonLime,
        container = SoloraSurfaceElevated,
        onContainer = SoloraTextSecondary
    )
}

private fun statusColors(accent: Color): SoloraStatusColors = SoloraStatusColors(
    accent = accent,
    container = accent.copy(alpha = 0.18f),
    onContainer = accent
)

private val SoloraDarkColorScheme = darkColorScheme(
    primary = SoloraNeonLime,
    onPrimary = SoloraTextOnAccent,
    primaryContainer = Color(0xFF1E2D12),
    onPrimaryContainer = SoloraNeonLime,
    secondary = SoloraEnergyGreen,
    onSecondary = SoloraTextOnAccent,
    secondaryContainer = Color(0xFF102D22),
    onSecondaryContainer = SoloraEnergyGreen,
    tertiary = SoloraCyan,
    onTertiary = SoloraTextOnAccent,
    background = SoloraBgDark,
    onBackground = SoloraTextPrimary,
    surface = SoloraSurface,
    onSurface = SoloraTextPrimary,
    surfaceVariant = SoloraSurfaceElevated,
    onSurfaceVariant = SoloraTextSecondary,
    outline = SoloraBorder,
    outlineVariant = SoloraBorder,
    error = SoloraAlertRed,
    onError = SoloraTextOnAccent
)

private val SoloraLightColorScheme = lightColorScheme(
    primary = Color(0xFF3F6B00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFF5A6),
    onPrimaryContainer = Color(0xFF243700),
    secondary = Color(0xFF006B4C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB6F2DA),
    onSecondaryContainer = Color(0xFF00281B),
    tertiary = Color(0xFF00668A),
    onTertiary = Color.White,
    background = SoloraLightBg,
    onBackground = Color(0xFF161C24),
    surface = SoloraLightSurface,
    onSurface = Color(0xFF161C24),
    surfaceVariant = Color(0xFFE6EAF2),
    onSurfaceVariant = Color(0xFF414A57),
    outline = SoloraLightBorder,
    error = Color(0xFFB3261E),
    onError = Color.White
)

/**
 * Type scale. Screens used to hardcode 14 different font sizes (7sp-20sp) with no
 * scale; these named styles keep the numeric sizes but give them meaning so the
 * ratio is auditable.
 */
private val SoloraTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
        fontSize = 38.sp, letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, letterSpacing = 1.5.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 13.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 12.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
        fontSize = 13.sp, letterSpacing = 1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 11.sp, letterSpacing = 1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, letterSpacing = 1.sp
    )
)

/** Never below 10sp: the old design used 8sp/9sp for meta text, which is illegible. */
val LabelTiny = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
    fontSize = 10.sp, letterSpacing = 1.sp
)

private val SoloraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun statusColorsFor(accent: StatusAccent): SoloraStatusColors =
    when (accent) {
        StatusAccent.NEUTRAL -> SoloraStatusColors(SoloraTextMuted, SoloraSurfaceElevated, SoloraTextMuted)
        StatusAccent.ACTIVE -> statusColors(SoloraNeonLime)
        StatusAccent.GOOD -> statusColors(SoloraEnergyGreen)
        StatusAccent.WARN -> statusColors(SoloraSolarAmber)
        StatusAccent.BAD -> statusColors(SoloraAlertRed)
    }

@Composable
fun ResumableTransferTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(androidx.compose.ui.platform.LocalContext.current)
            else dynamicLightColorScheme(androidx.compose.ui.platform.LocalContext.current)
        darkTheme -> SoloraDarkColorScheme
        else -> SoloraLightColorScheme
    }

    val statusColors = when {
        darkTheme -> statusColors(SoloraNeonLime)
        else -> statusColors(Color(0xFF3F6B00))
    }

    CompositionLocalProvider(LocalSoloraStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SoloraTypography,
            shapes = SoloraShapes,
            content = content
        )
    }
}
