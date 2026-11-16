package com.gr4nt.aurabrowser.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val LocalDark = compositionLocalOf { false }

object Ink {
    val accent = Color(0xFF0A84FF)
    val danger = Color(0xFFFF453A)
    val green = Color(0xFF30D158)

    @Composable fun primary(): Color = if (LocalDark.current) Color(0xFFF5F5FA) else Color(0xFF16161D)
    @Composable fun secondary(): Color = if (LocalDark.current) Color(0xB3F5F5FA) else Color(0xA016161D)
    @Composable fun tertiary(): Color = if (LocalDark.current) Color(0x80F5F5FA) else Color(0x6616161D)

    /** Soft pearl / midnight gradient behind everything (start page, empty areas, sheets' scrim backdrop). */
    @Composable fun background(): Brush = if (LocalDark.current)
        Brush.linearGradient(listOf(Color(0xFF14121F), Color(0xFF0D1522), Color(0xFF1A1226)))
    else
        Brush.linearGradient(listOf(Color(0xFFF7F1FF), Color(0xFFEAF3FF), Color(0xFFFDEFF6)))

    @Composable fun solidBg(): Color = if (LocalDark.current) Color(0xFF0D0D12) else Color(0xFFF3F1F8)
}

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Ink.accent) else lightColorScheme(primary = Ink.accent)) {
            content()
        }
    }
}
