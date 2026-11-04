package com.nc.fotoro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val FotoroColors = darkColorScheme(
    primary = Cobalt, onPrimary = Cream, secondary = Mint, onSecondary = Ink,
    background = Ink, onBackground = Cream, surface = Panel, onSurface = Cream,
    surfaceVariant = PanelRaised, onSurfaceVariant = Muted, outline = Line,
)

private val FotoroShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun FotoroAndroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FotoroColors, typography = Typography, shapes = FotoroShapes, content = content)
}
