package me.ash.reader.ui.page.adaptive

import androidx.paging.ItemSnapshotList
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.data.ArticlePagingListUseCase
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.data.GroupWithFeedsListUseCase
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.article.ListGeneration
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.model.position.PositionStore
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.DownloadDao
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.DownloadService
import me.ash.reader.domain.service.ExtractCoordinator
import me.ash.reader.domain.service.ExtractFailureReason
import me.ash.reader.domain.service.ExtractMode
import me.ash.reader.domain.service.ExtractOutcome
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.SavedSearchService
import me.ash.reader.infrastructure.android.AndroidImageDownloader
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.preference.AutoExtractPreference
import me.ash.reader.infrastructure.preference.Settings
import me.ash.reader.infrastructure.preference.SettingsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * X4 task 1 — the E8/E10 reader render state machine and the E11/E12
 * interactive entry points, driven through the REAL [ArticleListReaderViewModel]
 * over a fake [ExtractCoordinator] (Mockito answer + latch gate, the X3
 * deterministic-concurrency pattern; no HTTP mock server, S36).
 *
 * Render matrix pinned here:
 *  - extracted row → FullContent, coordinator NEVER called (E8 order step 1);
 *  - toggle ON + teaser → Loading → success FullContent / failure silent
 *    Description fallback (E10/E21 — no error state is ever emitted);
 *  - toggle OFF + teaser → Description, coordinator not called (E20 gate);
 *  - `feed.isFullContent` + toggle OFF → FORCE extraction anyway
 *    (E9/E20 independence);
 *  - MANUAL on a non-teaser runs (E11 — trigger/toggle-independent);
 *  - RE_EXTRACT success while viewing Description → row updated, VIEW NOT
 *    SWITCHED (E12); while viewing FullContent → in-place refresh;
 *  - extraction success causes NO paging refresh / generation roll (E22c)
 *    via the mocked list use-case;
 *  - extracted rows keep the S22 diff pane reachable on the Description
 *    path (E22b state-level pin — the ReadingPage `originalContent`-only-on-
 *    Description mapping itself is untouched);
 *  - MANUAL/RE_EXTRACT failures raise the ≤3 s transient tint (E13) while
 *    AUTO failures stay fully silent.
 *
 * Dispatching: `Dispatchers.Main` is the Unconfined [TestMainDispatcherFactory]
 * (ServiceLoader seam — coroutines-test is NOT a dependency, S36), so
 * `initData` runs inline on the test thread; extraction bodies run on a
 * single-thread executor (`ioDispatcher`) gated by latches.
 *
 * REDCHECKs (recorded in the review handoff):
 *  - dropping the `autoExtractEnabled &&` gate term turns
 *    [toggleOffTeaserRendersDescriptionWithoutCoordinator] red;
 *  - switching the rendered view unconditionally on RE_EXTRACT success turns
 *    [reExtractSuccessWhileViewingDescriptionUpdatesRowWithoutSwitchingView] red;
 *  - adding `articleListUseCase.rollGeneration()` to the success handler turns
 *    [extractionSuccessNeverRollsListGeneration] red;
 *  - setting the transient tint for AUTO failures turns
 *    [toggleOnTeaserFailureFallsBackToDescriptionSilently] red.
 */
class ReaderExtractStateTest {

