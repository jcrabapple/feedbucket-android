package me.ash.reader.ui.theme

import android.os.Build
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import me.ash.reader.infrastructure.preference.LocalBasicFonts
import me.ash.reader.infrastructure.preference.LocalDynamicColor
import me.ash.reader.infrastructure.preference.LocalThemeIndex
import me.ash.reader.infrastructure.preference.ThemeIndexPreference
import me.ash.reader.ui.ext.MotionExt
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import me.ash.reader.ui.theme.palette.LocalTonalPalettes
import me.ash.reader.ui.theme.palette.TonalPalettes
import me.ash.reader.ui.theme.palette.core.ProvideZcamViewingConditions
import me.ash.reader.ui.theme.palette.dynamic.extractTonalPalettesFromUserWallpaper
import me.ash.reader.ui.theme.palette.dynamicDarkColorScheme
import me.ash.reader.ui.theme.palette.dynamicLightColorScheme

/**
 * FeedBucket theme (Wave H, UI-CONTRACT-v1 §1.3/§5):
 * - Default: System theme + static FeedBucket petrol/ink palette.
 * - Dynamic color: OFF by default; optional on API 31+ via the Appearance
 *   setting, falling back to the complete static schemes.
 * - Legacy ReadYou palette selection (themeIndex != default, custom primary
 *   color) is retained for existing users.
 * - Motion: MotionScheme.standard() product-wide (D17), with reduced-motion
 *   honoring in ui/ext/MotionExt.kt.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    useDarkTheme: Boolean,
    wallpaperPalettes: List<TonalPalettes> = extractTonalPalettesFromUserWallpaper(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current

    LaunchedEffect(useDarkTheme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (useDarkTheme) {
                view.windowInsetsController?.setSystemBarsAppearance(
                    0,
                    APPEARANCE_LIGHT_STATUS_BARS,
                )
            } else {
                view.windowInsetsController?.setSystemBarsAppearance(
                    APPEARANCE_LIGHT_STATUS_BARS,
                    APPEARANCE_LIGHT_STATUS_BARS,
                )
            }
        }
    }

    val themeIndex = LocalThemeIndex.current
    val dynamicColor = LocalDynamicColor.current.value

    val tonalPalettes =
        wallpaperPalettes[
            if (themeIndex >= wallpaperPalettes.size) {
                when {
                    wallpaperPalettes.size == 5 -> 0
                    wallpaperPalettes.size > 5 -> 5
                    else -> 0
                }
            } else {
                themeIndex
            }]

    ProvideZcamViewingConditions {
        CompositionLocalProvider(
            LocalTonalPalettes provides tonalPalettes.apply { Preparing() },
            LocalTextStyle provides LocalTextStyle.current.applyTextDirection(),
        ) {
            MaterialTheme(
                motionScheme = MotionExt.feedbucketMotionScheme(),
                colorScheme = resolveColorScheme(useDarkTheme, dynamicColor, themeIndex),
                typography =
                    LocalBasicFonts.current
                        .asTypography(LocalContext.current)
                        .applyTextDirection(),
                shapes = Shapes,
                content = content,
            )
        }
    }
}

/**
 * Color scheme precedence (UI-CONTRACT-v1 §1.3):
 *  1. Dynamic color ON + API 31+ -> platform Material dynamic schemes.
 *  2. Legacy palette selection (themeIndex != default) -> ReadYou wallpaper /
 *     custom palettes (capability retained).
 *  3. Default -> static FeedBucket petrol/ink schemes.
 */
@Composable
internal fun resolveColorScheme(
    useDarkTheme: Boolean,
    dynamicColor: Boolean,
    themeIndex: Int,
): ColorScheme {
    val context = LocalContext.current
    return when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDarkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        themeIndex != ThemeIndexPreference.default -> {
            if (useDarkTheme) dynamicDarkColorScheme()
            else dynamicLightColorScheme()
        }
        else -> {
            if (useDarkTheme) FeedBucketColorSchemes.Dark
            else FeedBucketColorSchemes.Light
        }
    }
}
