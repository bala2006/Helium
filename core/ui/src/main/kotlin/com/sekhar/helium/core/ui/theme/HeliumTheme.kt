package com.sekhar.helium.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Helium's cherry-blossom palette.
 *
 * The product is dark-first because the editor is used in the dark, next to a
 * bright video preview. The palette is deliberately warm — a blossom accent on
 * warm charcoal, not the usual cold blue/violet — with a single saturated accent
 * so the timeline and the AI field stay legible without visual noise.
 */
object HeliumColors {
    /** Primary accent — the cherry blossom pink that defines the product. */
    val Blossom = Color(0xFFFECDBE)
    val BlossomBright = Color(0xFFFFE1D6)
    val BlossomDeep = Color(0xFFE8A793)

    /** Warm charcoal surfaces. */
    val Background = Color(0xFF12100F)
    val Surface = Color(0xFF1C1917)
    val SurfaceElevated = Color(0xFF262220)
    val SurfaceHighest = Color(0xFF322C29)

    /** Text. */
    val OnBackground = Color(0xFFF6F1EF)
    val OnSurfaceMuted = Color(0xFFA9A29E)
    val OnSurfaceFaint = Color(0xFF7A736F)

    /** Lines and dividers. */
    val Outline = Color(0xFF443D39)
    val OutlineFaint = Color(0xFF2E2926)

    /** Supporting accents used sparingly. */
    val Plum = Color(0xFFC9A0B4)
    val Sage = Color(0xFF9DBFA7)
    val Amber = Color(0xFFE8C07D)
    val Error = Color(0xFFF2887A)
    val Success = Color(0xFF9BD3AE)
    val Scrim = Color(0xCC0B0A09)

    // Timeline / track colours, tuned for contrast against `Surface`.
    val TrackVideo = Color(0xFF3A322E)
    val TrackVideoSelected = BlossomDeep
    val TrackAudio = Color(0xFF2C3A34)
    val TrackText = Color(0xFF3A3247)
    val TrackOverlay = Color(0xFF40352C)
    val Playhead = BlossomBright
}

private val HeliumDarkScheme = darkColorScheme(
    primary = HeliumColors.Blossom,
    onPrimary = Color(0xFF3A1D14),
    primaryContainer = Color(0xFF4A2A20),
    onPrimaryContainer = HeliumColors.BlossomBright,
    secondary = HeliumColors.Plum,
    onSecondary = Color(0xFF33202A),
    tertiary = HeliumColors.Sage,
    onTertiary = Color(0xFF1E2C23),
    background = HeliumColors.Background,
    onBackground = HeliumColors.OnBackground,
    surface = HeliumColors.Surface,
    onSurface = HeliumColors.OnBackground,
    surfaceVariant = HeliumColors.SurfaceElevated,
    onSurfaceVariant = HeliumColors.OnSurfaceMuted,
    surfaceContainer = HeliumColors.SurfaceElevated,
    surfaceContainerHigh = HeliumColors.SurfaceHighest,
    outline = HeliumColors.Outline,
    outlineVariant = HeliumColors.OutlineFaint,
    error = HeliumColors.Error,
    onError = Color(0xFF3A1310),
)

/** Slightly tighter than the Material default: phone editors need density. */
private val HeliumTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        bodyMedium = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
        ),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

private val HeliumShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun HeliumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HeliumDarkScheme,
        typography = HeliumTypography,
        shapes = HeliumShapes,
        content = content,
    )
}

/** Typography helpers used across screens so text styles stay consistent. */
object HeliumTextStyles {
    val SectionLabel: TextStyle = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
    )

    val Timestamp: TextStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        // Monospace-ish feel without bundling a font.
        fontFeatureSettings = "tnum",
    )
}
