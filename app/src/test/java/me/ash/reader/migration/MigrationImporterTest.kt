package me.ash.reader.migration

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import junit.framework.TestCase.assertNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleIdentity
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.importing.ImportIdentity
import me.ash.reader.domain.model.importing.ImportRun
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.domain.model.search.SavedSearch
import me.ash.reader.domain.repository.ActionRuleDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.ImportChunk
import me.ash.reader.domain.repository.ImportDao
import me.ash.reader.domain.repository.SavedSearchDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.migration.FeedBucketImporter
import me.ash.reader.domain.service.migration.ImportLedger
import me.ash.reader.domain.service.migration.ImportSchemaChecker
import me.ash.reader.domain.service.migration.SecretGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Acceptance #8 — S32a migration importer against SYNTHETIC web-schema
 * fixture DBs built in-test via raw SQLite (sqlite-jdbc, test scope). NEVER
 * a copy of live data (D16).
 *
 * The fixture DDL/DML mirrors scripts/make_test_feedbucket_db.py (canonical
 * 3-feed / 7-entry fixture; expected counts are hand-computed in
 * app/src/test/fixtures/migration/README.md). Target-side state is a
 * Mockito-backed in-memory store over the real DAO interfaces.
 */
class MigrationImporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_761_000_000_000L

    // --- target-side fake state -------------------------------------------
    private val runs = mutableMapOf<Long, ImportRun>()
    private var runSeq = 0L
    private val identities = mutableListOf<ImportIdentity>()
    private val feeds = mutableMapOf<String, Feed>()
    private val groups = mutableMapOf<String, Group>()
    private val articles = mutableMapOf<String, Article>()
    private val searches = mutableMapOf<Long, SavedSearch>()
    private val rules = mutableMapOf<Long, ActionRule>()
    private var searchSeq = 0L
    private var ruleSeq = 0L

    /** Exception injection points (resume/crash tests). */
    private var failFeedInsertWithUrl: String? = null
    private var failCompletedStateUpdate = false

    private lateinit var feedDao: FeedDao
    private lateinit var groupDao: GroupDao
    private lateinit var articleDao: ArticleDao
    private lateinit var savedSearchDao: SavedSearchDao
    private lateinit var actionRuleDao: ActionRuleDao
    private lateinit var importDao: ImportDao
    private lateinit var accountService: AccountService
    private lateinit var importer: FeedBucketImporter

    /** Query log capture for the SecretGuard query-log assertion. */
    private var lastSource: JdbcSourceDb? = null

    @Before
    fun setUp() {
        runs.clear(); identities.clear(); feeds.clear(); groups.clear()
        articles.clear(); searches.clear(); rules.clear()
        runSeq = 0L; searchSeq = 0L; ruleSeq = 0L
        failFeedInsertWithUrl = null
        failCompletedStateUpdate = false
        lastSource = null

        val io: CoroutineDispatcher = Dispatchers.Unconfined

        feedDao = mock()
        groupDao = mock()
        articleDao = mock()
        savedSearchDao = mock()
        actionRuleDao = mock()
        importDao = mock()
        accountService = mock()

        runBlocking {
            whenever(feedDao.queryById(any())).thenAnswer { feeds[it.getArgument(0)] }
            whenever(feedDao.insertAll(any())).thenAnswer { invocation ->
                val list = invocation.getArgument<List<Feed>>(0)
                failFeedInsertWithUrl?.let { url ->
                    if (list.any { it.url == url }) {
                        throw RuntimeException("injected crash on feed insert")
                    }
                }
                list.forEach { feeds[it.id] = it }
                Unit
            }
            whenever(feedDao.deleteById(any())).thenAnswer {
                feeds.remove(it.getArgument(0))
                Unit
            }
            // E7 — importer median hook: mirror the write into the fake store.
            whenever(feedDao.updateMedianBodyLength(any(), any())).thenAnswer { invocation ->
                val id = invocation.getArgument<String>(0)
                val median = invocation.getArgument<Int>(1)
                feeds[id]?.medianBodyLength = median
                Unit
            }

            whenever(groupDao.queryById(any())).thenAnswer { groups[it.getArgument(0)] }
            whenever(groupDao.insertAll(any())).thenAnswer { invocation ->
                invocation.getArgument<List<Group>>(0).forEach { groups[it.id] = it }
                Unit
            }

            whenever(articleDao.insertList(any())).thenAnswer { invocation ->
                invocation.getArgument<List<Article>>(0).forEach { articles[it.id] = it }
                Unit
            }
            whenever(articleDao.queryById(any())).thenAnswer { invocation ->
                val id = invocation.getArgument<String>(0)
                articles[id]?.let { article ->
                    ArticleWithFeed(
                        article = article,
                        feed = feeds[article.feedId] ?: Feed(
                            id = article.feedId, name = "", url = "",
                            groupId = "", accountId = 1,
                        ),
                    )
                }
            }
            whenever(articleDao.deleteById(any())).thenAnswer {
                articles.remove(it.getArgument(0))
                Unit
            }
            whenever(articleDao.queryIdsByFeed(any())).thenAnswer { invocation ->
                val feedId = invocation.getArgument<String>(0)
                articles.values.filter { it.feedId == feedId }.map { it.id }
            }

            whenever(savedSearchDao.insert(any())).thenAnswer { invocation ->
                val saved = invocation.getArgument<SavedSearch>(0)
                val id = ++searchSeq
                searches[id] = saved.copy(id = id)
                id
            }
            whenever(savedSearchDao.queryById(any())).thenAnswer { searches[it.getArgument(0)] }
            whenever(savedSearchDao.deleteById(any())).thenAnswer {
                searches.remove(it.getArgument(0))
                Unit
            }

            whenever(actionRuleDao.insert(any())).thenAnswer { invocation ->
                val rule = invocation.getArgument<ActionRule>(0)
                val id = ++ruleSeq
                rules[id] = rule.copy(id = id)
                id
            }
            whenever(actionRuleDao.queryById(any())).thenAnswer { rules[it.getArgument(0)] }
            whenever(actionRuleDao.deleteById(any())).thenAnswer {
                rules.remove(it.getArgument(0))
                Unit
            }

            whenever(importDao.upsertRun(any())).thenAnswer { invocation ->
                val run = invocation.getArgument<ImportRun>(0)
                val id = if (run.id == 0L) ++runSeq else run.id
                runs[id] = run.copy(id = id)
                id
            }
            whenever(importDao.queryRun(any())).thenAnswer { runs[it.getArgument(0)] }
            whenever(importDao.queryRunningRun()).thenAnswer {
                runs.values.filter { it.state == ImportRun.STATE_RUNNING }.maxByOrNull { it.id }
            }
            whenever(importDao.queryResumableRun()).thenAnswer {
                runs.values.filter {
                    it.state == ImportRun.STATE_RUNNING ||
                        (it.state == ImportRun.STATE_FAILED && it.sourcePath != null)
                }.maxByOrNull { it.id }
            }
            whenever(importDao.queryLatestRun(any())).thenAnswer { invocation ->
                runs.values.filter { it.kind == invocation.getArgument<String>(0) }
                    .maxByOrNull { run -> run.id }
            }
            whenever(importDao.updateRunState(any(), any(), any(), any())).thenAnswer { invocation ->
                val id = invocation.getArgument<Long>(0)
                if (invocation.getArgument<String>(1) == ImportRun.STATE_COMPLETED &&
                    failCompletedStateUpdate
                ) {
                    throw RuntimeException("injected crash before run completion")
                }
                runs[id] = runs.getValue(id).copy(
                    state = invocation.getArgument(1),
                    finishedAt = invocation.getArgument(2),
                    countsJson = invocation.getArgument(3),
                )
                Unit
            }
            whenever(importDao.updateRunSourcePath(any(), anyOrNull())).thenAnswer { invocation ->
                val id = invocation.getArgument<Long>(0)
                runs[id] = runs.getValue(id).copy(sourcePath = invocation.getArgument(1))
                Unit
            }
            whenever(importDao.updateRunFingerprint(any(), any())).thenAnswer { invocation ->
                val id = invocation.getArgument<Long>(0)
                runs[id] = runs.getValue(id)
                    .copy(sourceFingerprint = invocation.getArgument(1))
                Unit
            }
            whenever(importDao.queryIdentity(any(), any(), any())).thenAnswer { invocation ->
                val source = invocation.getArgument<String>(0)
                val sourceId = invocation.getArgument<String>(1)
                val kind = invocation.getArgument<String>(2)
                identities.firstOrNull {
                    it.source == source && it.sourceId == sourceId && it.targetKind == kind
                }
            }
            whenever(importDao.insertIdentities(any())).thenAnswer { invocation ->
                val list = invocation.getArgument<List<ImportIdentity>>(0)
                list.map { identity ->
                    val exists = identities.any {
                        it.source == identity.source && it.sourceId == identity.sourceId &&
                            it.targetKind == identity.targetKind
                    }
                    if (exists) -1L else {
                        identities.add(identity)
                        identities.size.toLong()
                    }
                }
            }
            whenever(importDao.queryCreatedIdentitiesByRun(any())).thenAnswer { invocation ->
                val runId = invocation.getArgument<Long>(0)
                identities.filter { it.createdByRunId == runId }
            }
            whenever(importDao.deleteIdentitiesByRun(any())).thenAnswer { invocation ->
                val runId = invocation.getArgument<Long>(0)
                identities.removeAll { it.importRunId == runId }
                Unit
            }
            whenever(importDao.queryRunBySourcePath(any())).thenAnswer { invocation ->
                val path = invocation.getArgument<String>(0)
                runs.values.firstOrNull { it.sourcePath == path }
            }
            whenever(importDao.runInImportTransaction(any())).thenAnswer { invocation ->
                val chunk = invocation.getArgument<ImportChunk>(0)
                runBlocking { chunk() }
                Unit
            }

            whenever(accountService.getCurrentAccountId()).thenReturn(1)
        }

        importer = FeedBucketImporter(
            importsRoot = tmp.newFolder("imports"),
            feedDao = feedDao,
            groupDao = groupDao,
            articleDao = articleDao,
            savedSearchDao = savedSearchDao,
            actionRuleDao = actionRuleDao,
            importDao = importDao,
            accountService = accountService,
            ioDispatcher = io,
        )
        importer.openSource = { file ->
            JdbcSourceDb.open(file).also { lastSource = it }
        }
    }

    // ------------------------------------------------------------------
    // Fixture builders (raw SQLite, in-test — D16 synthetic only)
    // ------------------------------------------------------------------

    /** Canonical web-schema fixture; mirrors make_test_feedbucket_db.py. */
    private fun buildWebFixtureDb(file: File) {
        val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(FIXTURE_SCHEMA)
                st.executeUpdate(FIXTURE_DATA)
            }
        }
    }

    /** v1 package shape: allowlisted tables WITHOUT user_id + package_meta. */
    private fun buildPackageFixtureDb(file: File, schemaVersion: Int = 1) {
        val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(FIXTURE_SCHEMA)
                st.executeUpdate(FIXTURE_DATA)
                // The export tool strips user-identifying columns; mirror it.
                st.executeUpdate("ALTER TABLE saved_searches RENAME TO saved_searches_old")
                st.executeUpdate(
                    """
                    CREATE TABLE saved_searches (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        query TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    "INSERT INTO saved_searches(id, name, query, created_at) " +
                        "SELECT id, name, query, created_at FROM saved_searches_old",
                )
                st.executeUpdate("DROP TABLE saved_searches_old")
                st.executeUpdate(
                    """
                    CREATE TABLE package_meta (
                        schemaVersion INTEGER NOT NULL,
                        createdAt TEXT NOT NULL,
                        sourceSha256 TEXT NOT NULL,
                        countsJson TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    INSERT INTO package_meta(schemaVersion, createdAt, sourceSha256, countsJson)
                    VALUES ($schemaVersion, '2026-11-17T00:00:00+00:00', 'abc', '{}');
                    """.trimIndent(),
                )
            }
        }
    }

    private fun stageFile(file: File): Long = runBlocking {
        importer.stageCopy(file.inputStream(), "content://picked/${file.name}", now)
    }

    /**
     * A FRESH importer over the SAME DAO state + imports dir — simulates
     * process recreation against persisted run/identity data.
     */
    private fun newImporter(): FeedBucketImporter {
        val io: CoroutineDispatcher = Dispatchers.Unconfined
        return FeedBucketImporter(
            importsRoot = importerDir(),
            feedDao = feedDao,
            groupDao = groupDao,
            articleDao = articleDao,
            savedSearchDao = savedSearchDao,
            actionRuleDao = actionRuleDao,
            importDao = importDao,
            accountService = accountService,
            ioDispatcher = io,
        ).apply {
            openSource = { file ->
                JdbcSourceDb.open(file).also { lastSource = it }
            }
        }
    }

    private fun previewReady(runId: Long) = runBlocking {
        val outcome = importer.preview(runId)
        assertTrue("expected Ready, got $outcome", outcome is FeedBucketImporter.PreviewOutcome.Ready)
        (outcome as FeedBucketImporter.PreviewOutcome.Ready).preview
    }

    private fun applyCompleted(runId: Long, fingerprint: String) = runBlocking {
        val outcome = importer.apply(runId, fingerprint, now)
        assertTrue("expected Completed, got $outcome", outcome is FeedBucketImporter.ApplyOutcome.Completed)
        (outcome as FeedBucketImporter.ApplyOutcome.Completed).ledger
    }

    private fun expectedFeedId(feedUrl: String) =
        "1\$${ArticleIdentity.sha256Hex("feed\n$feedUrl")}"

    private fun expectedGroupId(tag: String) =
        "1\$${ArticleIdentity.sha256Hex("group\n$tag")}"

    private fun expectedArticleId(feedUrl: String, externalId: String) =
        ArticleIdentity.articleId(1, feedUrl, externalId)

    private fun epoch(iso: String) = Instant.parse(iso).toEpochMilli()

    /** A stable digest over ALL target-side state (rollback zero-writes proof). */
    private fun targetDigest(): String {
        val dump = buildString {
            feeds.values.sortedBy { it.id }.forEach { append("F:${it.id}:${it.name}:${it.tags}\n") }
            groups.values.sortedBy { it.id }.forEach { append("G:${it.id}:${it.name}\n") }
            articles.values.sortedBy { it.id }.forEach { append("A:${it.id}:${it.title}:${it.stateVersion}\n") }
            searches.values.sortedBy { it.id }.forEach { append("S:${it.id}:${it.name}\n") }
            rules.values.sortedBy { it.id }.forEach { append("R:${it.id}:${it.title}\n") }
        }
        return ArticleIdentity.sha256Hex(dump)
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun previewCountsExactOnCanonicalFixture() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)
        val runId = stageFile(fixture)
        val preview = previewReady(runId)

        val c = preview.counts
        assertEquals(3, c.subscriptions)
        assertEquals(2, c.tags)
        assertEquals(2, c.savedSearches)
        assertEquals(7, c.entries)
        assertEquals(4, c.unread)
        assertEquals(2, c.starred)
        assertEquals(1, c.updated)
        assertEquals(2, c.recentlyRead)
        assertEquals(1, c.standardActions)
        assertEquals(1, c.muteActions)
        assertEquals(2, c.snapshots)
        assertEquals(2, c.extracted)
        assertEquals(listOf("Alpha Blog", "Beta News", "Gamma Lab"), preview.firstSubscriptionTitles)
        assertTrue(preview.fingerprint.isNotBlank())
        assertTrue(preview.kind is ImportSchemaChecker.SourceKind.RawCopy)
        // Preview is no-writes (ledger untouched since staging).
        assertEquals("{}", runs.getValue(runId).countsJson)
    }

    @Test
    fun packageAndRawPreviewParity() = runBlocking {
        val raw = File(tmp.root, "raw.db")
        val pkg = File(tmp.root, "pkg.db")
        buildWebFixtureDb(raw)
        buildPackageFixtureDb(pkg)

        val rawPreview = previewReady(stageFile(raw))
        val pkgPreview = previewReady(stageFile(pkg))

        assertTrue(pkgPreview.kind is ImportSchemaChecker.SourceKind.PackageV1)
        assertEquals(rawPreview.counts, pkgPreview.counts)
        assertEquals(rawPreview.firstSubscriptionTitles, pkgPreview.firstSubscriptionTitles)
    }

    @Test
    fun unknownPackageVersionRejectedWithNothingWritten() = runBlocking {
        val pkg = File(tmp.root, "pkg-v2.db")
        buildPackageFixtureDb(pkg, schemaVersion = 2)
        val runId = stageFile(pkg)
        val before = targetDigest()

        val outcome = importer.preview(runId)
        assertTrue(outcome is FeedBucketImporter.PreviewOutcome.Rejected)
        val reason = (outcome as FeedBucketImporter.PreviewOutcome.Rejected).reason
        assertTrue("precise version reason, got: $reason", reason.contains("schemaVersion: 2"))

        assertEquals(before, targetDigest())
        assertEquals(ImportRun.STATE_FAILED, runs.getValue(runId).state)
        // Retained copy purged on rejection (S32a step 1).
        assertNull(runs.getValue(runId).sourcePath)
    }

    @Test
    fun schemaInvalidRejectedZeroWrites() = runBlocking {
        val broken = File(tmp.root, "broken.db")
        buildWebFixtureDb(broken)
        DriverManager.getConnection("jdbc:sqlite:${broken.absolutePath}").use { c ->
            c.createStatement().use { it.executeUpdate("DROP TABLE starred") }
        }
        val runId = stageFile(broken)
        val before = targetDigest()

        val outcome = importer.preview(runId)
        assertTrue(outcome is FeedBucketImporter.PreviewOutcome.Rejected)
        assertTrue(
            ((outcome as FeedBucketImporter.PreviewOutcome.Rejected).reason)
                .contains("starred"),
        )
        assertEquals(before, targetDigest())
        assertEquals(0, feeds.size + articles.size)
    }

    @Test
    fun fingerprintMismatchAbortsWithZeroWrites() = runBlocking {
        val fixtureA = File(tmp.root, "a.db")
        buildWebFixtureDb(fixtureA)
        val runId = stageFile(fixtureA)
        val preview = previewReady(runId)
        val before = targetDigest()

        // The retained copy is replaced with DIFFERENT bytes before apply.
        val fixtureB = File(tmp.root, "b.db")
        buildPackageFixtureDb(fixtureB)
        val staged = File(importerDir(), "run-$runId.db")
        fixtureB.copyTo(staged, overwrite = true)

        val outcome = importer.apply(runId, preview.fingerprint, now)
        assertTrue(outcome is FeedBucketImporter.ApplyOutcome.FingerprintMismatch)
        assertEquals(before, targetDigest())
        assertEquals(0, feeds.size + articles.size)
        assertEquals(ImportRun.STATE_FAILED, runs.getValue(runId).state)
        val ledger = ImportLedger.fromJson(runs.getValue(runId).countsJson)
        assertEquals(1, ledger.failed)
        assertTrue(ledger.failures.single().contains("fingerprint mismatch"))
    }

    private fun importerDir() = tmp.root.listFiles()!!.first { it.isDirectory && it.name == "imports" }

    @Test
    fun fullApplyImportsFeedsEntriesSearchesRules() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)
        val runId = stageFile(fixture)
        val preview = previewReady(runId)

        val ledger = applyCompleted(runId, preview.fingerprint)
        assertEquals(ImportLedger(imported = 3, skipped = 0, failed = 0, searchesImported = 2, rulesImported = 2), ledger)

        // --- feeds (S32a step 4 mapping) ---
        assertEquals(3, feeds.size)
        val feed1 = feeds.getValue(expectedFeedId("https://alpha.example/feed.xml"))
        assertEquals("Alpha Blog", feed1.name)
        assertEquals("https://alpha.example", feed1.siteUrl)
        assertEquals("tech,science", feed1.tags)
        assertEquals(expectedGroupId("tech"), feed1.groupId)
        assertFalse(feed1.muted)
        assertTrue(feed1.showUpdates)
        assertEquals("tech", groups.getValue(feed1.groupId).name)

        val feed2 = feeds.getValue(expectedFeedId("https://beta.example/rss"))
        assertTrue(feed2.muted)
        assertFalse(feed2.showUpdates)
        assertEquals("", feed2.tags)
        assertEquals("1\$read_you_app_default_group", feed2.groupId)

        // --- articles ---
        assertEquals(7, articles.size)
        val a101 = articles.getValue(expectedArticleId("https://alpha.example/feed.xml", "guid-a1"))
        assertTrue(a101.isUnread)
        assertFalse(a101.isStarred)
        assertEquals("<p>Original body one</p>", a101.rawDescription)
        assertEquals("Original body one", a101.shortDescription)
        assertEquals("<p>Original body one</p>", a101.originalContent)
        assertEquals(epoch("2026-10-01T09:00:00Z"), a101.publishedAt!!.time)
        assertEquals(a101.publishedAt, a101.date)

        val a102 = articles.getValue(expectedArticleId("https://alpha.example/feed.xml", "guid-a2"))
        assertFalse(a102.isUnread)
        assertTrue(a102.isStarred)
        assertEquals("<p>Body two</p>", a102.fullContent)
        assertEquals(now, a102.extractedAt!!.time)
        assertEquals(epoch("2026-10-04T18:30:00Z"), a102.readAt!!.time)

        val a103 = articles.getValue(expectedArticleId("https://alpha.example/feed.xml", "guid-a3"))
        assertTrue(a103.isUpdated)
        assertEquals(epoch("2026-10-05T10:00:00Z"), a103.contentChangedAt!!.time)
        assertNull(a103.publishedAt)
        assertEquals(epoch("2026-10-03T09:05:00Z"), a103.date.time)

        val a202 = articles.getValue(expectedArticleId("https://beta.example/rss", "guid-b2"))
        assertEquals("<p>Original beta two</p>", a202.originalContent)
        assertEquals("<p>Beta body two</p>", a202.fullContent)

        // --- saved searches + action rules ---
        assertEquals(2, searches.size)
        assertEquals(2, rules.size)
        val standard = rules.values.single { it.type == ActionRule.TYPE_STANDARD }
        assertTrue(standard.allFeeds)
        assertEquals("mark_read", standard.effects)
        val mute = rules.values.single { it.type == ActionRule.TYPE_MUTE }
        assertEquals(expectedFeedId("https://gamma.example/atom.xml"), mute.feedIds)
        assertEquals("gamma", mute.query)

        // --- identity map provenance ---
        assertEquals(7 + 3 + 2 + 2, identities.size)
        identities.filter { it.targetKind == ImportIdentity.TARGET_ARTICLE }.forEach {
            assertEquals(runId, it.createdByRunId)
            assertEquals("0", it.stateStamp)
        }
        val feedIdentity = identities.single {
            it.targetKind == ImportIdentity.TARGET_FEED && it.sourceId == "feed:1"
        }
        assertEquals(runId, feedIdentity.createdByRunId)

        assertEquals(ImportRun.STATE_COMPLETED, runs.getValue(runId).state)
    }

    /**
     * X3 task 5 / E7+E24 — the importer recomputes each feed's
     * `medianBodyLength` at the end of its per-feed apply, sampled from the
     * imported rows' stored `rawDescription` values. Expected values are the
     * hand-computed UPPER medians (web `feed_median_body_len` parity) of the
     * canonical fixture's plain-text body lengths:
     *  - alpha: {17 "Original body one", 8 "Body two", 10 "Body three"} → sorted [8,10,17] → idx 3/2=1 → 10;
     *  - beta:  {13 "Beta body one", 17 "Original beta two"} → sorted [13,17] → idx 2/2=1 → 17;
     *  - gamma: {14 "Gamma body one", 14 "Gamma body two"} → sorted [14,14] → idx 1 → 14.
     */
    @Test
    fun applyComputesPerFeedMedianFromImportedEntries() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)
        val runId = stageFile(fixture)
        val preview = previewReady(runId)

        applyCompleted(runId, preview.fingerprint)

        val alpha = feeds.getValue(expectedFeedId("https://alpha.example/feed.xml"))
        val beta = feeds.getValue(expectedFeedId("https://beta.example/rss"))
        val gamma = feeds.getValue(expectedFeedId("https://gamma.example/atom.xml"))
        assertTrue("imported feed must get a NON-ZERO median from its entries", alpha.medianBodyLength > 0)
        assertEquals(10, alpha.medianBodyLength)
        assertEquals(17, beta.medianBodyLength)
        assertEquals(14, gamma.medianBodyLength)
    }

    @Test
    fun resumeAfterCrashContinuesFromIdentityMap() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)
        val runId = stageFile(fixture)
        val preview = previewReady(runId)

        // "Process death" after feed 2: feed-3 insert crashes the chunk, and
        // the run never reaches its completed state.
        failFeedInsertWithUrl = "https://gamma.example/atom.xml"
        failCompletedStateUpdate = true
        runCatching { importer.apply(runId, preview.fingerprint, now) }
        assertEquals(ImportRun.STATE_RUNNING, runs.getValue(runId).state)

        failFeedInsertWithUrl = null
        failCompletedStateUpdate = false
        val resumed = applyCompleted(runId, preview.fingerprint)
        // The completing pass imports only what the crashed pass left undone.
        assertEquals(1, resumed.imported)
        assertEquals(2, resumed.skipped)
        assertEquals(0, resumed.failed)
        // Searches/rules were already committed by the crashed attempt.
        assertEquals(0, resumed.searchesImported)
        assertEquals(0, resumed.rulesImported)

        // Everything imported exactly once; ledger totals stable.
        assertEquals(3, feeds.size)
        assertEquals(7, articles.size)
        assertEquals(2, searches.size)
        assertEquals(2, rules.size)
        assertEquals(7 + 3 + 2 + 2, identities.size)
    }

    @Test
    fun duplicateReImportAllSkipped() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)

        val first = stageFile(fixture)
        applyCompleted(first, previewReady(first).fingerprint)

        val second = stageFile(fixture)
        val ledger = applyCompleted(second, previewReady(second).fingerprint)
        assertEquals(0, ledger.imported)
        assertEquals(3, ledger.skipped)
        assertEquals(0, ledger.searchesImported)
        assertEquals(0, ledger.rulesImported)

        // No duplicated content anywhere.
        assertEquals(3, feeds.size)
        assertEquals(7, articles.size)
        assertEquals(2, searches.size)
        assertEquals(2, rules.size)
    }

    @Test
    fun removeImportedDeletesOnlyUnchangedRunCreatedRows() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)
        val runId = stageFile(fixture)
        applyCompleted(runId, previewReady(runId).fingerprint)

        // Edits since import: article 301 state change + feed 2 rename.
        val editedArticleId = expectedArticleId("https://gamma.example/atom.xml", "guid-g1")
        articles[editedArticleId] = articles.getValue(editedArticleId).copy(stateVersion = 9)
        val feed2Id = expectedFeedId("https://beta.example/rss")
        feeds[feed2Id] = feeds.getValue(feed2Id).copy(name = "Beta News (renamed)")

        // Pre-existing article under imported feed 3: removal must protect
        // the feed from FK-cascading it.
        val feed3Id = expectedFeedId("https://gamma.example/atom.xml")
        val feed1Id = expectedFeedId("https://alpha.example/feed.xml")
        val preExisting = Article(
            id = "1\$pre-existing", date = java.util.Date(now), title = "Pre-existing",
            rawDescription = "", shortDescription = "", link = "https://old.example/x",
            feedId = feed3Id, accountId = 1,
        )
        articles[preExisting.id] = preExisting

        val report = importer.removeImported(runId)
        // Deleted: 6 unchanged articles + feed 1 + 2 searches + 2 rules = 11.
        assertEquals(11, report.removed)
        // Skipped edited: article 301 + renamed feed 2.
        assertEquals(2, report.skippedEdited)
        // Skipped protected: feed 3 (retained articles would cascade).
        assertEquals(1, report.skippedProtected)

        assertFalse(feeds.containsKey(feed1Id))
        assertEquals(2, articles.size)
        assertEquals(0, searches.size)
        assertEquals(0, rules.size)
        // Kept: edited article, pre-existing article, renamed + protected feeds.
        assertTrue(articles.containsKey(editedArticleId))
        assertTrue(articles.containsKey(preExisting.id))
        assertTrue(feeds.containsKey(feed2Id))
        assertTrue(feeds.containsKey(feed3Id))
        assertEquals(0, identities.size)
    }

    @Test
    fun secretGuardQueryLogAndValuesClean() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)
        val runId = stageFile(fixture)
        val preview = previewReady(runId)
        applyCompleted(runId, preview.fingerprint)

        val source = lastSource!!
        SecretGuard.assertQueriesSecretFree(source.queriesIssued)
        assertTrue(
            "queries were issued (log must be non-trivial)",
            source.queriesIssued.size > 10,
        )
        // No imported value matches a session-token shape.
        val allValues = sequence {
            feeds.values.forEach { yieldAll(listOf(it.name, it.url, it.siteUrl, it.tags)) }
            articles.values.forEach { yieldAll(listOf(it.title, it.link, it.rawDescription)) }
            searches.values.forEach { yieldAll(listOf(it.name, it.query)) }
            rules.values.forEach { yieldAll(listOf(it.title, it.query, it.effects)) }
        }
        SecretGuard.assertValuesSecretFree(allValues)
    }

    @Test
    fun orphanGcRemovesCopiesOlderThanSevenDaysUnlessRunning() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)

        // Fresh copy of a RUNNING run: kept.
        val activeRun = stageFile(fixture)
        // Old orphaned copy: no run references it.
        val orphan = File(importerDir(), "run-999.db")
        fixture.copyTo(orphan)
        val old = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        orphan.setLastModified(old)

        val removed = importer.gcOrphanCopies(now = System.currentTimeMillis())
        assertEquals(1, removed)
        assertFalse(orphan.exists())
        val retained = File(importerDir(), "run-$activeRun.db")
        assertTrue(retained.exists())
        assertEquals(ImportRun.STATE_RUNNING, runs.getValue(activeRun).state)
    }

    @Test
    fun ledgerJsonRoundTripsWithEscapes() {
        val ledger = ImportLedger(
            imported = 3, skipped = 2, failed = 3,
            searchesImported = 2, rulesImported = 1,
            failures = listOf(
                "entry 103: bad \"quote\" and \\ slash",
                // Commas inside failure text must not split entries
                // (review round 1 finding 4).
                "feed 2 (Alpha, News): boom at feed.url, accountId",
                "comma, then \"quote\", then \\backslash\\, end",
            ),
        )
        assertEquals(ledger, ImportLedger.fromJson(ledger.toJson()))
        assertEquals(ImportLedger(), ImportLedger.fromJson(ImportLedger().toJson()))
    }

    // ------------------------------------------------------------------
    // Review round 1 — blocking-finding regression tests
    // ------------------------------------------------------------------

    @Test
    fun previewRejectsTokenShapedSourceValueWithNothingWritten() = runBlocking {
        val fixture = File(tmp.root, "evil-preview.db")
        buildWebFixtureDb(fixture)
        DriverManager.getConnection("jdbc:sqlite:${fixture.absolutePath}").use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "UPDATE entries SET title = " +
                        "'abcdefghijklmnopqrstuvwxyz0123456789.ABCDEFGHIJKLMNOP' " +
                        "WHERE id = 101",
                )
            }
        }
        val runId = stageFile(fixture)
        val before = targetDigest()

        val outcome = importer.preview(runId)
        assertTrue(
            "expected Rejected, got $outcome",
            outcome is FeedBucketImporter.PreviewOutcome.Rejected,
        )
        val reason = (outcome as FeedBucketImporter.PreviewOutcome.Rejected).reason
        assertTrue("precise reason, got: $reason", reason.contains("token", ignoreCase = true))
        assertEquals(before, targetDigest())
        assertEquals(0, feeds.size + articles.size)
        assertEquals(ImportRun.STATE_FAILED, runs.getValue(runId).state)
        // Secret-bearing retained copy must not linger on disk.
        assertNull(runs.getValue(runId).sourcePath)
    }

    @Test
    fun applyRejectsTokenShapedSourceValueWithZeroWrites() = runBlocking {
        val fixture = File(tmp.root, "evil-apply.db")
        buildWebFixtureDb(fixture)
        DriverManager.getConnection("jdbc:sqlite:${fixture.absolutePath}").use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "UPDATE entries SET title = " +
                        "'abcdefghijklmnopqrstuvwxyz0123456789.ABCDEFGHIJKLMNOP' " +
                        "WHERE id = 101",
                )
            }
        }
        val runId = stageFile(fixture)
        val fingerprint = runs.getValue(runId).sourceFingerprint
        val before = targetDigest()

        val outcome = importer.apply(runId, fingerprint, now)
        assertTrue(
            "expected Rejected, got $outcome",
            outcome is FeedBucketImporter.ApplyOutcome.Rejected,
        )
        val reason = (outcome as FeedBucketImporter.ApplyOutcome.Rejected).reason
        assertTrue("precise reason, got: $reason", reason.contains("token", ignoreCase = true))
        assertEquals(before, targetDigest())
        assertEquals(0, feeds.size + articles.size)
        assertEquals(ImportRun.STATE_FAILED, runs.getValue(runId).state)
        assertNull(runs.getValue(runId).sourcePath)
    }

    @Test
    fun importedHtmlIsSanitizedAtWriteTime() = runBlocking {
        val fixture = File(tmp.root, "dirty.db")
        buildWebFixtureDb(fixture)
        DriverManager.getConnection("jdbc:sqlite:${fixture.absolutePath}").use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    """UPDATE entries SET content =
                        '<p>safe body</p><script>alert(1)</script><img src="javascript:evil()" onerror="boom()">'
                        WHERE id = 102""".trimIndent(),
                )
                it.executeUpdate(
                    """UPDATE entries SET original_content =
                        '<style>bad{}</style><p>orig safe</p><a href="file:///etc/passwd" onclick="x()">link</a>'
                        WHERE id = 101""".trimIndent(),
                )
            }
        }
        val runId = stageFile(fixture)
        applyCompleted(runId, previewReady(runId).fingerprint)

        val a101 = articles.getValue(
            expectedArticleId("https://alpha.example/feed.xml", "guid-a1"),
        )
        // a101 rawDescription/originalContent derive from original_content.
        for (html in listOf(a101.rawDescription, a101.originalContent)) {
            assertNotNull(html)
            assertFalse(html!!.contains("<script"))
            assertFalse(html.contains("<style"))
            assertFalse(html.contains("onerror"))
            assertFalse(html.contains("onclick"))
            assertFalse(html.contains("javascript:"))
            assertFalse(html.contains("file:"))
            assertTrue(html.contains("<p>orig safe</p>"))
        }

        // a102: extracted=1 => fullContent derives from the dirty content.
        val a102 = articles.getValue(
            expectedArticleId("https://alpha.example/feed.xml", "guid-a2"),
        )
        val full = a102.fullContent
        assertNotNull(full)
        assertFalse(full!!.contains("<script"))
        assertFalse(full.contains("onerror"))
        assertFalse(full.contains("javascript:"))
        assertTrue(full.contains("<p>safe body</p>"))
    }

    @Test
    fun chunkFailureStaysRetryableAndResumeCompletes() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)
        val runId = stageFile(fixture)
        val preview = previewReady(runId)

        // Feed 2's chunk fails (injected constraint error -> tx rollback).
        failFeedInsertWithUrl = "https://beta.example/rss"
        val outcome = importer.apply(runId, preview.fingerprint, now)
        assertTrue(
            "expected Partial, got $outcome",
            outcome is FeedBucketImporter.ApplyOutcome.Partial,
        )
        val partial = outcome as FeedBucketImporter.ApplyOutcome.Partial
        assertEquals(2, partial.ledger.imported)
        assertEquals(1, partial.ledger.failed)
        assertEquals(ImportRun.STATE_RUNNING, runs.getValue(runId).state)
        // Source preserved while Retry exists: Done/leave must NOT purge.
        val retained = File(importerDir(), "run-$runId.db")
        assertTrue(retained.exists())
        importer.purgeOnTerminalLeave(runId)
        assertTrue("RUNNING run's source survives Done/leave", retained.exists())
        assertEquals(2, feeds.size)

        // Retry/resume: failed chunk now imports; earlier chunks skip-count.
        failFeedInsertWithUrl = null
        val resumed = importer.resume()
        assertTrue(
            "expected Completed, got $resumed",
            resumed is FeedBucketImporter.ApplyOutcome.Completed,
        )
        val resumeLedger = (resumed as FeedBucketImporter.ApplyOutcome.Completed).ledger
        assertEquals(1, resumeLedger.imported)
        assertEquals(2, resumeLedger.skipped)
        assertEquals(0, resumeLedger.failed)
        assertEquals(ImportRun.STATE_COMPLETED, runs.getValue(runId).state)
        assertEquals(3, feeds.size)
        assertEquals(7, articles.size)
    }

    @Test
    fun failedRunWithRetainedSourceResumesAfterProcessRecreation() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)
        val runId = stageFile(fixture)

        // Persist a FAILED run whose retained copy survived (e.g. process
        // kill between the failure-state write and any purge). Nothing
        // imported yet.
        runs[runId] = runs.getValue(runId).copy(
            state = ImportRun.STATE_FAILED,
            finishedAt = now,
        )

        // Fresh importer over the same persisted state = process recreation.
        val recreated = newImporter()
        assertEquals(runId, recreated.resumableRun()?.id)

        val resumed = recreated.resume()
        assertTrue(
            "expected Completed, got $resumed",
            resumed is FeedBucketImporter.ApplyOutcome.Completed,
        )
        assertEquals(3, feeds.size)
        assertEquals(7, articles.size)

        // Removal works after a resumed completion (runId provenance intact).
        val report = recreated.removeImported(runId)
        assertEquals(7 + 3 + 2 + 2, report.removed)
        assertEquals(0, report.skippedEdited + report.skippedProtected)
        assertEquals(0, feeds.size + articles.size)
    }

    @Test
    fun purgedFailedRunIsNotResumable() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)
        val runId = stageFile(fixture)
        runs[runId] = runs.getValue(runId).copy(
            state = ImportRun.STATE_FAILED,
            sourcePath = null,
            finishedAt = now,
        )

        val recreated = newImporter()
        assertNull(recreated.resumableRun())
        assertNull(recreated.resume())
    }

    @Test
    fun removeImportedIsOrderIndependentFeedFirst() = runBlocking {
        val fixture = File(tmp.root, "fixture.db")
        buildWebFixtureDb(fixture)
        val runId = stageFile(fixture)
        applyCompleted(runId, previewReady(runId).fingerprint)

        // Simulate a DAO returning FEED identities BEFORE their ARTICLE rows
        // (legal without ORDER BY; review round 1 finding 5): every feed
        // guard must still see the complete article deletion set.
        val feedFirst = identities.sortedWith(
            compareBy { if (it.targetKind == ImportIdentity.TARGET_FEED) 0 else 1 },
        )
        identities.clear()
        identities.addAll(feedFirst)
        assertEquals(ImportIdentity.TARGET_FEED, identities.first().targetKind)

        val report = importer.removeImported(runId)
        assertEquals(7 + 3 + 2 + 2, report.removed)
        assertEquals(0, report.skippedEdited)
        assertEquals(0, report.skippedProtected)
        assertEquals(0, feeds.size + articles.size)
        assertEquals(0, searches.size + rules.size)
    }

    companion object {
        /** Canonical fixture rows, mirroring scripts/make_test_feedbucket_db.py. */
        private val FIXTURE_DATA: String = """
            INSERT INTO users VALUES (1, 'synthetic', 'pbkdf2:fake01${"ab".repeat(32)}',
                '2026-01-01T00:00:00+00:00', 'fake-starred-token');
            INSERT INTO user_prefs(user_id, sort) VALUES (1, 'DESC');
            INSERT INTO subscriptions VALUES
                (1, 'https://alpha.example/feed.xml', 'https://alpha.example', 'Alpha Blog', 0, 1, NULL, NULL),
                (2, 'https://beta.example/rss', 'https://beta.example', 'Beta News', 1, 0, NULL, NULL),
                (3, 'https://gamma.example/atom.xml', '', 'Gamma Lab', 0, 1, NULL, NULL);
            INSERT INTO entries(id, feed_id, external_id, title, url, author, summary,
                content, original_content, extracted, published, created_at) VALUES
                (101, 1, 'guid-a1', 'Alpha One', 'https://alpha.example/one', 'Ann Author',
                 'Summary one', '<p>Full body one</p>', '<p>Original body one</p>', 0,
                 '2026-10-01T09:00:00+00:00', '2026-10-01T09:05:00+00:00'),
                (102, 1, 'guid-a2', 'Alpha Two', 'https://alpha.example/two', '',
                 'Summary two', '<p>Body two</p>', NULL, 1,
                 '2026-10-02T09:00:00+00:00', '2026-10-02T09:05:00+00:00'),
                (103, 1, 'guid-a3', 'Alpha Three', 'https://alpha.example/three', 'Ann Author',
                 'Summary three', '<p>Body three</p>', NULL, 0, NULL,
                 '2026-10-03T09:05:00+00:00'),
                (201, 2, 'guid-b1', 'Beta One', 'https://beta.example/one', 'Bob',
                 'Beta summary one', '<p>Beta body one</p>', NULL, 0,
                 '2026-10-01T12:00:00+00:00', '2026-10-01T12:05:00+00:00'),
                (202, 2, 'guid-b2', 'Beta Two', 'https://beta.example/two', '',
                 'Beta summary two', '<p>Beta body two</p>', '<p>Original beta two</p>', 1,
                 '2026-10-02T12:00:00+00:00', '2026-10-02T12:05:00+00:00'),
                (301, 3, 'guid-g1', 'Gamma One', 'https://gamma.example/one', '',
                 'Gamma summary one', '<p>Gamma body one</p>', NULL, 0,
                 '2026-10-01T15:00:00+00:00', '2026-10-01T15:05:00+00:00'),
                (302, 3, 'guid-g2', 'Gamma Two', 'https://gamma.example/two', 'Gus',
                 'Gamma summary two', '<p>Gamma body two</p>', NULL, 0,
                 '2026-10-02T15:00:00+00:00', '2026-10-02T15:05:00+00:00');
            INSERT INTO unread(entry_id) VALUES (101), (103), (201), (301);
            INSERT INTO starred(entry_id) VALUES (102), (202);
            INSERT INTO updated(entry_id, updated_at) VALUES (103, '2026-10-05T10:00:00+00:00');
            INSERT INTO recently_read(entry_id, read_at) VALUES
                (102, '2026-10-04T18:30:00+00:00'), (201, '2026-10-04T19:00:00+00:00');
            INSERT INTO tags VALUES (1, 'tech'), (2, 'science');
            INSERT INTO subscription_tags VALUES (1, 1), (1, 2);
            INSERT INTO saved_searches VALUES
                (1, 1, 'Unread tech', 'tag:tech', '2026-09-01T00:00:00+00:00'),
                (2, 1, 'Alpha mentions', 'alpha sort:desc', '2026-09-02T00:00:00+00:00');
            INSERT INTO actions VALUES
                (1, 'standard', 'Mark beta read', 'beta', 1, '', '', 'mark_read',
                 '2026-09-03T00:00:00+00:00'),
                (2, 'mute', 'Mute gamma', 'gamma', 0, '3', '', 'mark_read',
                 '2026-09-04T00:00:00+00:00');
        """.trimIndent()

        /** Web schema, mirroring scripts/make_test_feedbucket_db.py. */
        private val FIXTURE_SCHEMA: String = """
            CREATE TABLE users (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                username TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                password_created_at TEXT NOT NULL,
                starred_token TEXT
            );
            CREATE TABLE user_prefs (
                user_id INTEGER PRIMARY KEY,
                sort TEXT
            );
            CREATE TABLE subscriptions (
                id INTEGER PRIMARY KEY,
                feed_url TEXT NOT NULL UNIQUE,
                site_url TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                muted INTEGER NOT NULL DEFAULT 0,
                show_updates INTEGER NOT NULL DEFAULT 1,
                last_fetched TEXT,
                etag TEXT
            );
            CREATE TABLE entries (
                id INTEGER PRIMARY KEY,
                feed_id INTEGER NOT NULL,
                external_id TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL DEFAULT '',
                author TEXT NOT NULL DEFAULT '',
                summary TEXT NOT NULL DEFAULT '',
                content TEXT NOT NULL DEFAULT '',
                original_content TEXT,
                extracted INTEGER NOT NULL DEFAULT 0,
                published TEXT,
                created_at TEXT NOT NULL,
                UNIQUE(feed_id, external_id)
            );
            CREATE TABLE unread (entry_id INTEGER PRIMARY KEY);
            CREATE TABLE starred (entry_id INTEGER PRIMARY KEY);
            CREATE TABLE updated (entry_id INTEGER PRIMARY KEY, updated_at TEXT NOT NULL);
            CREATE TABLE recently_read (entry_id INTEGER PRIMARY KEY, read_at TEXT NOT NULL);
            CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
            CREATE TABLE subscription_tags (
                feed_id INTEGER NOT NULL, tag_id INTEGER NOT NULL,
                PRIMARY KEY(feed_id, tag_id)
            );
            CREATE TABLE saved_searches (
                id INTEGER PRIMARY KEY,
                user_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                query TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE TABLE actions (
                id INTEGER PRIMARY KEY,
                action_type TEXT NOT NULL DEFAULT 'standard',
                title TEXT NOT NULL DEFAULT '',
                query TEXT NOT NULL DEFAULT '',
                all_feeds INTEGER NOT NULL DEFAULT 1,
                feed_ids TEXT NOT NULL DEFAULT '',
                tag_ids TEXT NOT NULL DEFAULT '',
                effects TEXT NOT NULL DEFAULT 'mark_read',
                created_at TEXT NOT NULL
            );
        """.trimIndent()
    }
}

/**
 * JVM [FeedBucketImporter.SourceDb] over sqlite-jdbc: opens the retained
 * copy and logs every issued SQL string for the SecretGuard query-log
 * assertion. Read-only by construction (only SELECT/PRAGMA/sqlite_master).
 */
private class JdbcSourceDb(
    private val conn: Connection,
) : FeedBucketImporter.SourceDb {

    private val log = mutableListOf<String>()

    override val queriesIssued: List<String> get() = log.toList()

    override fun tableExists(table: String): Boolean {
        val sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?"
        log += sql
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    override fun tableColumns(table: String): List<String> {
        val sql = "PRAGMA table_info($table)"
        log += sql
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val columns = mutableListOf<String>()
                while (rs.next()) columns.add(rs.getString(2))
                return columns
            }
        }
    }

    override fun query(sql: String, args: List<String>): List<Map<String, String?>> {
        log += sql
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { index, arg -> ps.setString(index + 1, arg) }
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                val columns = (1..meta.columnCount).map { meta.getColumnName(it) }
                val rows = mutableListOf<Map<String, String?>>()
                while (rs.next()) {
                    rows.add(columns.associateWith { rs.getString(it) })
                }
                return rows
            }
        }
    }

    override fun close() {
        conn.close()
    }

    companion object {
        fun open(file: File): JdbcSourceDb =
            JdbcSourceDb(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"))
    }
}
