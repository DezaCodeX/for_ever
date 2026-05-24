package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
  primary = LoveAccentDark,
  secondary = LoveSecondary,
  tertiary = LoveTertiary,
  background = LoveBackgroundDark,
  surface = LoveSurfaceDark,
  onPrimary = LoveBackgroundDark,
  onSecondary = LoveCream,
  onTertiary = LoveCream,
  onBackground = LoveCream,
  onSurface = LoveCream
)

private val LightColorScheme = lightColorScheme(
  primary = LovePrimary,
  secondary = LoveSecondary,
  tertiary = LoveTertiary,
  background = LoveBackgroundLight,
  surface = LoveSurfaceLight,
  onPrimary = LoveSurfaceLight,
  onSecondary = Color.White,
  onTertiary = Color.White,
  onBackground = LoveBackgroundDark,
  onSurface = LoveBackgroundDark
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // For standard couples theme integration, disable android dynamic material schemes by default to preserve romantic styles
  dynamicColor: Boolean = false,
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

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}
