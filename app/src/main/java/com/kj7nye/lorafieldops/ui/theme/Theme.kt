package com.kj7nye.lorafieldops.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Green accent — field-ops / amateur radio aesthetic
private val PrimaryGreen = Color(0xFF2E7D32)
private val OnPrimary = Color.White
private val SecondaryGreen = Color(0xFF558B2F)
private val SurfaceDark = Color(0xFF1A1C1E)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF66BB6A),
    onPrimary = Color(0xFF003909),
    secondary = Color(0xFF8BC34A),
    background = SurfaceDark,
    surface = Color(0xFF282C2E),
    error = Color(0xFFCF6679),
)

private val LightColors = lightColorScheme(
    primary = PrimaryGreen,
    onPrimary = OnPrimary,
    secondary = SecondaryGreen,
    error = Color(0xFFB3261E),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