    private companion object {
        init {
            // coroutines 1.9.0's FastServiceLoader HARD-CODES the Android
            // Main factory whenever `android.os.Build` is loadable (always
            // true in unit tests via the mockable android.jar), silently
            // ignoring ServiceLoader providers. Disabling it (before
            // MainDispatcherLoader's <clinit>, i.e. before any Dispatchers
            // .Main access) restores the standard ServiceLoader path, where
            // TestMainDispatcherFactory (priority Int.MAX_VALUE) wins over
            // AndroidDispatcherFactory (Int.MAX_VALUE / 2). S36: this is the
            // no-new-dependency substitute for coroutines-test's setMain.
            System.setProperty("kotlinx.coroutines.fast.service.loader", "false")
        }

        const val NOW = 1_700_000_000_000L
        const val ARTICLE_ID = "1\$a1"
        const val ARTICLE_ID_B = "1\$b1"
        const val FEED_ID = "1\$f1"
        const val RAW_TEASER = "<p>A short teaser body.</p>"
        const val RAW_FULL = "<p>already a complete post body</p>"
        const val STORED_EXTRACTION = "<p>the stored sanitized extraction</p>"
        const val FRESH_EXTRACTION = "<p>a cleaner re-extraction result</p>"
        const val OTHER_EXTRACTION = "<p>the second article's stored body</p>"
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private class Fixture(
        autoExtract: AutoExtractPreference = AutoExtractPreference.ON,
    ) {
        private val executor = Executors.newSingleThreadExecutor()
        val ioDispatcher = executor.asCoroutineDispatcher()
        private val applicationScope = CoroutineScope(SupervisorJob() + ioDispatcher)

        val extractCalls = CopyOnWriteArrayList<ExtractMode>()

        /** Outcome the fake coordinator returns for the next extract call. */
        @Volatile
        var nextOutcome: ExtractOutcome = ExtractOutcome.Skipped

        /** Row `findArticleById` re-reads after a Stored outcome. */
        @Volatile
        var storedRow: ArticleWithFeed? = null

        /** When true the fake blocks inside extract until [gate] opens. */
        @Volatile
        var gateExtract = false

        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)

        val rssService = mock<RssService>()
        val repository = mock<AbstractRssRepository>()
        val diffMapHolder = mock<DiffMapHolder>()
        val filterStateUseCase = mock<FilterStateUseCase>()
        val groupWithFeedsListUseCase = mock<GroupWithFeedsListUseCase>()
        val settingsProvider = mock<SettingsProvider>()
        val extractCoordinator = mock<ExtractCoordinator>()
        val textToSpeechManager = mock<TextToSpeechManager>()
        val imageDownloader = mock<AndroidImageDownloader>()
        val articleListUseCase = mock<ArticlePagingListUseCase>()
        val positionStore = mock<PositionStore>()
        val savedSearchService = mock<SavedSearchService>()
        val downloadDao = mock<DownloadDao>()
        val downloadService = mock<DownloadService>()
        val articleDao = mock<ArticleDao>()
        val accountService = mock<AccountService>()
        val workManager = mock<WorkManager>()

        val vm: ArticleListReaderViewModel

        init {
            runBlocking {
                whenever(rssService.get()).thenReturn(repository)
                whenever(repository.findArticleById(any())).thenAnswer { storedRow }
                whenever(accountService.getCurrentAccountId()).thenReturn(1)
                whenever(extractCoordinator.extract(any(), any(), any())).thenAnswer { invocation ->
                    extractCalls.add(invocation.getArgument<ExtractMode>(2))
                    if (gateExtract) {
                        entered.countDown()
                        if (!gate.await(10, TimeUnit.SECONDS)) error("gate never released")
                    }
                    nextOutcome
                }
            }
            whenever(accountService.currentAccountFlow).thenReturn(MutableStateFlow<Account?>(null))
            whenever(articleDao.countFtsCoveredArticlesFlow(any())).thenReturn(flowOf(0))
            whenever(articleDao.countAccountArticlesFlow(any())).thenReturn(flowOf(0))
            whenever(filterStateUseCase.filterStateFlow).thenReturn(MutableStateFlow(FilterState()))
            whenever(groupWithFeedsListUseCase.groupWithFeedListFlow)
                .thenReturn(MutableStateFlow(emptyList<GroupWithFeed>()))
            whenever(articleListUseCase.pagerFlow).thenReturn(MutableStateFlow(PagerData()))
            whenever(articleListUseCase.generationFlow)
                .thenReturn(MutableStateFlow<ListGeneration?>(null))
            whenever(savedSearchService.listFlow()).thenReturn(flowOf(emptyList()))
            whenever(workManager.getWorkInfosByTagFlow(any()))
                .thenReturn(MutableStateFlow(emptyList<WorkInfo>()))
            whenever(settingsProvider.settings).thenReturn(Settings(autoExtractFullContent = autoExtract))
            whenever(diffMapHolder.checkIfUnread(any())).thenReturn(false)

            vm = ArticleListReaderViewModel(
                rssService = rssService,
                ioDispatcher = ioDispatcher,
                applicationScope = applicationScope,
                diffMapHolder = diffMapHolder,
                filterStateUseCase = filterStateUseCase,
                groupWithFeedsListUseCase = groupWithFeedsListUseCase,
                settingsProvider = settingsProvider,
                extractCoordinator = extractCoordinator,
                textToSpeechManager = textToSpeechManager,
                imageDownloader = imageDownloader,
                articleListUseCase = articleListUseCase,
                positionStore = positionStore,
                savedSearchService = savedSearchService,
                downloadDao = downloadDao,
                downloadService = downloadService,
                articleDao = articleDao,
                accountService = accountService,
                workManager = workManager,
            )
        }

        fun seedArticle(articleWithFeed: ArticleWithFeed) {
            whenever(articleListUseCase.itemSnapshotList).thenReturn(
                ItemSnapshotList(
                    placeholdersBefore = 0,
                    placeholdersAfter = 0,
                    items = listOf<ArticleFlowItem>(ArticleFlowItem.Article(articleWithFeed)),
                )
            )
        }

        /** Seeds a MULTI-article snapshot list, in the given order (indices matter). */
        fun seedArticles(vararg articles: ArticleWithFeed) {
            whenever(articleListUseCase.itemSnapshotList).thenReturn(
                ItemSnapshotList(
                    placeholdersBefore = 0,
                    placeholdersAfter = 0,
                    items = articles.map<ArticleWithFeed, ArticleFlowItem> {
                        ArticleFlowItem.Article(it)
                    },
                )
            )
        }

        /** Polls the reader state until [predicate] holds (deadline-bounded). */
        fun awaitState(
            timeoutMs: Long = 10_000,
            predicate: (ReaderState) -> Boolean,
        ): ReaderState {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val state = vm.readerStateStateFlow.value
                if (predicate(state)) return state
                Thread.sleep(10)
            }
            error("state condition not met within ${timeoutMs}ms; last = ${vm.readerStateStateFlow.value}")
        }

