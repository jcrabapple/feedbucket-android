package me.ash.reader.ui.theme

import androidx.compose.ui.graphics.Color
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Wave H (D17): the static FeedBucket petrol/ink schemes must match
 * docs/creative/UI-CONTRACT-v1.md §5.1 token-for-token, and every semantic
 * text pair must meet WCAG AA (normal text >= 4.5:1, boundaries/icons >= 3:1)
 * in light, dark, and the representative dynamic-sample columns.
 */
class FeedBucketStaticSchemeTest {

    private fun hex(color: Color): String =
        "#%02X%02X%02X".format(
            (color.red * 255).roundToInt().coerceIn(0, 255),
            (color.green * 255).roundToInt().coerceIn(0, 255),
            (color.blue * 255).roundToInt().coerceIn(0, 255),
        )

    @Test
    fun lightSchemeMatchesContractTokens() {
        val c = FeedBucketColorSchemes.Light
        mapOf(
            "primary" to "#145B60", "onPrimary" to "#FFFFFF",
            "primaryContainer" to "#B8E4E2", "onPrimaryContainer" to "#062F32",
            "primaryFixed" to "#B8E4E2", "primaryFixedDim" to "#9ACFCD",
            "onPrimaryFixed" to "#062F32", "onPrimaryFixedVariant" to "#1A4A4D",
            "secondary" to "#496361", "onSecondary" to "#FFFFFF",
            "secondaryContainer" to "#CBE6E2", "onSecondaryContainer" to "#123331",
            "secondaryFixed" to "#CBE6E2", "secondaryFixedDim" to "#AFCBC7",
            "onSecondaryFixed" to "#123331", "onSecondaryFixedVariant" to "#2A4A47",
            "tertiary" to "#4B5F7D", "onTertiary" to "#FFFFFF",
            "tertiaryContainer" to "#D3E0F5", "onTertiaryContainer" to "#16283F",
            "tertiaryFixed" to "#D3E0F5", "tertiaryFixedDim" to "#B7C9E2",
            "onTertiaryFixed" to "#16283F", "onTertiaryFixedVariant" to "#33465F",
            "error" to "#BA1A1A", "onError" to "#FFFFFF",
            "errorContainer" to "#FFDAD6", "onErrorContainer" to "#410002",
            "background" to "#FAFDFC", "onBackground" to "#171D1C",
            "surface" to "#FAFDFC", "onSurface" to "#171D1C",
            "surfaceDim" to "#D6DAD8", "surfaceBright" to "#FAFDFC",
            "surfaceContainerLowest" to "#FFFFFF", "surfaceContainerLow" to "#F4F7F6",
            "surfaceContainer" to "#EEF2F1", "surfaceContainerHigh" to "#E8EDEB",
            "surfaceContainerHighest" to "#E2E8E6", "surfaceVariant" to "#E2E8E6",
            "onSurfaceVariant" to "#3F4947", "outline" to "#6F7977",
            "outlineVariant" to "#BEC9C6", "inverseSurface" to "#2C3130",
            "inverseOnSurface" to "#F1F4F2", "inversePrimary" to "#86D4D2",
        ).forEach { (role, expected) ->
            val actual =
                when (role) {
                    "primary" -> hex(c.primary)
                    "onPrimary" -> hex(c.onPrimary)
                    "primaryContainer" -> hex(c.primaryContainer)
                    "onPrimaryContainer" -> hex(c.onPrimaryContainer)
                    "primaryFixed" -> hex(c.primaryFixed)
                    "primaryFixedDim" -> hex(c.primaryFixedDim)
                    "onPrimaryFixed" -> hex(c.onPrimaryFixed)
                    "onPrimaryFixedVariant" -> hex(c.onPrimaryFixedVariant)
                    "secondary" -> hex(c.secondary)
                    "onSecondary" -> hex(c.onSecondary)
                    "secondaryContainer" -> hex(c.secondaryContainer)
                    "onSecondaryContainer" -> hex(c.onSecondaryContainer)
                    "secondaryFixed" -> hex(c.secondaryFixed)
                    "secondaryFixedDim" -> hex(c.secondaryFixedDim)
                    "onSecondaryFixed" -> hex(c.onSecondaryFixed)
                    "onSecondaryFixedVariant" -> hex(c.onSecondaryFixedVariant)
                    "tertiary" -> hex(c.tertiary)
                    "onTertiary" -> hex(c.onTertiary)
                    "tertiaryContainer" -> hex(c.tertiaryContainer)
                    "onTertiaryContainer" -> hex(c.onTertiaryContainer)
                    "tertiaryFixed" -> hex(c.tertiaryFixed)
                    "tertiaryFixedDim" -> hex(c.tertiaryFixedDim)
                    "onTertiaryFixed" -> hex(c.onTertiaryFixed)
                    "onTertiaryFixedVariant" -> hex(c.onTertiaryFixedVariant)
                    "error" -> hex(c.error)
                    "onError" -> hex(c.onError)
                    "errorContainer" -> hex(c.errorContainer)
                    "onErrorContainer" -> hex(c.onErrorContainer)
                    "background" -> hex(c.background)
                    "onBackground" -> hex(c.onBackground)
                    "surface" -> hex(c.surface)
                    "onSurface" -> hex(c.onSurface)
                    "surfaceDim" -> hex(c.surfaceDim)
                    "surfaceBright" -> hex(c.surfaceBright)
                    "surfaceContainerLowest" -> hex(c.surfaceContainerLowest)
                    "surfaceContainerLow" -> hex(c.surfaceContainerLow)
                    "surfaceContainer" -> hex(c.surfaceContainer)
                    "surfaceContainerHigh" -> hex(c.surfaceContainerHigh)
                    "surfaceContainerHighest" -> hex(c.surfaceContainerHighest)
                    "surfaceVariant" -> hex(c.surfaceVariant)
                    "onSurfaceVariant" -> hex(c.onSurfaceVariant)
                    "outline" -> hex(c.outline)
                    "outlineVariant" -> hex(c.outlineVariant)
                    "inverseSurface" -> hex(c.inverseSurface)
                    "inverseOnSurface" -> hex(c.inverseOnSurface)
                    "inversePrimary" -> hex(c.inversePrimary)
                    else -> error("unmapped role $role")
                }
            assertEquals("light $role", expected, actual)
        }
    }

