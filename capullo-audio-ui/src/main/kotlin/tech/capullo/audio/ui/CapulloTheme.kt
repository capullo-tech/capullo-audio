package tech.capullo.audio.ui

import android.app.Activity
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The Material3 wrapper every capullo app was writing for itself: dynamic colour on API 31+,
 * a light/dark palette pair below it, and an optional status-bar appearance effect.
 *
 * Only the CONSTANTS differed between the copies, which is why this is a parameter list and not a
 * design. Each app keeps its own palettes and its own name for the wrapper; what moves here is the
 * branch logic they were all repeating. Making the palette a parameter also makes an unfilled one
 * visible: telecloud-radio shipped Android Studio's `Purple80` boilerplate for months because a
 * private `DarkColorScheme` in each app looks identical whether or not anyone chose the colours.
 *
 * [darkTheme] is a decision, not a source. Apps that expose a three-way system/light/dark setting
 * resolve it before calling; apps that follow the system pass `isSystemInDarkTheme()`.
 *
 * [applyStatusBarAppearance] defaults to OFF because it reaches for the host [Activity] window and
 * only one app was doing it. Pass `true` to keep status-bar icons legible against the theme.
 */
@Composable
fun CapulloTheme(
    darkTheme: Boolean,
    lightColors: ColorScheme,
    darkColors: ColorScheme,
    dynamicColor: Boolean = true,
    typography: Typography? = null,
    applyStatusBarAppearance: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColors
        else -> lightColors
    }

    if (applyStatusBarAppearance) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography ?: MaterialTheme.typography,
        content = content,
    )
}