        /**
         * FIFO barrier on the single-thread IO executor: returns once all
         * previously-submitted work (e.g. a gated extraction continuation)
         * has drained. Makes "stale completion ignored" assertions
         * deterministic without sleeps.
         */
        fun runIoBarrier(timeoutMs: Long = 10_000): Boolean {
            val latch = CountDownLatch(1)
            executor.execute { latch.countDown() }
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun close() {
            ioDispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun article(
        id: String = ARTICLE_ID,
        title: String = "Test Article",
        rawDescription: String = RAW_TEASER,
        fullContent: String? = null,
        originalContent: String? = null,
        link: String = "https://example.com/post",
    ) = Article(
        id = id,
        date = Date(NOW),
        title = title,
        rawDescription = rawDescription,
        shortDescription = rawDescription.take(20),
        fullContent = fullContent,
        link = link,
        feedId = FEED_ID,
        accountId = 1,
        originalContent = originalContent,
        publishedAt = Date(NOW),
    )

    private fun feed(
        isFullContent: Boolean = false,
        medianBodyLength: Int = 0,
    ) = Feed(
        id = FEED_ID,
        name = "Test Feed",
        url = "https://example.com/feed.xml",
        groupId = "1\$g1",
        accountId = 1,
        isFullContent = isFullContent,
        medianBodyLength = medianBodyLength,
    )

    private fun ArticleWithFeed.withFullContent(fullContent: String?) =
        ArticleWithFeed(
            article = article.copy(fullContent = fullContent, extractedAt = fullContent?.let { Date(NOW) }),
            feed = feed,
        )

    // ------------------------------------------------------------------
    // E8 render matrix
    // ------------------------------------------------------------------

    /** Extracted row → FullContent immediately; the coordinator is NEVER called. */
    @Test
    fun extractedArticleRendersStoredFullContentWithoutTouchingCoordinator() {
        val fixture = Fixture()
        try {
            val awf = ArticleWithFeed(article(fullContent = STORED_EXTRACTION), feed())
            fixture.seedArticle(awf)

            fixture.vm.initData(ARTICLE_ID)

            val state = fixture.vm.readerStateStateFlow.value
            assertEquals(ReaderState.FullContent(STORED_EXTRACTION), state.content)
            assertFalse(state.isExtracting)
            assertTrue("coordinator must NEVER run for an extracted row", fixture.extractCalls.isEmpty())
            verify(fixture.articleListUseCase, never()).rollGeneration()
        } finally {
            fixture.close()
        }
    }

    /** Toggle ON + teaser → Loading (busy) → success → FullContent, in place. */
    @Test
    fun toggleOnTeaserEntersLoadingThenSuccessRendersFullContentInPlace() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.ON)
        try {
            val awf = ArticleWithFeed(article(rawDescription = RAW_TEASER), feed())
            fixture.seedArticle(awf)
            fixture.gateExtract = true
            fixture.nextOutcome = ExtractOutcome.Stored(ARTICLE_ID, Date(NOW))
            fixture.storedRow = awf.withFullContent(STORED_EXTRACTION)

            fixture.vm.initData(ARTICLE_ID)

            // E10 — busy state renders the existing Loading indicator.
            assertTrue("extraction must be in flight", fixture.entered.await(10, TimeUnit.SECONDS))
            val busy = fixture.vm.readerStateStateFlow.value
            assertEquals(ReaderState.Loading, busy.content)
            assertTrue(busy.isExtracting)

            fixture.gate.countDown()
            val done = fixture.awaitState { !it.isExtracting }

            assertEquals(ReaderState.FullContent(STORED_EXTRACTION), done.content)
            assertFalse(done.extractFailed)
            assertEquals(listOf(ExtractMode.AUTO), fixture.extractCalls.toList())
            // In-place row refresh — the BottomBar toggle now sees the extraction.
            assertEquals(
                STORED_EXTRACTION,
                fixture.vm.readingUiState.value.articleWithFeed?.article?.fullContent,
            )
        } finally {
            fixture.close()
        }
    }

