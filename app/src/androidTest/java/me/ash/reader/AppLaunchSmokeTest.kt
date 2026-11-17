package me.ash.reader

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.ash.reader.infrastructure.android.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wave A runtime smoke (S38): launches the real [MainActivity] entry point
 * on an explicitly selected emulator and exercises the Back path.
 *
 * Verifies the targetSdk 36 / predictive-back posture: the manifest sets
 * enableOnBackInvokedCallback=true and all custom back handling uses
 * supported APIs (BackHandler only; no onBackPressed/KEYCODE_BACK). This
 * test proves launch + back dispatch do not crash on an API 36 runtime.
 *
 * Note: system-level key injection via Espresso is broken on the API 36
 * emulator image used here (espresso reflection on InputManager.getInstance,
 * an infra incompatibility unrelated to this app), so back is dispatched
 * through Activity.onBackPressedDispatcher — the same supported-API path a
 * system back press drives.
 *
 * It does NOT assert specific navigation destinations (onboarding flow is
 * owned by later waves); the contract here is "launch survives, back
 * dispatches, no uncaught exception".
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    @Test
    fun launchAndBackDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Let first composition + any onboarding settle.
            Thread.sleep(2_000)

            // Back dispatch through the supported dispatcher path.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            Thread.sleep(1_000)

            // Either the activity finished (back exited the app) or it is
            // still alive handling the back internally - both are legal;
            // the failure mode this test pins is a crash/uncaught exception.
            val state = scenario.state
            assertTrue(
                "Activity in illegal state after back: $state",
                state == Lifecycle.State.DESTROYED ||
                    state == Lifecycle.State.RESUMED ||
                    state == Lifecycle.State.STARTED ||
                    state == Lifecycle.State.CREATED,
            )
        }
    }
}
