package com.nc.fotoro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val FotoroColors = darkColorScheme(
    primary = Coral, onPrimary = Ink, secondary = Mint, onSecondary = Ink,
    background = Ink, onBackground = Cream, surface = Panel, onSurface = Cream,
    surfaceVariant = PanelRaised, onSurfaceVariant = Muted, outline = Line,
)

private val FotoroShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun FotoroAndroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FotoroColors, typography = Typography, shapes = FotoroShapes, content = content)
}
