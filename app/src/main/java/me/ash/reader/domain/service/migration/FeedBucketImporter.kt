package me.ash.reader.domain.service.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleIdentity
import me.ash.reader.domain.model.download.DownloadHtmlSanitizer
import me.ash.reader.domain.model.extract.MedianBodyLength
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
import me.ash.reader.domain.repository.ImportDao
import me.ash.reader.domain.repository.SavedSearchDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.spacerDollar
import org.jsoup.Jsoup

/**
 * S32a — FeedBucket migration importer (D11 full-state migration).
 *
 * Pipeline (preferred input is the v1 export package from
 * `scripts/export_feedbucket_package.py`; raw user copies remain accepted):
 *   stage copy -> DURABLE filesDir/imports/<runId>.db (sourceUri recorded)
 *   -> fingerprint -> schema/package-version check -> secret guard
 *   -> no-writes preview (counts + fingerprint) -> apply RE-FINGERPRINTS and
 *   ABORTS on mismatch -> per-feed Room transaction -> identity map WITH
 *   `createdByRunId` + `stateStamp` -> counts ledger -> resume from the
 *   retained copy -> remove-imported deletes ONLY provenance-matched,
 *   unchanged rows (skipping+counting edited/protected records).
 *
 * Live web service untouched (D14): the importer opens ONLY user-picked
 * copies or tool-made packages; there is no network code here.
 *
 * Testability: the source DB is read through [SourceDb] (production opens
 * the retained copy read-only via android.database.sqlite; JVM tests
 * substitute a JDBC opener over synthetic fixtures) and every issued SQL
 * string is logged for the SecretGuard query-log assertion. Target writes go
 * through the existing DAO interfaces; the per-chunk Room transaction lives
 * on ImportDao.runInImportTransaction (StateApplyDao precedent).
 */
