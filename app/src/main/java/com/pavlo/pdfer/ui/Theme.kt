package com.pavlo.pdfer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pavlo.pdfer.data.ReaderTheme

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    secondary = Color(0xFF80CBC4),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    secondary = Color(0xFF00897B),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

/** Background + text colors for the reading surface, independent of app theme. */
data class ReaderPalette(val background: Color, val text: Color, val faint: Color)

fun ReaderTheme.palette(): ReaderPalette = when (this) {
    ReaderTheme.LIGHT -> ReaderPalette(Color(0xFFFFFFFF), Color(0xFF1A1A1A), Color(0xFF888888))
    ReaderTheme.SEPIA -> ReaderPalette(Color(0xFFF4ECD8), Color(0xFF3B3024), Color(0xFF9C8C72))
    ReaderTheme.DARK -> ReaderPalette(Color(0xFF121212), Color(0xFFD8D8D8), Color(0xFF777777))
}
