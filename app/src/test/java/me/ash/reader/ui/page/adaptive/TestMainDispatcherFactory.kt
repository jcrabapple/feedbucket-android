package me.ash.reader.ui.page.adaptive

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory

/**
 * Test-only Main dispatcher factory. The unit-test classpath carries NO
 * kotlinx-coroutines-test artifact (S36 — no new dependencies), so the
 * `Dispatchers.setMain` seam is unavailable; [MainDispatcherFactory] is the
 * coroutines-core ServiceLoader extension point for exactly this case.
 * Installed via
 * `src/test/resources/META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory`,
 * it makes `Dispatchers.Main` (and therefore `viewModelScope`) an INLINE
 * dispatcher on the JVM: ViewModel code launched from a test thread runs
 * synchronously until its first real suspension, keeping the reader-state
 * tests deterministic. No other unit test in this module touches Main.
 *
 * REQUIREMENT: the test class must set the system property
 * `kotlinx.coroutines.fast.service.loader=false` before any `Dispatchers
 * .Main` access — coroutines' FastServiceLoader otherwise hard-codes the
 * Android factory (it detects `android.os.Build` from the mockable jar) and
 * never consults ServiceLoader providers.
 *
 * The opt-in is a compile requirement of the coroutines factory SPI (the
 * only supported way to provide a Main dispatcher), not a suppressed
 * warning in product code.
 */
@OptIn(InternalCoroutinesApi::class)
class TestMainDispatcherFactory : MainDispatcherFactory {

    override val loadPriority: Int = Int.MAX_VALUE

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher =
        InlineMainDispatcher
}

/** Never-dispatch Main stand-in: everything runs on the calling thread. */
@OptIn(InternalCoroutinesApi::class)
private object InlineMainDispatcher : MainCoroutineDispatcher() {

    override val immediate: MainCoroutineDispatcher
        get() = this

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()

    override fun toString(): String = "InlineMainDispatcher[test]"
}
