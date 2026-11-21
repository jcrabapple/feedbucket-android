package me.ash.reader.domain.data

import android.content.Context
import androidx.annotation.Keep
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

private const val TAG = "DiffMapHolder"
private const val CACHE_FILE_NAME = "diff_map.json"
private const val CACHE_TMP_FILE_NAME = "diff_map.json.tmp"

@OptIn(FlowPreview::class)
class DiffMapHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val accountService: AccountService,
    private val rssService: RssService,
) {
    val diffMap = mutableStateMapOf<String, Diff>()

    private val pendingSyncDiffs = mutableStateMapOf<String, Diff>()
    private val syncedDiffs = mutableMapOf<String, Diff>()

    val diffMapSnapshotFlow = snapshotFlow { diffMap.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    private val pendingSyncDiffsSnapshotFlow = snapshotFlow { pendingSyncDiffs.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    val shouldSyncWithRemote get() = currentAccount?.type != AccountType.Local

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Diff>>() {}.type

    /**
     * Serializes every read, write, and delete of the diff cache file for
     * this holder so a load, a debounced persist, and a commit reconcile can
     * never interleave on `diff_map.json`.
     */
    private val cacheIoMutex = Mutex()

    private val cacheDir = context.cacheDir.resolve("diff")

    @Volatile
    private var userCacheDir = cacheDir

    private var currentAccount: Account? = null

    var dbJob: Job? = null
    var remoteJob: Job? = null

    init {
        applicationScope.launch {
            accountService.currentAccountFlow.mapNotNull { it }.collect { account ->
                val previousAccount = currentAccount
                if (previousAccount != null && previousAccount != account) {
                    cleanup(previousAccount)
                }
                currentAccount = account
                init(account)
            }
        }
    }

    private fun init(account: Account) {
        userCacheDir = cacheDir.resolve(account.id.toString())
        commitDiffsFromCache()
        commitOnChange()
        if (account.type != AccountType.Local) {
            syncOnChange()
        }
    }

    private fun cleanup(account: Account) {
        dbJob?.cancel()
        remoteJob?.cancel()
        // Snapshot the pending diffs and the outgoing account's directory
        // synchronously, before init() repoints userCacheDir at the next
        // account and diffMap.clear() empties the live map.
        writeDiffsToCache(diffMap.toMap(), userCacheDir)
        diffMap.clear()
        pendingSyncDiffs.clear()
        syncedDiffs.clear()
    }

    private fun commitOnChange() {
        dbJob = applicationScope.launch(ioDispatcher) {
            diffMapSnapshotFlow.debounce(2_000).collect {
                if (it.isNotEmpty()) {
                    writeDiffsToCache(it)
                }
            }
        }
    }

    private fun syncOnChange() {
        remoteJob = applicationScope.launch(ioDispatcher) {
            pendingSyncDiffsSnapshotFlow.debounce(2_000).collect {
                withContext(ioDispatcher) {
                    syncDiffsWithRemote(it)
                }
            }
        }
    }

    fun checkIfUnread(articleWithFeed: ArticleWithFeed): Boolean {
        return diffMap[articleWithFeed.article.id]?.isUnread ?: articleWithFeed.article.isUnread
    }

    /**
     * Updates the diff map with changes to an article's read/unread status.
     *
     * This function manages a map (`diffMap`) that tracks pending changes (diffs) to the
     * read/unread status of articles. These changes are not immediately applied to the
     * underlying data store but are held in `diffMap` until a later commit operation.
     *
     * The function supports three modes of updating:
     *
     * 1. **Toggle:** If `isUnread` is `null`, the function toggles the current read/unread
     *    status of the article.  If the article is currently unread, it will be marked as read,
     *    and vice-versa.
     * 2. **Mark as Unread:** If `isUnread` is `true`, the article will be marked as unread,
     *    regardless of its current status.
     * 3. **Mark as Read:** If `isUnread` is `false`, the article will be marked as read,
     *    regardless of its current status.
     *
     * The function determines if a change needs to be tracked based on the current status and desired status:
     *  - If the requested change matches the article's current status, the diff is removed from the map, if it exists. (No change is needed.)
     *  - Otherwise, the diff is added to or updated in the map.
     *
     * @param articleWithFeed The article and its associated feed data. This is used to identify the article
     *                        and access its current read/unread state.
     * @param isUnread An optional boolean indicating the desired read/unread status of the article.
     *                 - `null`: Toggles the current read/unread status.
     *                 - `true`: Marks the article as unread.
     *                 - `false`: Marks the article as read.
     *
     * @return A [Diff] object representing the changes made to the article.
     *
     * @see Diff
     */
    private fun updateDiffInternal(
        articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null
    ): Diff? {
        val articleId = articleWithFeed.article.id

        val diff = diffMap[articleId]

        if (diff == null) {
            val isUnread = isUnread ?: !articleWithFeed.article.isUnread
            val diff = Diff(
                isUnread = isUnread, articleWithFeed = articleWithFeed
            )
            diffMap[articleId] = diff
            return diff
        } else {
            if (isUnread == null || diff.isUnread != isUnread) {
                val diff = diffMap.remove(articleId)
                return diff?.copy(isUnread = !diff.isUnread)
            }
        }
        return null
    }

    fun updateDiff(
        vararg articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null
    ) {
        val appliedDiffs = articleWithFeed.mapNotNull {
            updateDiffInternal(it, isUnread)
        }
        if (shouldSyncWithRemote) {
            appliedDiffs.forEach {
                appendDiffToSync(it)
            }
        }
    }

    private fun appendDiffToSync(diff: Diff) {
        val syncedDiff = syncedDiffs[diff.articleId]
        if (syncedDiff == null || syncedDiff.isUnread != diff.isUnread) {
            pendingSyncDiffs[diff.articleId] = diff
        }
    }

    fun commitDiffsToDb() {
        applicationScope.launch(ioDispatcher) {
            commitDiffsToDbSuspend()
        }
    }

    /**
     * Commits the CURRENT diff snapshot to the database and reconciles the
     * cache file under [cacheIoMutex]. Only the committed keys are removed
     * from [diffMap], so diffs recorded while the batch runs survive and are
     * persisted back by the reconcile write instead of being cleared by an
     * uncoordinated delete.
     */
    private suspend fun commitDiffsToDbSuspend() {
        val snapshot = diffMap.toMap()
        snapshot.keys.forEach { diffMap.remove(it) }
        cacheIoMutex.withLock {
            val remainder = diffMap.toMap()
            if (remainder.isEmpty()) {
                deleteCacheFilesLocked()
            } else {
                writeDiffsAtomicLocked(remainder, userCacheDir)
            }
        }
        if (snapshot.isEmpty()) return
        val markAsReadArticles = snapshot.filter { !it.value.isUnread }.map { it.key }.toSet()
        val markAsUnreadArticles = snapshot.filter { it.value.isUnread }.map { it.key }.toSet()
        rssService.get().batchMarkAsRead(articleIds = markAsReadArticles, isUnread = false)
        rssService.get().batchMarkAsRead(articleIds = markAsUnreadArticles, isUnread = true)
    }

    /**
     * Atomically persists an immutable snapshot of the diff map: the JSON is
     * written to a sibling temp file, flushed and synced, then moved over the
     * cache file, so a concurrent reader (or process death mid-write) can
     * never observe a half-written `diff_map.json`. All file I/O runs under
     * [cacheIoMutex]; [targetDir] is captured by the caller so an account
     * switch cannot redirect a pending write. Internal visibility is the
     * seam for JVM atomic-write coverage.
     */
    internal fun writeDiffsToCache(
        snapshot: Map<String, Diff>,
        targetDir: File = userCacheDir,
    ): Job = applicationScope.launch(ioDispatcher) {
        cacheIoMutex.withLock {
            writeDiffsAtomicLocked(snapshot, targetDir)
        }
    }

    /** Caller must hold [cacheIoMutex]. */
    private fun writeDiffsAtomicLocked(snapshot: Map<String, Diff>, targetDir: File) {
        val targetFile = targetDir.resolve(CACHE_FILE_NAME)
        val tmpFile = targetDir.resolve(CACHE_TMP_FILE_NAME)
        try {
            targetDir.mkdirs()
            val json = gson.toJson(snapshot)
            FileOutputStream(tmpFile).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            moveTempOverTarget(tmpFile, targetFile)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "failed to write diff cache; discarding temp file")
            deleteQuietly(tmpFile)
        }
    }

    private fun moveTempOverTarget(tmpFile: File, targetFile: File) {
        try {
            Files.move(
                tmpFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            return
        } catch (expected: AtomicMoveNotSupportedException) {
            Timber.tag(TAG).w(expected, "atomic move unsupported; falling back")
        } catch (expected: UnsupportedOperationException) {
            Timber.tag(TAG).w(expected, "atomic move unsupported; falling back")
        }
        try {
            Files.move(tmpFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (expected: IOException) {
            Timber.tag(TAG).w(expected, "nio move failed; falling back to renameTo")
        }
        if (!tmpFile.renameTo(targetFile)) {
            throw IOException("failed to move ${tmpFile.name} over ${targetFile.name}")
        }
    }

    private suspend fun syncDiffsWithRemote(diffs: Map<String, Diff>) {
        if (!shouldSyncWithRemote) return
        if (diffs.isEmpty()) return
        val toBeSync = diffs
        val markAsReadArticles =
            toBeSync.filter { !it.value.isUnread }.map { it.key }.toSet()
        val markAsUnreadArticles =
            toBeSync.filter { it.value.isUnread }.map { it.key }.toSet()

        val rssService = rssService.get()

        val synced = supervisorScope {
            val read = async {
                rssService.syncReadStatus(
                    articleIds = markAsReadArticles,
                    isUnread = false
                )
            }
            val unread = async {
                rssService.syncReadStatus(
                    articleIds = markAsUnreadArticles,
                    isUnread = true
                )
            }
            runCatching { read.await() }.getOrElse { emptySet() } +
                    runCatching { unread.await() }.getOrElse { emptySet() }
        }

        pendingSyncDiffs -= synced
        syncedDiffs += diffs.filter { synced.contains(it.key) }
    }

    /**
     * Loads persisted diffs for the current account, then commits them. The
     * commit now runs INSIDE the load coroutine (it was an
     * invokeOnCompletion callback): a cancelled load no longer fires a
     * commit, an empty or corrupt load makes the commit a cheap no-op, and
     * the valid-cache semantics (load, then commit to db) are preserved.
     */
    private fun commitDiffsFromCache() {
        applicationScope.launch(ioDispatcher) {
            val loaded = cacheIoMutex.withLock { readDiffsFromCacheLocked() }
            loaded?.let {
                diffMap.clear()
                diffMap.putAll(it)
            }
            commitDiffsToDbSuspend()
        }
    }

    /**
     * Reads and validates the cache file for [userCacheDir]. Returns null
     * when nothing usable is on disk (missing, unreadable, empty, or corrupt
     * file); a corrupt or empty file is DELETED here so it can never crash a
     * later load. Otherwise returns the validated entries — malformed
     * records Gson can materialize for damaged JSON (null values, missing
     * ids) are dropped, and the file is rewritten without them.
     * Caller must hold [cacheIoMutex].
     */
    private fun readDiffsFromCacheLocked(): Map<String, Diff>? {
        val dir = userCacheDir
        val file = dir.resolve(CACHE_FILE_NAME)
        if (!file.exists() || !file.canRead()) return null
        val json = try {
            file.readText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "unreadable diff cache; deleting corrupt files")
            deleteCacheFilesLocked(dir)
            return null
        }
        val parsed = try {
            gson.fromJson<Map<String, Diff>>(json, mapType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // JsonSyntaxException (e.g. a truncated file with an unterminated
            // string), JsonIOException, or any other damage: delete the file
            // and start empty instead of crashing on every account emission.
            Timber.tag(TAG).w(e, "corrupt diff cache; deleting and starting empty")
            deleteCacheFilesLocked(dir)
            return null
        }
        if (parsed == null) {
            Timber.tag(TAG).w("empty diff cache file; deleting")
            deleteCacheFilesLocked(dir)
            return null
        }
        val validated = LinkedHashMap<String, Diff>(parsed.size)
        var dropped = 0
        for (entry in parsed.entries) {
            if (isUsableEntry(entry.key, entry.value)) {
                validated[entry.key] = entry.value
            } else {
                dropped++
            }
        }
        if (dropped > 0) {
            Timber.tag(TAG).w("ignoring $dropped malformed diff cache entries")
            if (validated.isEmpty()) {
                deleteCacheFilesLocked(dir)
            } else {
                writeDiffsAtomicLocked(validated, dir)
            }
        }
        return validated
    }

    /**
     * Gson materializes damaged records reflectively: a null value, or a
     * record whose non-null Kotlin id fields are actually null at runtime.
     * Checking through nullable parameters filters those instead of crashing
     * later when the entry is read.
     */
    private fun isUsableEntry(key: String?, value: Diff?): Boolean {
        if (key.isNullOrBlank() || value == null) return false
        val articleId: String? = value.articleId
        val feedId: String? = value.feedId
        return !articleId.isNullOrEmpty() && !feedId.isNullOrEmpty()
    }

    /** Caller must hold [cacheIoMutex]. */
    private fun deleteCacheFilesLocked(targetDir: File = userCacheDir) {
        deleteQuietly(targetDir.resolve(CACHE_FILE_NAME))
        deleteQuietly(targetDir.resolve(CACHE_TMP_FILE_NAME))
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (expected: SecurityException) {
            Timber.tag(TAG).w(expected, "failed to delete ${file.name}")
        }
    }
}

@Keep
data class Diff(
    @SerializedName("isUnread") val isUnread: Boolean,
    @SerializedName("articleId") val articleId: String,
    @SerializedName("feedId") val feedId: String,
) {
    constructor(isUnread: Boolean, articleWithFeed: ArticleWithFeed) : this(
        isUnread = isUnread,
        articleId = articleWithFeed.article.id,
        feedId = articleWithFeed.feed.id,
    )
}
