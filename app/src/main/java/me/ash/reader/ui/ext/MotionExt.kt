package me.ash.reader.ui.ext

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Wave H motion contract (UI-CONTRACT-v1 §7 and D17):
 * - `MotionScheme.standard()` product-wide; expressive anatomy without
 *   gratuitous bounce/morph, and no custom springs in list/read deltas.
 * - Reduced motion: animator duration scale 0 removes custom spatial
 *   transitions while preserving Back, focus and state.
 */
object MotionExt {

    /** Mirrors Settings.Global.ANIMATOR_DURATION_SCALE without the API shim. */
    const val ANIMATOR_DURATION_SCALE: String = "animator_duration_scale"

    /**
     * True when the animator duration scale is 0 (reduced motion). Pure and
     * side-effect free so it is unit-testable.
     */
    fun isReducedMotion(scale: Float?): Boolean = scale != null && scale == 0f

    /**
     * Spatial (slide/shared-element) motion is used only when reduced motion
     * is not active.
     */
    fun useSpatialMotion(scale: Float?): Boolean = !isReducedMotion(scale)

    /** Current animator duration scale, or null when unreadable. */
    fun animatorDurationScale(context: Context): Float? =
        try {
            Settings.Global.getFloat(context.contentResolver, ANIMATOR_DURATION_SCALE)
        } catch (e: Settings.SettingNotFoundException) {
            null
        }

    /**
     * Reduced-motion state for the current composition. The value is read
     * once per composition; it is not polled.
     */
    @Composable
    fun rememberReducedMotion(): Boolean {
        val context = LocalContext.current
        return remember { isReducedMotion(animatorDurationScale(context)) }
    }

    /** The product-wide motion scheme: Material standard (D17/§7). */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun feedbucketMotionScheme(): MotionScheme = MotionScheme.standard()

    /**
     * Fade-only pane transition used when reduced motion is active: no
     * spatial slide, no shared elements, instant under animator scale 0.
     */
    fun fadeEnter(durationMillis: Int = DEFAULT_FADE_MILLIS): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis))

    fun fadeExit(durationMillis: Int = DEFAULT_FADE_MILLIS): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis))

    private const val DEFAULT_FADE_MILLIS = 150
}
