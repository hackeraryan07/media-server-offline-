package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = ThemePrimary,
    secondary = ThemeSecondary,
    tertiary = ThemeTertiary,
    background = Color(0xFF141218),
    surface = Color(0xFF1D1B20),
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5)
  )

private val LightColorScheme =
  lightColorScheme(
    primary = ThemePrimary,
    secondary = ThemeSecondary,
    tertiary = ThemeTertiary,
    background = ThemeBackground,
    surface = Color.White,
    onPrimary = ThemeOnPrimary,
    onSecondary = Color.White,
    onTertiary = ThemeOnTertiary,
    onBackground = ThemeTextDark,
    onSurface = ThemeTextDark,
    surfaceVariant = ServerOfflineContainer,
    onSurfaceVariant = ThemeSecondary,
    outline = ServerActiveBorder
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false, // Set to false to enforce local custom Professional Polish theme strictly
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