@Singleton
class FeedBucketImporter internal constructor(
    private val importsRoot: File,
    private val feedDao: FeedDao,
    private val groupDao: GroupDao,
    private val articleDao: ArticleDao,
    private val savedSearchDao: SavedSearchDao,
    private val actionRuleDao: ActionRuleDao,
    private val importDao: ImportDao,
    private val accountService: AccountService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        feedDao: FeedDao,
        groupDao: GroupDao,
        articleDao: ArticleDao,
        savedSearchDao: SavedSearchDao,
        actionRuleDao: ActionRuleDao,
        importDao: ImportDao,
        accountService: AccountService,
        @IODispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        importsRoot = File(context.filesDir, IMPORTS_DIR),
        feedDao = feedDao,
        groupDao = groupDao,
        articleDao = articleDao,
        savedSearchDao = savedSearchDao,
        actionRuleDao = actionRuleDao,
        importDao = importDao,
        accountService = accountService,
        ioDispatcher = ioDispatcher,
    )

    /** Source opener seam — swapped for JDBC in JVM tests. */
    internal var openSource: (File) -> SourceDb = { AndroidSourceDb.open(it) }

    // ------------------------------------------------------------------
    // Seam types
    // ------------------------------------------------------------------

    /** Read-only view over a FeedBucket source (package or raw copy). */
    interface SourceDb : Closeable {
        /** Every SQL string issued so far (SecretGuard query-log assertion). */
        val queriesIssued: List<String>

        fun tableExists(table: String): Boolean
        fun tableColumns(table: String): List<String>

        /** Runs a read-only query; returns rows keyed by column name. */
        fun query(sql: String, args: List<String> = emptyList()): List<Map<String, String?>>
    }

    sealed interface ApplyOutcome {
        data class Completed(val runId: Long, val ledger: ImportLedger) : ApplyOutcome

        /**
         * Bounded chunk failures: imported chunks stay, the failed ones are
         * itemized, the run remains RUNNING (retryable) and the retained
         * source is preserved while Retry exists (S32a steps 7/8;
         * UI-CONTRACT §6.10, §11.3; review round 1 finding 3).
         */
        data class Partial(val runId: Long, val ledger: ImportLedger) : ApplyOutcome

        data class Rejected(val reason: String) : ApplyOutcome
        data class SourceLost(val runId: Long) : ApplyOutcome

        /** Preview/apply consumed different bytes: nothing was written. */
        data class FingerprintMismatch(
            val previewFingerprint: String,
            val applyFingerprint: String,
        ) : ApplyOutcome
    }

    /** S32a step 7 removal report ("Removed N · skipped M (...)"). */
    data class RemovalReport(
        val removed: Int,
        val skippedEdited: Int,
        val skippedProtected: Int,
    ) {
        val skipped: Int get() = skippedEdited + skippedProtected
    }

    // ------------------------------------------------------------------
    // Stage 1 — durable retained copy
    // ------------------------------------------------------------------

    /**
     * Copy the picked bytes to `filesDir/imports/<runId>.db` and open the
     * run ledger. Returns the run id; the file fingerprint is recorded once
     * the copy lands.
     */
    suspend fun stageCopy(
        input: InputStream,
        sourceUri: String?,
        now: Long = System.currentTimeMillis(),
    ): Long = withContext(ioDispatcher) {
        importsRoot.mkdirs()
        val runId = importDao.upsertRun(
            ImportRun(
                kind = ImportRun.KIND_FEEDBUCKET,
                sourceFingerprint = "",
                sourceUri = sourceUri,
                sourcePath = null,
                state = ImportRun.STATE_RUNNING,
                startedAt = now,
            )
        )
        val staged = File(importsRoot, "run-$runId.db")
        input.use { src ->
            staged.outputStream().use { out -> src.copyTo(out) }
        }
        val fingerprint = sha256(staged)
        importDao.updateRunFingerprint(runId, fingerprint)
        importDao.updateRunSourcePath(runId, staged.absolutePath)
        runId
    }

    // ------------------------------------------------------------------
    // Stage 3 — no-writes preview
    // ------------------------------------------------------------------

    sealed interface PreviewOutcome {
        data class Ready(val runId: Long, val preview: ImportPreview) : PreviewOutcome
        data class Rejected(val runId: Long, val reason: String) : PreviewOutcome
        data class SourceLost(val runId: Long) : PreviewOutcome
    }

    suspend fun preview(runId: Long): PreviewOutcome = withContext(ioDispatcher) {
        val run = importDao.queryRun(runId) ?: return@withContext PreviewOutcome.SourceLost(runId)
        val file = run.sourcePath?.let(::File)
        if (file == null || !file.isFile) return@withContext PreviewOutcome.SourceLost(runId)

        openSource(file).use { source ->
            val kind = ImportSchemaChecker.classify(source)
            if (kind is ImportSchemaChecker.SourceKind.Rejected) {
                return@withContext rejectRun(runId, kind.reason)
            }
            val invalid = ImportSchemaChecker.validate(source)
            if (invalid != null) {
                return@withContext rejectRun(runId, invalid.reason)
            }
            // Secret guard, value level: scan BEFORE the user confirms the
            // preview so a token-bearing source dies here with zero writes.
            val secretHits = SecretGuard.scanSourceValues(source)
            if (secretHits.isNotEmpty()) {
                return@withContext rejectRun(runId, REASON_SECRET_VALUE)
            }
            val preview = ImportPreview.read(source, run.sourceFingerprint, kind)
            SecretGuard.assertQueriesSecretFree(source.queriesIssued)
            PreviewOutcome.Ready(runId, preview)
        }
    }

    // ------------------------------------------------------------------
    // Stage 4-6 — apply (resumable), ledger, resume
    // ------------------------------------------------------------------

    /** Apply (or resume) the run against its RETAINED copy. */
    suspend fun apply(
        runId: Long,
        previewFingerprint: String,
        now: Long = System.currentTimeMillis(),
        onProgress: ((feedTitle: String, ledger: ImportLedger) -> Unit)? = null,
    ): ApplyOutcome = withContext(ioDispatcher) {
        val run = importDao.queryRun(runId) ?: return@withContext ApplyOutcome.SourceLost(runId)
        val file = run.sourcePath?.let(::File)
        if (file == null || !file.isFile) {
            importDao.updateRunState(
                runId, ImportRun.STATE_FAILED, now,
                ImportLedger().plusFailed("retained copy missing").toJson(),
            )
            return@withContext ApplyOutcome.SourceLost(runId)
        }

        // Re-fingerprint: preview and apply must consume IDENTICAL bytes.
        val applyFingerprint = sha256(file)
        if (applyFingerprint != previewFingerprint) {
            importDao.updateRunState(
                runId, ImportRun.STATE_FAILED, now,
                ImportLedger().plusFailed(
                    "fingerprint mismatch: preview=$previewFingerprint apply=$applyFingerprint",
                ).toJson(),
            )
            return@withContext ApplyOutcome.FingerprintMismatch(previewFingerprint, applyFingerprint)
        }

        openSource(file).use { source ->
            val kind = ImportSchemaChecker.classify(source)
            if (kind is ImportSchemaChecker.SourceKind.Rejected) {
                return@withContext rejectRun(runId, kind.reason).let { ApplyOutcome.Rejected(kind.reason) }
            }
            val invalid = ImportSchemaChecker.validate(source)
            if (invalid != null) return@withContext ApplyOutcome.Rejected(invalid.reason)

            // Secret guard, value level (S32a step 2): scan the SOURCE before
            // ANY target write; on a hit nothing is written and the
            // secret-bearing retained copy is purged.
            val secretHits = SecretGuard.scanSourceValues(source)
            if (secretHits.isNotEmpty()) {
                return@withContext rejectRun(runId, REASON_SECRET_VALUE)
                    .let { ApplyOutcome.Rejected(REASON_SECRET_VALUE) }
            }

            val accountId = accountService.getCurrentAccountId()
            var ledger = ImportLedger()
            val seenSearches = mutableSetOf<String>()
            val seenRules = mutableSetOf<String>()

            // Feeds in subscriptions.id order; identity map first => resume
            // naturally skips already-imported feeds (S32a step 6).
            val feedRows = source.query(
                """
                SELECT s.id, s.feed_url, s.site_url, s.title, s.muted, s.show_updates,
                    (SELECT GROUP_CONCAT(t.name) FROM subscription_tags st
                        JOIN tags t ON t.id = st.tag_id WHERE st.feed_id = s.id) AS tag_names
                FROM subscriptions s ORDER BY s.id
                """.trimIndent(),
            )
            for (feedRow in feedRows) {
                val sourceId = feedSourceId(feedRow.getValue("id"))
                if (importDao.queryIdentity(
                        ImportSchemaChecker.SOURCE_FEEDBUCKET, sourceId,
                        ImportIdentity.TARGET_FEED,
                    ) != null
                ) {
                    ledger = ledger.plusSkipped()
                    onProgress?.invoke(feedRow["title"].orEmpty(), ledger)
                    continue
                }
                ledger = try {
                    applyFeedChunk(source, feedRow, runId, accountId, now, ledger)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Schema/constraint error: the chunk rolled back (single
                    // Room transaction); bounded + ledgered, import continues.
                    ledger.plusFailed(
                        "feed ${feedRow["id"]} (${feedRow["title"]}): ${e.message ?: e.javaClass.simpleName}",
                    )
                }
                onProgress?.invoke(feedRow["title"].orEmpty(), ledger)
            }

            // Saved searches + action rules (idempotent via identity map).
            ledger = applySavedSearches(source, runId, ledger, seenSearches)
            ledger = applyActionRules(source, runId, accountId, ledger, seenRules)

            SecretGuard.assertQueriesSecretFree(source.queriesIssued)
            if (ledger.failed > 0) {
                // Bounded failures: stay RUNNING + keep the retained source so
                // Retry can resume the failed chunks (nothing is finalized
                // Completed while failure items exist; review round 1
                // findings 3/4).
                importDao.updateRunState(runId, ImportRun.STATE_RUNNING, now, ledger.toJson())
                ApplyOutcome.Partial(runId, ledger)
            } else {
                importDao.updateRunState(runId, ImportRun.STATE_COMPLETED, now, ledger.toJson())
                ApplyOutcome.Completed(runId, ledger)
            }
        }
    }

    /**
     * S32a step 6 — resume the latest retryable run against its retained
     * copy: state RUNNING, or FAILED with the retained copy still on disk
     * (recovery after fingerprint mismatch / apply-time rejection; review
     * round 1 finding 4). Uses the run's RECORDED fingerprint, which matches
     * the retained bytes, so a resumed apply is self-consistent.
     */
    suspend fun resume(
        now: Long = System.currentTimeMillis(),
        onProgress: ((feedTitle: String, ledger: ImportLedger) -> Unit)? = null,
    ): ApplyOutcome? {
        val run = importDao.queryResumableRun() ?: return null
        return apply(run.id, run.sourceFingerprint, now, onProgress)
    }

    /** Latest retryable run (resumable indicator; S32a steps 6/8). */
    suspend fun resumableRun(): ImportRun? = importDao.queryResumableRun()

    suspend fun latestRun(): ImportRun? = importDao.queryLatestRun(ImportRun.KIND_FEEDBUCKET)

    // ------------------------------------------------------------------
    // Stage 7 — remove imported data (provenance-guarded)
    // ------------------------------------------------------------------

    /**
     * S32a step 7 — remove imported data, provenance-guarded. DETERMINISTIC
     * TWO-PHASE (review round 1 finding 5): phase 1 collects article
     * deletions (validating stateStamps) and deletes searches/rules; phase 2
     * evaluates EVERY feed cascade guard against the COMPLETE article
     * deletion set; phase 3 deletes the articles. Result-order independent:
     * feed-first DAO order cannot strand a feed whose articles were already
     * deleted or wrongly protect it before its own rows were considered.
     */
    suspend fun removeImported(runId: Long): RemovalReport = withContext(ioDispatcher) {
        val created = importDao.queryCreatedIdentitiesByRun(runId)
        var removed = 0
        var skippedEdited = 0
        var skippedProtected = 0
        // Article ids this removal will delete (for the feed cascade walk).
        val articlesToDelete = mutableSetOf<String>()
        val feedIdentities = mutableListOf<ImportIdentity>()

        importDao.runInImportTransaction {
            // Phase 1: non-feed rows + collect feed identities.
            for (identity in created) {
                when (identity.targetKind) {
                    ImportIdentity.TARGET_ARTICLE -> {
                        val article = articleDao.queryById(identity.targetId)?.article
                        if (article == null || article.stateVersion.toString() != identity.stateStamp) {
                            skippedEdited += 1
                        } else {
                            articlesToDelete.add(identity.targetId)
                        }
                    }
                    ImportIdentity.TARGET_SAVED_SEARCH -> {
                        val current = savedSearchDao.queryById(identity.targetId.toLong())
                        if (current == null || savedSearchStamp(current) != identity.stateStamp) {
                            skippedEdited += 1
                        } else {
                            savedSearchDao.deleteById(current.id)
                            removed += 1
                        }
                    }
                    ImportIdentity.TARGET_ACTION_RULE -> {
                        val current = actionRuleDao.queryById(identity.targetId.toLong())
                        if (current == null || actionRuleStamp(current) != identity.stateStamp) {
                            skippedEdited += 1
                        } else {
                            actionRuleDao.deleteById(current.id)
                            removed += 1
                        }
                    }
                    ImportIdentity.TARGET_FEED -> feedIdentities.add(identity)
                }
            }
            // Phase 2: feed guards with the COMPLETE articlesToDelete set.
            for (identity in feedIdentities) {
                val current = feedDao.queryById(identity.targetId)
                if (current == null || feedStamp(current) != identity.stateStamp) {
                    skippedEdited += 1
                } else {
                    // Cascade guard: the feed may only go if no retained
                    // article (pre-existing / other-run / edited-since-import)
                    // would be FK-cascaded.
                    val retained = articleDao.queryIdsByFeed(current.id) - articlesToDelete
                    if (retained.isNotEmpty()) {
                        skippedProtected += 1
                    } else {
                        feedDao.deleteById(current.id)
                        removed += 1
                    }
                }
            }
            // Phase 3: articles leave only after every feed guard evaluated.
            for (articleId in articlesToDelete) {
                articleDao.deleteById(articleId)
                removed += 1
            }
            importDao.deleteIdentitiesByRun(runId)
        }
        RemovalReport(removed, skippedEdited, skippedProtected)
    }

    // ------------------------------------------------------------------
    // Stage 8 — source retention + orphan GC
    // ------------------------------------------------------------------

    /** Cancel before apply: nothing written, retained copy purged. */
    suspend fun cancelBeforeApply(runId: Long, now: Long = System.currentTimeMillis()) {
        purgeRetainedCopy(runId)
        importDao.updateRunState(runId, ImportRun.STATE_CANCELLED, now, ImportLedger().toJson())
    }

    /** Completed runs purge the retained copy when the user leaves. */
    suspend fun purgeOnTerminalLeave(runId: Long) {
        val run = importDao.queryRun(runId) ?: return
        if (run.state == ImportRun.STATE_COMPLETED || run.state == ImportRun.STATE_CANCELLED) {
            purgeRetainedCopy(runId)
        }
    }

    private suspend fun purgeRetainedCopy(runId: Long) {
        val run = importDao.queryRun(runId) ?: return
        run.sourcePath?.let { File(it).delete() }
        importDao.updateRunSourcePath(runId, null)
    }

    /** Orphan GC: copies older than 7 days whose run is not 'running'. */
    suspend fun gcOrphanCopies(
        now: Long = System.currentTimeMillis(),
        retentionMillis: Long = RETENTION_MILLIS,
    ): Int = withContext(ioDispatcher) {
        val files = importsRoot.listFiles { f -> f.isFile && f.name.endsWith(".db") }
            ?: return@withContext 0
        var removed = 0
        for (file in files) {
            if (now - file.lastModified() < retentionMillis) continue
            val run = importDao.queryRunBySourcePath(file.absolutePath)
            if (run?.state == ImportRun.STATE_RUNNING) continue
            if (file.delete()) {
                removed += 1
                run?.let { importDao.updateRunSourcePath(it.id, null) }
            }
        }
        removed
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun rejectRun(runId: Long, reason: String): PreviewOutcome.Rejected {
        importDao.updateRunState(
            runId, ImportRun.STATE_FAILED, System.currentTimeMillis(),
            ImportLedger().plusFailed(reason).toJson(),
        )
        purgeRetainedCopy(runId)
        return PreviewOutcome.Rejected(runId, reason)
    }

    /** One feed = ONE Room transaction (S32a steps 4/5). */
    private suspend fun applyFeedChunk(
        source: SourceDb,
        feedRow: Map<String, String?>,
        runId: Long,
        accountId: Int,
        now: Long,
        ledger: ImportLedger,
    ): ImportLedger {
        var current = ledger
        val entryFailures = mutableListOf<String>()
        importDao.runInImportTransaction {
            val feedUrl = feedRow.getValue("feed_url")!!
            val feedId = feedId(accountId, feedUrl)
            val rawTagNames = feedRow["tag_names"].orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val tagCsv = rawTagNames
                .map { it.lowercase() }.distinct().joinToString(",")

            // Groups from tags: first tag -> display group (S32a step 4).
            var groupId = accountId.getDefaultGroupId()
            rawTagNames.firstOrNull()?.let { firstTag ->
                groupId = groupIdFor(accountId, firstTag)
                if (groupDao.queryById(groupId) == null) {
                    groupDao.insertAll(listOf(Group(groupId, firstTag, accountId)))
                }
            }

            val feed = Feed(
                id = feedId,
                name = feedRow.getValue("title").orEmpty(),
                url = feedUrl,
                groupId = groupId,
                accountId = accountId,
                siteUrl = feedRow.getValue("site_url").orEmpty(),
                muted = feedRow.getValue("muted") == "1",
                showUpdates = feedRow.getValue("show_updates") != "0",
                tags = tagCsv,
            )
            feedDao.insertAll(listOf(feed))

            val entryRows = source.query(
                """
                SELECT e.id, e.external_id, e.title, e.url, e.author, e.summary,
                    e.content, e.original_content, e.extracted, e.published,
                    e.created_at,
                    (SELECT 1 FROM unread u WHERE u.entry_id = e.id) AS is_unread,
                    (SELECT 1 FROM starred st WHERE st.entry_id = e.id) AS is_starred,
                    (SELECT updated_at FROM updated up WHERE up.entry_id = e.id) AS updated_at,
                    (SELECT read_at FROM recently_read rr WHERE rr.entry_id = e.id) AS read_at
                FROM entries e WHERE e.feed_id = ? ORDER BY e.id
                """.trimIndent(),
                listOf(feedRow.getValue("id")!!),
            )
            val articles = mutableListOf<Article>()
            val identities = mutableListOf<ImportIdentity>()
            for (row in entryRows) {
                try {
                    val article = buildArticle(row, feed, accountId, now)
                    articles.add(article)
                    identities.add(
                        ImportIdentity(
                            source = ImportSchemaChecker.SOURCE_FEEDBUCKET,
                            sourceId = entrySourceId(row.getValue("id")),
                            targetKind = ImportIdentity.TARGET_ARTICLE,
                            targetId = article.id,
                            importRunId = runId,
                            importedAt = now,
                            createdByRunId = runId,
                            stateStamp = article.stateVersion.toString(),
                        ),
                    )
                } catch (e: Exception) {
                    // Row-level failure: bounded, itemized, chunk survives.
                    entryFailures.add("entry ${row["id"]}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            if (articles.isNotEmpty()) {
                articleDao.insertList(articles)
                importDao.insertIdentities(identities)
                // E7/E24 — per-feed median recompute at the end of the
                // per-feed apply, sampled from the imported rows (single
                // implementation; samples are the stored rawDescription
                // values of the articles just inserted).
                feedDao.updateMedianBodyLength(
                    feedId,
                    MedianBodyLength.recompute(articles.map { it.rawDescription }),
                )
            }
            importDao.insertIdentities(
                listOf(
                    ImportIdentity(
                        source = ImportSchemaChecker.SOURCE_FEEDBUCKET,
                        sourceId = feedSourceId(feedRow.getValue("id")),
                        targetKind = ImportIdentity.TARGET_FEED,
                        targetId = feedId,
                        importRunId = runId,
                        importedAt = now,
                        createdByRunId = runId,
                        stateStamp = feedStamp(feed),
                    ),
                ),
            )
        }
        current = current.plusImported()
        entryFailures.forEach { current = current.plusFailed(it) }
        return current
    }

    /**
     * Entry mapping (S32a step 4). Documented deviation, pinned by the
     * fixture test: `rawDescription = original_content ?: content-as-body
     * ?: summary`; `fullContent`+`extractedAt` come from `content` only when
     * `extracted = 1`; `*Lower` shadows are recomputed by ArticleDao.insert.
     */
    private fun buildArticle(
        row: Map<String, String?>,
        feed: Feed,
        accountId: Int,
        now: Long,
    ): Article {
        val content = row.getValue("content").orEmpty()
        val original = row.getValue("original_content")
        val rawSource = original ?: content.ifBlank { row.getValue("summary").orEmpty() }
        // S30a/D16 parity with downloaded content: source HTML is sanitized
        // at WRITE time (the reader WebView enables JavaScript). script/style
        // elements, event handlers and javascript:/data:/file: URIs never
        // reach the target rows (review round 1 finding 2).
        val raw = DownloadHtmlSanitizer.sanitize(rawSource)
        val extracted = row.getValue("extracted") == "1"
        val externalId = row.getValue("external_id").orEmpty()
        val published = parseDate(row.getValue("published"))
        return Article(
            id = ArticleIdentity.articleId(accountId, feed.url, externalId),
            date = published ?: parseDate(row.getValue("created_at")) ?: Date(now),
            title = row.getValue("title").orEmpty(),
            author = row.getValue("author")?.takeIf { it.isNotBlank() },
            rawDescription = raw,
            shortDescription = Jsoup.parse(raw).text().take(SHORT_DESCRIPTION_MAX),
            fullContent = if (extracted) {
                DownloadHtmlSanitizer.sanitize(content).takeIf { it.isNotBlank() }
            } else {
                null
            },
            link = row.getValue("url").orEmpty(),
            feedId = feed.id,
            accountId = accountId,
            isUnread = row["is_unread"] != null,
            isStarred = row["is_starred"] != null,
            guid = externalId,
            originalContent = original?.let { DownloadHtmlSanitizer.sanitize(it) },
            isUpdated = row["updated_at"] != null,
            contentChangedAt = parseDate(row["updated_at"]),
            readAt = parseDate(row["read_at"]),
            extractedAt = if (extracted) Date(now) else null,
            publishedAt = published,
        )
    }

    private suspend fun applySavedSearches(
        source: SourceDb,
        runId: Long,
        ledger: ImportLedger,
        seen: MutableSet<String>,
    ): ImportLedger {
        var current = ledger
        val rows = source.query(
            "SELECT id, name, query, created_at FROM saved_searches ORDER BY id",
        )
        for (row in rows) {
            val sourceId = "saved_search:${row.getValue("id")}"
            if (!seen.add(sourceId)) continue
            if (importDao.queryIdentity(
                    ImportSchemaChecker.SOURCE_FEEDBUCKET, sourceId,
                    ImportIdentity.TARGET_SAVED_SEARCH,
                ) != null
            ) {
                continue
            }
            val savedSearch = SavedSearch(
                name = row.getValue("name").orEmpty(),
                query = row.getValue("query").orEmpty(),
                createdAt = parseDate(row.getValue("created_at"))?.time ?: 0L,
            )
            importDao.runInImportTransaction {
                val id = savedSearchDao.insert(savedSearch)
                importDao.insertIdentities(
                    listOf(
                        ImportIdentity(
                            source = ImportSchemaChecker.SOURCE_FEEDBUCKET,
                            sourceId = sourceId,
                            targetKind = ImportIdentity.TARGET_SAVED_SEARCH,
                            targetId = id.toString(),
                            importRunId = runId,
                            importedAt = System.currentTimeMillis(),
                            createdByRunId = runId,
                            stateStamp = savedSearchStamp(savedSearch),
                        ),
                    ),
                )
            }
            current = current.copy(searchesImported = current.searchesImported + 1)
        }
        return current
    }

    private suspend fun applyActionRules(
        source: SourceDb,
        runId: Long,
        accountId: Int,
        ledger: ImportLedger,
        seen: MutableSet<String>,
    ): ImportLedger {
        var current = ledger
        val tagNames = source.query("SELECT id, name FROM tags")
            .associate { it.getValue("id")!! to it.getValue("name").orEmpty() }
        val rows = source.query(
            """
            SELECT id, action_type, title, query, all_feeds, feed_ids, tag_ids,
                effects, created_at
            FROM actions ORDER BY id
            """.trimIndent(),
        )
        for (row in rows) {
            val sourceId = "action:${row.getValue("id")}"
            if (!seen.add(sourceId)) continue
            if (importDao.queryIdentity(
                    ImportSchemaChecker.SOURCE_FEEDBUCKET, sourceId,
                    ImportIdentity.TARGET_ACTION_RULE,
                ) != null
            ) {
                continue
            }
            // Web feed_ids resolve through the identity map; web tag_ids
            // resolve against imported tag names -> Android group ids.
            val feedIds = row.getValue("feed_ids").orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { webId ->
                    importDao.queryIdentity(
                        ImportSchemaChecker.SOURCE_FEEDBUCKET, feedSourceId(webId),
                        ImportIdentity.TARGET_FEED,
                    )?.targetId
                }
            val groupIds = row.getValue("tag_ids").orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { tagId -> tagNames[tagId] }
                .map { tagName -> groupIdFor(accountId, tagName) }
                .filter { groupDao.queryById(it) != null }
            val effects = row.getValue("effects").orEmpty()
                .split(',').map { it.trim() }
                .filter {
                    it == ActionRule.EFFECT_MARK_READ || it == ActionRule.EFFECT_STAR
                }
                .distinct()
            val rule = ActionRule(
                type = row.getValue("action_type")
                    ?.takeIf { it == ActionRule.TYPE_MUTE }
                    ?: ActionRule.TYPE_STANDARD,
                title = row.getValue("title").orEmpty(),
                query = row.getValue("query").orEmpty(),
                allFeeds = row.getValue("all_feeds") != "0",
                feedIds = feedIds.joinToString(","),
                groupIds = groupIds.joinToString(","),
                effects = effects.joinToString(",")
                    .ifEmpty { ActionRule.EFFECT_MARK_READ },
                createdAt = parseDate(row.getValue("created_at"))?.time ?: 0L,
            )
            importDao.runInImportTransaction {
                val id = actionRuleDao.insert(rule)
                importDao.insertIdentities(
                    listOf(
                        ImportIdentity(
                            source = ImportSchemaChecker.SOURCE_FEEDBUCKET,
                            sourceId = sourceId,
                            targetKind = ImportIdentity.TARGET_ACTION_RULE,
                            targetId = id.toString(),
                            importRunId = runId,
                            importedAt = System.currentTimeMillis(),
                            createdByRunId = runId,
                            stateStamp = actionRuleStamp(rule),
                        ),
                    ),
                )
            }
            current = current.copy(rulesImported = current.rulesImported + 1)
        }
        return current
    }

    // ------------------------------------------------------------------
    // Stamps + ids (S32a step 4 identity map contract)
    // ------------------------------------------------------------------

    private fun feedStamp(feed: Feed): String = sha256(
        listOf(feed.name, feed.siteUrl, feed.tags, feed.muted, feed.showUpdates)
            .joinToString("\n"),
    )

    private fun savedSearchStamp(savedSearch: SavedSearch): String = sha256(
        "${savedSearch.name}\n${savedSearch.query}",
    )

    private fun actionRuleStamp(rule: ActionRule): String = sha256(
        listOf(
            rule.type, rule.title, rule.query, rule.allFeeds, rule.feedIds,
            rule.groupIds, rule.effects,
        ).joinToString("\n"),
    )

    /** S32a step 4: deterministic feed id = "<accountId>$" + sha256("feed\n"+url). */
    private fun feedId(accountId: Int, feedUrl: String): String =
        accountId.spacerDollar(sha256("feed\n$feedUrl"))

    /** Deterministic group id for a tag-derived display group. */
    private fun groupIdFor(accountId: Int, tagName: String): String =
        accountId.spacerDollar(sha256("group\n${tagName.lowercase()}"))

    private fun feedSourceId(webId: String?): String = "feed:$webId"
    private fun entrySourceId(webId: String?): String = "entry:$webId"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Web ISO-8601 timestamps (`published` nullable; `created_at` NOT NULL). */
    private fun parseDate(value: String?): Date? {
        if (value.isNullOrBlank()) return null
        return runCatching { Date.from(Instant.parse(value)) }.getOrElse {
            runCatching { Date.from(OffsetDateTime.parse(value).toInstant()) }.getOrElse {
                runCatching {
                    Date.from(LocalDateTime.parse(value).toInstant(ZoneOffset.UTC))
                }.getOrNull()
            }
        }
    }

    companion object {
        const val IMPORTS_DIR = "imports"
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val REASON_SECRET_VALUE =
            "Secret-like token value detected in the source. Nothing was imported."
        private const val SHORT_DESCRIPTION_MAX = 280
    }
}

/**
 * Production [FeedBucketImporter.SourceDb]: opens the retained copy
 * READ-ONLY via android.database.sqlite (SQLiteDatabase.OPEN_READONLY) and
 * logs every issued SQL string for the SecretGuard query-log assertion.
 */
private class AndroidSourceDb(
    private val db: SQLiteDatabase,
) : FeedBucketImporter.SourceDb {

    private val log = mutableListOf<String>()

    override val queriesIssued: List<String> get() = log.toList()

    override fun tableExists(table: String): Boolean {
        val sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?"
        log += sql
        return db.rawQuery(sql, arrayOf(table)).use { it.moveToFirst() }
    }

    override fun tableColumns(table: String): List<String> {
        val sql = "PRAGMA table_info($table)"
        log += sql
        return db.rawQuery(sql, null).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toList()
        }
    }

    override fun query(
        sql: String,
        args: List<String>,
    ): List<Map<String, String?>> {
        log += sql
        return db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            val columns = Array(cursor.columnCount) { cursor.getColumnName(it) }
            val rows = mutableListOf<Map<String, String?>>()
            while (cursor.moveToNext()) {
                rows.add(
                    columns.associateWith { index ->
                        cursor.getString(cursor.getColumnIndexOrThrow(index))
                    },
                )
            }
            rows
        }
    }

    override fun close() {
        db.close()
    }

    companion object {
        fun open(file: File): FeedBucketImporter.SourceDb =
            AndroidSourceDb(SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY))
    }
}