    /** Toggle ON + teaser + failure → silent Description fallback (E10/E21). */
    @Test
    fun toggleOnTeaserFailureFallsBackToDescriptionSilently() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.ON)
        try {
            val awf = ArticleWithFeed(article(rawDescription = RAW_TEASER), feed())
            fixture.seedArticle(awf)
            fixture.gateExtract = true
            fixture.nextOutcome = ExtractOutcome.Failure(ExtractFailureReason.NETWORK)

            fixture.vm.initData(ARTICLE_ID)
            assertTrue(fixture.entered.await(10, TimeUnit.SECONDS))
            assertEquals(ReaderState.Loading, fixture.vm.readerStateStateFlow.value.content)

            fixture.gate.countDown()
            val done = fixture.awaitState { !it.isExtracting }

            // E10 — feed content the user would have seen anyway; NO error
            // state exists anymore, and AUTO failures are FULLY silent (E13:
            // no transient tint, no toast, no dialog).
            assertEquals(ReaderState.Description(RAW_TEASER), done.content)
            assertFalse("AUTO failure must not raise the transient tint", done.extractFailed)
            assertEquals(listOf(ExtractMode.AUTO), fixture.extractCalls.toList())
        } finally {
            fixture.close()
        }
    }

    /** Toggle OFF + teaser → Description; the coordinator is not called (E20). */
    @Test
    fun toggleOffTeaserRendersDescriptionWithoutCoordinator() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.OFF)
        try {
            fixture.seedArticle(ArticleWithFeed(article(rawDescription = RAW_TEASER), feed()))

            fixture.vm.initData(ARTICLE_ID)
            // Give a hypothetically queued extraction every chance to run.
            Thread.sleep(200)

            val state = fixture.vm.readerStateStateFlow.value
            assertEquals(ReaderState.Description(RAW_TEASER), state.content)
            assertFalse(state.isExtracting)
            assertTrue("E20: toggle OFF must gate the adaptive path", fixture.extractCalls.isEmpty())
        } finally {
            fixture.close()
        }
    }

    /** `feed.isFullContent` + toggle OFF → force extraction anyway (E9/E20). */
    @Test
    fun flaggedFeedForcesExtractionEvenWithToggleOffAndNonTeaserBody() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.OFF)
        try {
            // 800 plain chars ≥ 700 → NOT a teaser candidate (E6 cond 3);
            // only the per-feed force contract can extract this row.
            val awf = ArticleWithFeed(
                article(rawDescription = RAW_FULL + "x".repeat(800)),
                feed(isFullContent = true),
            )
            fixture.seedArticle(awf)
            fixture.gateExtract = true
            fixture.nextOutcome = ExtractOutcome.Stored(ARTICLE_ID, Date(NOW))
            fixture.storedRow = awf.withFullContent(STORED_EXTRACTION)

            fixture.vm.initData(ARTICLE_ID)
            assertTrue(fixture.entered.await(10, TimeUnit.SECONDS))
            assertEquals(ReaderState.Loading, fixture.vm.readerStateStateFlow.value.content)

            fixture.gate.countDown()
            val done = fixture.awaitState { !it.isExtracting }

            assertEquals(ReaderState.FullContent(STORED_EXTRACTION), done.content)
            // The force path runs the coordinator's forced best-effort mode.
            assertEquals(listOf(ExtractMode.PREFETCH), fixture.extractCalls.toList())
        } finally {
            fixture.close()
        }
    }

    // ------------------------------------------------------------------
    // E11 — MANUAL extract
    // ------------------------------------------------------------------

    /** MANUAL on a non-teaser with the toggle OFF still runs (E11). */
    @Test
    fun manualExtractRunsOnNonTeaserRegardlessOfTriggerAndToggle() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.OFF)
        try {
            val awf = ArticleWithFeed(article(rawDescription = RAW_FULL), feed())
            fixture.seedArticle(awf)
            fixture.nextOutcome = ExtractOutcome.Stored(ARTICLE_ID, Date(NOW))
            fixture.storedRow = awf.withFullContent(STORED_EXTRACTION)

            fixture.vm.initData(ARTICLE_ID)
            // Non-teaser + toggle OFF → plain description, no extraction.
            assertEquals(
                ReaderState.Description(RAW_FULL),
                fixture.vm.readerStateStateFlow.value.content,
            )
            assertTrue(fixture.extractCalls.isEmpty())

            // Explicit MANUAL extract — trigger/toggle-independent (E11).
            fixture.vm.extractFullContent()
            assertEquals(ReaderState.Loading, fixture.vm.readerStateStateFlow.value.content)
            assertTrue(fixture.vm.readerStateStateFlow.value.isExtracting)
            // Busy guard: a second tap while in flight is ignored (E11).
            fixture.vm.extractFullContent()

            val done = fixture.awaitState { !it.isExtracting }
            assertEquals(ReaderState.FullContent(STORED_EXTRACTION), done.content)
            assertEquals(listOf(ExtractMode.MANUAL), fixture.extractCalls.toList())
            verify(fixture.articleListUseCase, never()).rollGeneration()
        } finally {
            fixture.close()
        }
    }

    /** MANUAL failure → Description + transient tint that clears after ≤3 s (E13). */
    @Test
    fun manualFailureShowsTransientTintThatClearsAfterThreeSeconds() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.OFF)
        try {
            val awf = ArticleWithFeed(article(rawDescription = RAW_FULL), feed())
            fixture.seedArticle(awf)
            fixture.nextOutcome = ExtractOutcome.Failure(ExtractFailureReason.TIMEOUT)

            fixture.vm.initData(ARTICLE_ID)
            fixture.vm.extractFullContent()

            val failed = fixture.awaitState { it.extractFailed }
            assertEquals(ReaderState.Description(RAW_FULL), failed.content)

            // E13 — ephemeral: cleared by the VM after ≤3 s, never persisted.
            val cleared = fixture.awaitState(timeoutMs = 6_000) { !it.extractFailed }
            assertEquals(ReaderState.Description(RAW_FULL), cleared.content)
        } finally {
            fixture.close()
        }
    }

    // ------------------------------------------------------------------
    // E12 — RE_EXTRACT
    // ------------------------------------------------------------------

    /** RE_EXTRACT success while viewing Description: row updated, VIEW NOT SWITCHED. */
    @Test
    fun reExtractSuccessWhileViewingDescriptionUpdatesRowWithoutSwitchingView() {
        val fixture = Fixture()
        try {
            val awf = ArticleWithFeed(article(fullContent = STORED_EXTRACTION), feed())
            fixture.seedArticle(awf)
            fixture.nextOutcome = ExtractOutcome.Stored(ARTICLE_ID, Date(NOW))
            fixture.storedRow = awf.withFullContent(FRESH_EXTRACTION)

            fixture.vm.initData(ARTICLE_ID)
            assertEquals(
                ReaderState.FullContent(STORED_EXTRACTION),
                fixture.vm.readerStateStateFlow.value.content,
            )
            // User toggles to the feed description (E11 escape hatch — keeps
            // the S22 diff pane reachable, E22b).
            fixture.vm.renderDescriptionContent()
            assertEquals(
                ReaderState.Description(awf.article.rawDescription),
                fixture.vm.readerStateStateFlow.value.content,
            )

            fixture.vm.reExtractFullContent()
            // E12 — content stays rendered while in flight (no Loading swap).
            assertTrue(fixture.vm.readerStateStateFlow.value.isExtracting)
            assertTrue(fixture.vm.readerStateStateFlow.value.content is ReaderState.Description)

            val done = fixture.awaitState { !it.isExtracting }
            // E12 — VIEW NOT SWITCHED: still Description.
            assertTrue("re-extract must not force-switch the view", done.content is ReaderState.Description)
            // …but the ROW was updated in place.
            assertEquals(
                FRESH_EXTRACTION,
                fixture.vm.readingUiState.value.articleWithFeed?.article?.fullContent,
            )
            assertEquals(listOf(ExtractMode.RE_EXTRACT), fixture.extractCalls.toList())
            verify(fixture.articleListUseCase, never()).rollGeneration()
        } finally {
            fixture.close()
        }
    }

    /** RE_EXTRACT success while viewing FullContent: the view refreshes in place (E12). */
    @Test
    fun reExtractSuccessWhileViewingFullContentUpdatesViewInPlace() {
        val fixture = Fixture()
        try {
            val awf = ArticleWithFeed(article(fullContent = STORED_EXTRACTION), feed())
            fixture.seedArticle(awf)
            fixture.nextOutcome = ExtractOutcome.Stored(ARTICLE_ID, Date(NOW))
            fixture.storedRow = awf.withFullContent(FRESH_EXTRACTION)

            fixture.vm.initData(ARTICLE_ID)
            fixture.vm.reExtractFullContent()

            val done = fixture.awaitState { !it.isExtracting }
            assertEquals(ReaderState.FullContent(FRESH_EXTRACTION), done.content)
            assertEquals(listOf(ExtractMode.RE_EXTRACT), fixture.extractCalls.toList())
        } finally {
            fixture.close()
        }
    }

    /** RE_EXTRACT failure: stored extraction untouched, transient tint, no view change (E12/E13/E21). */
    @Test
    fun reExtractFailureLeavesStoredContentUntouchedAndShowsTint() {
        val fixture = Fixture()
        try {
            val awf = ArticleWithFeed(article(fullContent = STORED_EXTRACTION), feed())
            fixture.seedArticle(awf)
            fixture.nextOutcome = ExtractOutcome.Failure(ExtractFailureReason.LENGTH_REJECTED)

            fixture.vm.initData(ARTICLE_ID)
            fixture.vm.reExtractFullContent()

            val done = fixture.awaitState { !it.isExtracting }
            assertEquals(ReaderState.FullContent(STORED_EXTRACTION), done.content)
            assertTrue("explicit re-extract failure raises the transient tint", done.extractFailed)
            assertEquals(
                STORED_EXTRACTION,
                fixture.vm.readingUiState.value.articleWithFeed?.article?.fullContent,
            )
        } finally {
            fixture.close()
        }
    }

    /** RE_EXTRACT is extracted-only: a non-extracted row ignores the action (E12). */
    @Test
    fun reExtractIsNoOpOnNonExtractedRow() {
        // Toggle OFF keeps the on-open path out of this pin: the ONLY thing
        // under test is that re-extract refuses a non-extracted row.
        val fixture = Fixture(autoExtract = AutoExtractPreference.OFF)
        try {
            val awf = ArticleWithFeed(article(rawDescription = RAW_FULL), feed())
            fixture.seedArticle(awf)

            fixture.vm.initData(ARTICLE_ID)
            fixture.vm.reExtractFullContent()
            Thread.sleep(200)

            assertFalse(fixture.vm.readerStateStateFlow.value.isExtracting)
            assertTrue(fixture.extractCalls.isEmpty())
        } finally {
            fixture.close()
        }
    }

    // ------------------------------------------------------------------
    // E22 — Wave E interaction pins
    // ------------------------------------------------------------------

    /** E22c — a successful extraction never rolls the list generation. */
    @Test
    fun extractionSuccessNeverRollsListGeneration() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.ON)
        try {
            val awf = ArticleWithFeed(article(rawDescription = RAW_TEASER), feed())
            fixture.seedArticle(awf)
            fixture.nextOutcome = ExtractOutcome.Stored(ARTICLE_ID, Date(NOW))
            fixture.storedRow = awf.withFullContent(STORED_EXTRACTION)

            fixture.vm.initData(ARTICLE_ID)
            val done = fixture.awaitState { !it.isExtracting }
            assertEquals(ReaderState.FullContent(STORED_EXTRACTION), done.content)
            // The generation flow is exposed straight from the use-case; the
            // VM must never trigger a roll or a paging refresh for extraction.
            verify(fixture.articleListUseCase, never()).rollGeneration()
            assertNull(fixture.vm.listGenerationFlow.value)
        } finally {
            fixture.close()
        }
    }

    /**
     * E22b (state-level) — an extracted row keeps the S22 diff pane
     * reachable: toggling to Description yields the Description state (the
     * ReadingPage `originalContent`-only-on-Description mapping is untouched
     * and reads the row's snapshot).
     */
    @Test
    fun extractedRowKeepsDiffPaneReachableOnDescriptionPath() {
        val fixture = Fixture()
        try {
            val awf = ArticleWithFeed(
                article(fullContent = STORED_EXTRACTION, originalContent = "<p>old snapshot</p>"),
                feed(),
            )
            fixture.seedArticle(awf)

            fixture.vm.initData(ARTICLE_ID)
            assertEquals(
                ReaderState.FullContent(STORED_EXTRACTION),
                fixture.vm.readerStateStateFlow.value.content,
            )

            fixture.vm.renderDescriptionContent()
            assertTrue(fixture.vm.readerStateStateFlow.value.content is ReaderState.Description)
            // The S22 mapping input survives on the in-memory row.
            assertEquals(
                "<p>old snapshot</p>",
                fixture.vm.readingUiState.value.articleWithFeed?.article?.originalContent,
            )
        } finally {
            fixture.close()
        }
    }

    // ------------------------------------------------------------------
    // Review lane 2 — race & null-safety pins
    // ------------------------------------------------------------------

    /**
     * Lane 2 defect 1 — a STALE `listIndex` (the list mutated after the tap)
     * must not open the article sitting at that index: `initData` trusts the
     * index item only when it actually holds the requested `articleId`, else
     * it falls through to the id scan.
     *
     * REDCHECK: dropping the `.takeIf { it.articleWithFeed.article.id ==
     * articleId }` guard on `itemByIndex` renders the index-0 article (X)
     * instead of the requested A, turning this test red.
     */
    @Test
    fun staleListIndexStillOpensTheRequestedArticleById() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.OFF)
        try {
            val x = ArticleWithFeed(
                article(id = "1\$x", title = "Wrong", fullContent = "<p>wrong body</p>"),
                feed(),
            )
            val a = ArticleWithFeed(
                article(id = ARTICLE_ID, title = "Right", fullContent = STORED_EXTRACTION),
                feed(),
            )
            // Index 0 holds X; the requested article A sits at index 1.
            fixture.seedArticles(x, a)

            fixture.vm.initData(articleId = ARTICLE_ID, listIndex = 0)

            val state = fixture.vm.readerStateStateFlow.value
            assertEquals("stale index must not open the wrong row", ARTICLE_ID, state.articleId)
            assertEquals(ReaderState.FullContent(STORED_EXTRACTION), state.content)
            assertEquals(
                ARTICLE_ID,
                fixture.vm.readingUiState.value.articleWithFeed?.article?.id,
            )
        } finally {
            fixture.close()
        }
    }

    /**
     * Lane 2 defect 1 — an article that cannot be resolved (deleted, or a
     * concurrent `clearReadingData`) must NOT NPE on the `findArticleById`
     * fallback; `initData` resets the reader state instead.
     *
     * REDCHECK: restoring `findArticleById(articleId)!!` throws an NPE for a
     * missing id, turning this test red.
     */
    @Test
    fun missingArticleResetsReaderStateInsteadOfCrashing() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.OFF)
        try {
            // Not in the snapshot list AND findArticleById returns null.
            fixture.seedArticles()
            fixture.storedRow = null

            fixture.vm.initData(articleId = "1\$gone", listIndex = null)

            val state = fixture.vm.readerStateStateFlow.value
            assertNull("missing article must reset articleId", state.articleId)
            assertNull(fixture.vm.readingUiState.value.articleWithFeed)
        } finally {
            fixture.close()
        }
    }

    /**
     * Lane 2 defect 1 — `updateReadStatus` must not NPE when a concurrent
     * `clearReadingData()` nulls the open row (was `it.articleWithFeed!!`).
     *
     * REDCHECK: restoring `it.articleWithFeed!!` throws an NPE after a clear,
     * turning this test red.
     */
    @Test
    fun updateReadStatusAfterClearDoesNotCrash() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.OFF)
        try {
            fixture.seedArticle(ArticleWithFeed(article(fullContent = STORED_EXTRACTION), feed()))
            fixture.vm.initData(ARTICLE_ID)

            // Simulate the concurrent clear that nulls the open row.
            fixture.vm.clearReadingData()
            assertNull(fixture.vm.readingUiState.value.articleWithFeed)

            // Must be a safe no-op, not an NPE.
            fixture.vm.updateReadStatus(isUnread = false)
            assertNull(fixture.vm.readingUiState.value.articleWithFeed)
        } finally {
            fixture.close()
        }
    }

    /**
     * Lane 2 defect 2 — switching from article A to B while A's AUTO
     * extraction is in flight: A's completion (even a SUCCESS) must not
     * overwrite B's rendered state. `initData` bumps the open-generation and
     * cancels A's job; the completion path also re-checks the current
     * `articleId`. The fixture's synchronous mocks mean the generation /
     * articleId guard is the deciding defense (caller cancellation cannot
     * interrupt the blocked IO thread).
     *
     * REDCHECK: neutralizing the guards (dispatch the handler unconditionally
     * and drop the `articleId` re-checks in `onExtractionStored`) lets A's
     * Stored result clobber B, turning this test red.
     */
    @Test
    fun crossArticleExtractionCompletionIsIgnoredAfterSwitch() {
        val fixture = Fixture(autoExtract = AutoExtractPreference.ON)
        try {
            val a = ArticleWithFeed(article(id = ARTICLE_ID, rawDescription = RAW_TEASER), feed())
            val b = ArticleWithFeed(
                article(id = ARTICLE_ID_B, rawDescription = RAW_FULL, fullContent = OTHER_EXTRACTION),
                feed(),
            )
            fixture.seedArticles(a, b)
            fixture.gateExtract = true
            fixture.nextOutcome = ExtractOutcome.Stored(ARTICLE_ID, Date(NOW))
            fixture.storedRow = a.withFullContent(FRESH_EXTRACTION)

            // Open A: teaser + toggle ON → AUTO extraction in flight (gated).
            fixture.vm.initData(articleId = ARTICLE_ID, listIndex = 0)
            assertTrue("A extraction must be in flight", fixture.entered.await(10, TimeUnit.SECONDS))
            assertEquals(ReaderState.Loading, fixture.vm.readerStateStateFlow.value.content)
            assertTrue(fixture.vm.readerStateStateFlow.value.isExtracting)

            // Switch to B while A is still extracting. B is already extracted →
            // renders immediately and launches no competing extraction.
            fixture.vm.initData(articleId = ARTICLE_ID_B, listIndex = 1)
            val afterSwitch = fixture.vm.readerStateStateFlow.value
            assertEquals(ARTICLE_ID_B, afterSwitch.articleId)
            assertEquals(ReaderState.FullContent(OTHER_EXTRACTION), afterSwitch.content)
            assertFalse(afterSwitch.isExtracting)

            // Let A's extraction finish; its handler must be ignored as stale.
            fixture.gate.countDown()
            assertTrue("IO barrier timed out", fixture.runIoBarrier())

            val done = fixture.vm.readerStateStateFlow.value
            assertEquals("B must remain the open article", ARTICLE_ID_B, done.articleId)
            assertEquals(
                "A's stale Stored result must not overwrite B",
                ReaderState.FullContent(OTHER_EXTRACTION),
                done.content,
            )
            assertEquals(
                ARTICLE_ID_B,
                fixture.vm.readingUiState.value.articleWithFeed?.article?.id,
            )
        } finally {
            fixture.close()
        }
    }
}