    @Test
    fun darkSchemeMatchesContractTokens() {
        val c = FeedBucketColorSchemes.Dark
        mapOf(
            "primary" to "#86D4D2", "onPrimary" to "#003638",
            "primaryContainer" to "#24575A", "onPrimaryContainer" to "#B8E4E2",
            "primaryFixed" to "#B8E4E2", "primaryFixedDim" to "#9ACFCD",
            "onPrimaryFixed" to "#062F32", "onPrimaryFixedVariant" to "#1A4A4D",
            "secondary" to "#A9CCC7", "onSecondary" to "#173634",
            "secondaryContainer" to "#35504D", "onSecondaryContainer" to "#CBE6E2",
            "secondaryFixed" to "#CBE6E2", "secondaryFixedDim" to "#AFCBC7",
            "onSecondaryFixed" to "#123331", "onSecondaryFixedVariant" to "#2A4A47",
            "tertiary" to "#B3C5E2", "onTertiary" to "#1D304A",
            "tertiaryContainer" to "#33465F", "onTertiaryContainer" to "#D3E0F5",
            "tertiaryFixed" to "#D3E0F5", "tertiaryFixedDim" to "#B7C9E2",
            "onTertiaryFixed" to "#16283F", "onTertiaryFixedVariant" to "#33465F",
            "error" to "#FFB4AB", "onError" to "#690005",
            "errorContainer" to "#93000A", "onErrorContainer" to "#FFDAD6",
            "background" to "#101514", "onBackground" to "#DDE4E2",
            "surface" to "#101514", "onSurface" to "#DDE4E2",
            "surfaceDim" to "#101514", "surfaceBright" to "#363C3A",
            "surfaceContainerLowest" to "#0B100F", "surfaceContainerLow" to "#181E1C",
            "surfaceContainer" to "#1C2220", "surfaceContainerHigh" to "#262D2B",
            "surfaceContainerHighest" to "#313836", "surfaceVariant" to "#3F4947",
            "onSurfaceVariant" to "#BEC9C6", "outline" to "#899391",
            "outlineVariant" to "#3F4947", "inverseSurface" to "#DDE4E2",
            "inverseOnSurface" to "#2C3130", "inversePrimary" to "#145B60",
        ).forEach { (role, expected) ->
            val actual =
                when (role) {
                    "primary" -> hex(c.primary)
                    "onPrimary" -> hex(c.onPrimary)
                    "primaryContainer" -> hex(c.primaryContainer)
                    "onPrimaryContainer" -> hex(c.onPrimaryContainer)
                    "primaryFixed" -> hex(c.primaryFixed)
                    "primaryFixedDim" -> hex(c.primaryFixedDim)
                    "onPrimaryFixed" -> hex(c.onPrimaryFixed)
                    "onPrimaryFixedVariant" -> hex(c.onPrimaryFixedVariant)
                    "secondary" -> hex(c.secondary)
                    "onSecondary" -> hex(c.onSecondary)
                    "secondaryContainer" -> hex(c.secondaryContainer)
                    "onSecondaryContainer" -> hex(c.onSecondaryContainer)
                    "secondaryFixed" -> hex(c.secondaryFixed)
                    "secondaryFixedDim" -> hex(c.secondaryFixedDim)
                    "onSecondaryFixed" -> hex(c.onSecondaryFixed)
                    "onSecondaryFixedVariant" -> hex(c.onSecondaryFixedVariant)
                    "tertiary" -> hex(c.tertiary)
                    "onTertiary" -> hex(c.onTertiary)
                    "tertiaryContainer" -> hex(c.tertiaryContainer)
                    "onTertiaryContainer" -> hex(c.onTertiaryContainer)
                    "tertiaryFixed" -> hex(c.tertiaryFixed)
                    "tertiaryFixedDim" -> hex(c.tertiaryFixedDim)
                    "onTertiaryFixed" -> hex(c.onTertiaryFixed)
                    "onTertiaryFixedVariant" -> hex(c.onTertiaryFixedVariant)
                    "error" -> hex(c.error)
                    "onError" -> hex(c.onError)
                    "errorContainer" -> hex(c.errorContainer)
                    "onErrorContainer" -> hex(c.onErrorContainer)
                    "background" -> hex(c.background)
                    "onBackground" -> hex(c.onBackground)
                    "surface" -> hex(c.surface)
                    "onSurface" -> hex(c.onSurface)
                    "surfaceDim" -> hex(c.surfaceDim)
                    "surfaceBright" -> hex(c.surfaceBright)
                    "surfaceContainerLowest" -> hex(c.surfaceContainerLowest)
                    "surfaceContainerLow" -> hex(c.surfaceContainerLow)
                    "surfaceContainer" -> hex(c.surfaceContainer)
                    "surfaceContainerHigh" -> hex(c.surfaceContainerHigh)
                    "surfaceContainerHighest" -> hex(c.surfaceContainerHighest)
                    "surfaceVariant" -> hex(c.surfaceVariant)
                    "onSurfaceVariant" -> hex(c.onSurfaceVariant)
                    "outline" -> hex(c.outline)
                    "outlineVariant" -> hex(c.outlineVariant)
                    "inverseSurface" -> hex(c.inverseSurface)
                    "inverseOnSurface" -> hex(c.inverseOnSurface)
                    "inversePrimary" -> hex(c.inversePrimary)
                    else -> error("unmapped role $role")
                }
            assertEquals("dark $role", expected, actual)
        }
    }

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
        val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertAa(name: String, scheme: androidx.compose.material3.ColorScheme) {
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
        textPairs.forEachIndexed { i, (fg, bg) ->
            val r = contrast(fg, bg)
            assertTrue("$name text pair #$i (${hex(fg)} on ${hex(bg)}) = %.2f < 4.5".format(r), r >= 4.5)
        }
        val boundaryPairs =
            listOf(
                scheme.outline to scheme.surface,
                scheme.outline to scheme.background,
                scheme.primary to scheme.surface,
            )
        boundaryPairs.forEachIndexed { i, (fg, bg) ->
            val r = contrast(fg, bg)
            assertTrue("$name boundary pair #$i (${hex(fg)} on ${hex(bg)}) = %.2f < 3.0".format(r), r >= 3.0)
        }
    }

    @Test
    fun staticLightMeetsWcagAa() = assertAa("static-light", FeedBucketColorSchemes.Light)

    @Test
    fun staticDarkMeetsWcagAa() = assertAa("static-dark", FeedBucketColorSchemes.Dark)

    @Test
    fun dynamicSampleLightMeetsWcagAa() =
        assertAa("dynamic-sample-light", FeedBucketColorSchemes.DynamicSampleLight)

    @Test
    fun dynamicSampleDarkMeetsWcagAa() =
        assertAa("dynamic-sample-dark", FeedBucketColorSchemes.DynamicSampleDark)
}
