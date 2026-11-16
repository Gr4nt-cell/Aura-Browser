package com.d4viddf.hyperbridge.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Liquid-glass design tokens. Everything translucent, hairline specular borders, deep aurora backdrop. */
object Glass {
    val Radius = 26.dp
    val shapeLarge = RoundedCornerShape(Radius)
    val shapeMedium = RoundedCornerShape(20.dp)
    val shapePill = RoundedCornerShape(50)

    fun border(dark: Boolean = true) = BorderStroke(
        1.dp,
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (dark) 0.55f else 0.9f),
                Color.White.copy(alpha = if (dark) 0.08f else 0.25f),
                Color.White.copy(alpha = if (dark) 0.22f else 0.5f)
            )
        )
    )

    val sheetFill = Color(0xE6141B30)
    val sheetFillLight = Color(0xE9F2F5FF)

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(16.dp),
        medium = shapeMedium,
        large = shapeLarge,
        extraLarge = RoundedCornerShape(34.dp)
    )
}

val LocalGlassDark = staticCompositionLocalOf { true }

fun glassColorScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xFF9CC2FF),
    onPrimary = Color(0xFF06142E),
    primaryContainer = Color(0x3D9CC2FF),
    onPrimaryContainer = Color(0xFFE6F0FF),
    secondary = Color(0xFFB9A8FF),
    onSecondary = Color(0xFF140B33),
    secondaryContainer = Color(0x33B9A8FF),
    onSecondaryContainer = Color(0xFFEFE9FF),
    tertiary = Color(0xFF8FF0D4),
    onTertiary = Color(0xFF00241B),
    tertiaryContainer = Color(0x338FF0D4),
    onTertiaryContainer = Color(0xFFD9FFF3),
    background = Color.Transparent,
    onBackground = Color(0xFFEAF0FF),
    surface = Color(0x14FFFFFF),
    onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0x1FFFFFFF),
    onSurfaceVariant = Color(0xFFB7C2DD),
    surfaceTint = Color(0xFF9CC2FF),
    surfaceBright = Color(0x2EFFFFFF),
    surfaceDim = Color(0x0AFFFFFF),
    surfaceContainerLowest = Color(0x0AFFFFFF),
    surfaceContainerLow = Color(0x10FFFFFF),
    surfaceContainer = Color(0x17FFFFFF),
    surfaceContainerHigh = Color(0x1FFFFFFF),
    surfaceContainerHighest = Color(0x29FFFFFF),
    outline = Color(0x66FFFFFF),
    outlineVariant = Color(0x2EFFFFFF),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3A0000),
    errorContainer = Color(0x40FF6B6B),
    onErrorContainer = Color(0xFFFFE1E1),
    inverseSurface = Color(0xFFEAF0FF),
    inverseOnSurface = Color(0xFF111827),
    scrim = Color(0x99000000)
) else lightColorScheme(
    primary = Color(0xFF2F6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0x332F6BFF),
    onPrimaryContainer = Color(0xFF0B1F4D),
    secondary = Color(0xFF7459E8),
    onSecondary = Color.White,
    secondaryContainer = Color(0x337459E8),
    onSecondaryContainer = Color(0xFF241166),
    tertiary = Color(0xFF0E9F82),
    onTertiary = Color.White,
    tertiaryContainer = Color(0x330E9F82),
    onTertiaryContainer = Color(0xFF00382C),
    background = Color.Transparent,
    onBackground = Color(0xFF111A33),
    surface = Color(0x66FFFFFF),
    onSurface = Color(0xFF111A33),
    surfaceVariant = Color(0x59FFFFFF),
    onSurfaceVariant = Color(0xFF45506E),
    surfaceTint = Color(0xFF2F6BFF),
    surfaceBright = Color(0x99FFFFFF),
    surfaceDim = Color(0x33FFFFFF),
    surfaceContainerLowest = Color(0x40FFFFFF),
    surfaceContainerLow = Color(0x50FFFFFF),
    surfaceContainer = Color(0x62FFFFFF),
    surfaceContainerHigh = Color(0x75FFFFFF),
    surfaceContainerHighest = Color(0x88FFFFFF),
    outline = Color(0x802F3B5C),
    outlineVariant = Color(0x33111A33),
    error = Color(0xFFD93636),
    onError = Color.White,
    errorContainer = Color(0x33D93636),
    onErrorContainer = Color(0xFF4A0000),
    scrim = Color(0x66000000)
)

/** Full-screen aurora behind everything so the glass has something to refract. */
@Composable
fun GlassBackdrop(dark: Boolean, content: @Composable () -> Unit) {
    val base = if (dark) listOf(Color(0xFF060A18), Color(0xFF0B1230), Color(0xFF140B2A))
    else listOf(Color(0xFFDDE8FF), Color(0xFFEBE2FF), Color(0xFFD8F3EE))
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(base, Offset(0f, 0f), Offset(1400f, 2600f)))) {
        Box(
            Modifier.fillMaxSize()
                .blur(80.dp)
                .drawBehind {
                    val a = if (dark) 0.55f else 0.5f
                    drawCircle(Color(0xFF3D7BFF).copy(alpha = a), radius = size.minDimension * 0.55f, center = Offset(size.width * 0.15f, size.height * 0.12f))
                    drawCircle(Color(0xFF9B5CFF).copy(alpha = a), radius = size.minDimension * 0.5f, center = Offset(size.width * 0.95f, size.height * 0.42f))
                    drawCircle(Color(0xFF22D3B6).copy(alpha = a * 0.8f), radius = size.minDimension * 0.5f, center = Offset(size.width * 0.3f, size.height * 0.92f))
                }
        )
        CompositionLocalProvider(LocalGlassDark provides dark) { content() }
    }
}
