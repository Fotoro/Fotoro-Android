package com.nc.fotoro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FotoroColors = darkColorScheme(
    primary = Coral, onPrimary = Ink, secondary = Mint, onSecondary = Ink,
    background = Ink, onBackground = Cream, surface = Panel, onSurface = Cream,
    surfaceVariant = PanelRaised, onSurfaceVariant = Muted, outline = Line,
)

@Composable
fun FotoroAndroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FotoroColors, typography = Typography, content = content)
}
