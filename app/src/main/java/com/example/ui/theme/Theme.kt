package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BurpOrange,
    onPrimary = Color.White,
    secondary = CardBackgroundDark,
    onSecondary = TextPrimaryDark,
    tertiary = SoftGreen,
    background = DarkAtmosphereBg,
    onBackground = TextPrimaryDark,
    surface = CardBackgroundDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = CardHeaderDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = BorderDark,
    error = SoftRed
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
