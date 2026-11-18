package me.ash.reader.ui.ext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave H (D17/§7): reduced-motion honoring. Animator duration scale 0 must
 * disable spatial motion; any non-zero (or unreadable) scale keeps standard
 * motion.
 */
class MotionExtTest {

    @Test
    fun animatorScaleZeroIsReducedMotion() {
        assertTrue(MotionExt.isReducedMotion(0f))
        assertFalse(MotionExt.useSpatialMotion(0f))
    }

    @Test
    fun animatorScaleOneIsStandardMotion() {
        assertFalse(MotionExt.isReducedMotion(1f))
        assertTrue(MotionExt.useSpatialMotion(1f))
    }

    @Test
    fun animatorScaleFractionalIsStandardMotion() {
        assertFalse(MotionExt.isReducedMotion(0.5f))
        assertTrue(MotionExt.useSpatialMotion(0.5f))
    }

    @Test
    fun unreadableScaleDefaultsToStandardMotion() {
        assertFalse(MotionExt.isReducedMotion(null))
        assertTrue(MotionExt.useSpatialMotion(null))
    }
}
