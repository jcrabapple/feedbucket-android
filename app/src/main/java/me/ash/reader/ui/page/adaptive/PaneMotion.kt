package me.ash.reader.ui.page.adaptive

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneMotion
import androidx.compose.material3.adaptive.layout.PaneScaffoldMotionDataProvider
import androidx.compose.material3.adaptive.layout.PaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculateDefaultEnterTransition
import androidx.compose.material3.adaptive.layout.calculateDefaultExitTransition
import me.ash.reader.ui.ext.MotionExt
import me.ash.reader.ui.motion.materialSharedAxisXIn
import me.ash.reader.ui.motion.materialSharedAxisXOut

private const val INITIAL_OFFSET_FACTOR = 0.10f

/**
 * Wave H (D17/§7): reduced motion (animator duration scale 0) removes custom
 * spatial pane transitions; panes cross-fade instead of sliding.
 */
@ExperimentalMaterial3AdaptiveApi
internal fun <Role : PaneScaffoldRole> PaneScaffoldMotionDataProvider<Role>
    .calculateEnterTransition(role: Role, reducedMotion: Boolean = false): EnterTransition =
    if (reducedMotion) {
        MotionExt.fadeEnter()
    } else {
        when (this[role].motion) {
            PaneMotion.EnterFromRight ->
                materialSharedAxisXIn(initialOffsetX = { (it * INITIAL_OFFSET_FACTOR).toInt() })
            PaneMotion.EnterFromLeft ->
                materialSharedAxisXIn(initialOffsetX = { -(it * INITIAL_OFFSET_FACTOR).toInt() })
            else -> calculateDefaultEnterTransition(role)
        }
    }

@ExperimentalMaterial3AdaptiveApi
internal fun <Role : PaneScaffoldRole> PaneScaffoldMotionDataProvider<Role>.calculateExitTransition(
    role: Role,
    reducedMotion: Boolean = false,
): ExitTransition =
    if (reducedMotion) {
        MotionExt.fadeExit()
    } else {
        when (this[role].motion) {
            PaneMotion.ExitToLeft ->
                materialSharedAxisXOut(targetOffsetX = { -(it * INITIAL_OFFSET_FACTOR).toInt() })
            PaneMotion.ExitToRight ->
                materialSharedAxisXOut(targetOffsetX = { (it * INITIAL_OFFSET_FACTOR).toInt() })
            else -> calculateDefaultExitTransition(role)
        }
    }
