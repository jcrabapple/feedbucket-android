package me.ash.reader.ui

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.max

/**
 * Wave H (D17 / UI-CONTRACT §5.2): measured contrast against RESOLVED
 * production colors. Dynamic wallpaper schemes are verified on-device via the
 * platform dynamic schemes (API 31+); static schemes re-measure here as a
 * rendered-source cross-check of the JVM token test.
 */
class DynamicSchemeContrastInstrumentedTest {

    private fun luminance(color: Color): Double {
        fun lin(v: Double): Double =
            if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        val r = lin(color.red.toDouble())
        val g = lin(color.green.toDouble())
        val b = lin(color.blue.toDouble())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(fg: Color, bg: Color): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        return (max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)
    }

    private fun assertAa(name: String, scheme: ColorScheme) {
        val textPairs =
            listOf(
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondary to scheme.secondary,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiary to scheme.tertiary,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.onError to scheme.error,
                scheme.onErrorContainer to scheme.errorContainer,
                scheme.onBackground to scheme.background,
                scheme.onSurface to scheme.surface,
                scheme.onSurface to scheme.surfaceContainerLow,
                scheme.onSurface to scheme.surfaceContainerHigh,
                scheme.onSurfaceVariant to scheme.surfaceContainerHighest,
                scheme.onSurfaceVariant to scheme.surface,
                scheme.inverseOnSurface to scheme.inverseSurface,
                scheme.onPrimaryFixed to scheme.primaryFixed,
                scheme.onSecondaryFixed to scheme.secondaryFixed,
                scheme.onTertiaryFixed to scheme.tertiaryFixed,
            )
        textPairs.forEachIndexed { index, (fg, bg) ->
            val ratio = contrast(fg, bg)
            assertTrue(
                "$name text pair #$index = %.2f < 4.5".format(ratio),
                ratio >= 4.5,
            )
        }
        listOf(
                scheme.outline to scheme.surface,
                scheme.outline to scheme.background,
                scheme.primary to scheme.surface,
            )
            .forEachIndexed { index, (fg, bg) ->
                val ratio = contrast(fg, bg)
                assertTrue("$name boundary pair #$index = %.2f < 3.0".format(ratio), ratio >= 3.0)
            }
    }

    @Test
    fun staticSchemesMeetAaMeasuredOnDevice() {
        assertAa("static-light", FeedBucketColorSchemes.Light)
        assertAa("static-dark", FeedBucketColorSchemes.Dark)
    }

    @Test
    fun platformDynamicSchemesMeetAaMeasuredOnDevice() {
        assumeTrue(
            "platform dynamic schemes require API 31+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAa("dynamic-light", dynamicLightColorScheme(context))
        assertAa("dynamic-dark", dynamicDarkColorScheme(context))
    }
}
