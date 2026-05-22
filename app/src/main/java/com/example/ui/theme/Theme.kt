package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ClaudeClayLight,
    secondary = ClaudeClayPrimary,
    tertiary = ClaudeMutedDark,
    background = ClaudeCharcoalBg,
    surface = ClaudeCharcoalCard,
    onPrimary = ClaudeCoffeeDark,
    onSecondary = Color.White,
    onBackground = ClaudeCoffeeLight,
    onSurface = ClaudeCoffeeLight,
    surfaceVariant = ClaudeCharcoalCard,
    onSurfaceVariant = ClaudeMutedDark,
    outline = ClaudeMutedDark
)

private val LightColorScheme = lightColorScheme(
    primary = ClaudeClayPrimary,
    secondary = ClaudeClayHover,
    tertiary = ClaudeMutedMuted,
    background = ClaudeCreamBg,
    surface = ClaudeCreamCard,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = ClaudeCoffeeDark,
    onSurface = ClaudeCoffeeDark,
    surfaceVariant = ClaudeCreamCard,
    onSurfaceVariant = ClaudeMutedMuted,
    outline = ClaudeMutedMuted
)

@Composable
fun HermesAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // disabled to enforce Claude style
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
