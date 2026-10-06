package com.taehagen.spotifygood.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Brand palette shared by the whole app (dark-first, near-black with a green accent). */
object AppColors {
    /** Accent green used for play buttons, "now playing" highlights and switches. */
    val Brand = Color(0xFF1ED760)
    val BrandDark = Color(0xFF169C46)
    val OnBrand = Color(0xFF000000)

    val Background = Color(0xFF121212)
    val SurfaceLow = Color(0xFF181818)
    val Surface1 = Color(0xFF1E1E1E)
    val Surface2 = Color(0xFF2A2A2A)
    val Surface3 = Color(0xFF333333)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextTertiary = Color(0xFF7C7C7C)
    val Error = Color(0xFFF15E6C)
    val Info = Color(0xFF509BF5)

    /** Liked Songs tile gradient (original: indigo to teal). */
    val LikedGradient: Brush = Brush.linearGradient(listOf(Color(0xFF4A2BC2), Color(0xFF7B8CDE), Color(0xFF9FD3C7)))

    /** Your Episodes tile gradient. */
    val EpisodesGradient: Brush = Brush.linearGradient(listOf(Color(0xFF0B5D4B), Color(0xFF1E9E7A)))
}

internal val DarkColors = darkColorScheme(
    primary = AppColors.Brand,
    onPrimary = AppColors.OnBrand,
    primaryContainer = Color(0xFF0F5C2E),
    onPrimaryContainer = Color(0xFFB9F6CA),
    inversePrimary = AppColors.BrandDark,
    secondary = Color(0xFFD0D0D0),
    onSecondary = Color(0xFF121212),
    secondaryContainer = AppColors.Surface2,
    onSecondaryContainer = AppColors.TextPrimary,
    tertiary = AppColors.Info,
    onTertiary = Color(0xFF00213F),
    tertiaryContainer = Color(0xFF173A63),
    onTertiaryContainer = Color(0xFFD3E4FF),
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Background,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.Surface2,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = AppColors.Background,
    error = AppColors.Error,
    onError = Color(0xFF2D0005),
    errorContainer = Color(0xFF5C1520),
    onErrorContainer = Color(0xFFFFDADC),
    outline = Color(0xFF727272),
    outlineVariant = Color(0xFF3A3A3A),
    scrim = Color.Black,
    surfaceBright = Color(0xFF3A3A3A),
    surfaceDim = Color(0xFF0E0E0E),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = AppColors.SurfaceLow,
    surfaceContainer = AppColors.Surface1,
    surfaceContainerHigh = AppColors.Surface2,
    surfaceContainerHighest = AppColors.Surface3,
)

internal val LightColors = lightColorScheme(
    // The brand green is too light for text on white; a deeper green keeps 4.5:1 contrast.
    primary = Color(0xFF0E7A39),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F2C8),
    onPrimaryContainer = Color(0xFF00210C),
    inversePrimary = AppColors.Brand,
    secondary = Color(0xFF4E4E4E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E6E6),
    onSecondaryContainer = Color(0xFF121212),
    tertiary = Color(0xFF1F5FAF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5E3FF),
    onTertiaryContainer = Color(0xFF001B3C),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF121212),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF121212),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF5A5A5A),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF242424),
    inverseOnSurface = Color(0xFFF2F2F2),
    error = Color(0xFFBA1A2A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDADB),
    onErrorContainer = Color(0xFF410008),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFD0D0D0),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDDDDD),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F4F4),
    surfaceContainer = Color(0xFFEFEFEF),
    surfaceContainerHigh = Color(0xFFE9E9E9),
    surfaceContainerHighest = Color(0xFFE2E2E2),
)
