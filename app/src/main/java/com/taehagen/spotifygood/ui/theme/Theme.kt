package com.taehagen.spotifygood.ui.theme

import android.graphics.Color.TRANSPARENT
import android.graphics.Color.argb
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.taehagen.spotifygood.data.settings.Settings
import com.taehagen.spotifygood.data.settings.ThemeMode

/**
 * Lets full-screen dark overlays (e.g. Now Playing, which always draws a dark gradient) ask for
 * light system-bar icons regardless of the app theme.
 */
@Stable
class SystemBarsController {
    var forceDarkBars: Boolean by mutableStateOf(false)
}

val LocalSystemBarsController = staticCompositionLocalOf { SystemBarsController() }

/** True when the resolved app theme is dark (independent of the system setting). */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** Resolves whether the app should be dark for [mode]. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

/** App theme driven by the user's [settings] (theme mode + optional dynamic color). */
@Composable
fun SpotifyGoodTheme(settings: Settings, content: @Composable () -> Unit) {
    SpotifyGoodTheme(darkTheme = settings.theme.isDark(), dynamicColor = settings.dynamicColor, content = content)
}

/**
 * Dark-first Material 3 theme: near-black surfaces, green accent, bold headlines. Dynamic color
 * (Android 12+) replaces the palette when [dynamicColor] is set. System-bar icon colors follow
 * the resolved theme (or [SystemBarsController.forceDarkBars]).
 */
@Composable
fun SpotifyGoodTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val barsController = remember { SystemBarsController() }
    val darkBars = darkTheme || barsController.forceDarkBars
    val activity = LocalActivity.current as? ComponentActivity
    if (activity != null) {
        DisposableEffect(activity, darkBars) {
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { darkBars },
                navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkBars },
            )
            onDispose {}
        }
    }
    CompositionLocalProvider(
        LocalSystemBarsController provides barsController,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Scrims used behind 3-button navigation (gesture navigation stays fully transparent). */
private val LIGHT_SCRIM = argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = argb(0x80, 0x1b, 0x1b, 0x1b)
