package me.ash.reader.ui.page.settings.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.download.DownloadPolicy
import me.ash.reader.domain.model.download.DownloadStore
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.DownloadDao
import me.ash.reader.domain.repository.DownloadWithTitle
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.DownloadService
import me.ash.reader.domain.service.DownloadWorker
import me.ash.reader.infrastructure.di.IODispatcher

/**
 * S16/S28/S29 — Downloads settings surface: account-scoped store usage,
 * download rows with honest state text (incl. the ENQUEUED "Waiting for
 * Wi-Fi" pending state), per-item and bulk removal, and the capped bulk
 * download action.
 */
@HiltViewModel
class DownloadsViewModel
@Inject
constructor(
    downloadDao: DownloadDao,
    private val articleDao: ArticleDao,
    private val downloadService: DownloadService,
    private val downloadStore: DownloadStore,
    private val accountService: AccountService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    workManager: WorkManager,
) : ViewModel() {

    val items: StateFlow<List<DownloadWithTitle>> =
        downloadDao
            .queryAllWithTitles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * S28a — honest pending states: per-article (manual, runs immediately)
     * vs bulk (constraint-gated). A row is "Waiting for Wi-Fi" only while it
     * is pending, has no manual work enqueued, and the bulk worker is
     * ENQUEUED under its UNMETERED constraint.
     */
    data class WorkStates(
        val manualEnqueued: Set<String> = emptySet(),
        val bulkEnqueued: Boolean = false,
    )

    val workStates: StateFlow<WorkStates> =
        workManager
            .getWorkInfosByTagLiveData(DownloadWorker.DOWNLOAD_TAG)
            .asFlow()
            .map { infos ->
                val enqueued =
                    infos.filter {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                    }
                WorkStates(
                    manualEnqueued =
                        enqueued
                            .flatMap { it.tags }
                            .filter { it.startsWith(DownloadWorker.ARTICLE_TAG_PREFIX) }
                            .map { it.removePrefix(DownloadWorker.ARTICLE_TAG_PREFIX) }
                            .toSet(),
                    bulkEnqueued =
                        enqueued.any { DownloadWorker.BULK_TAG in it.tags },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorkStates())

    /** S28 — global usage with the 90% warn / 100% cap surfaces. */
    val usageBytes: StateFlow<Long> =
        flow {
                while (currentCoroutineContext().isActive) {
                    emit(downloadStore.usageBytes())
                    delay(5_000)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val globalStatus: StateFlow<DownloadPolicy.GlobalStatus> =
        usageBytes
            .map { DownloadPolicy.globalStatus(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadPolicy.GlobalStatus.OK)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun remove(articleId: String) {
        viewModelScope.launch(ioDispatcher) { downloadService.remove(articleId) }
    }

    /** S27a — "Retry images": re-open the missing asset set on a ready row. */
    fun retryImages(articleId: String) {
        viewModelScope.launch(ioDispatcher) { downloadService.resumeAssets(articleId) }
    }

    fun removeAll() {
        viewModelScope.launch(ioDispatcher) { downloadService.removeAll() }
    }

    /** Bulk download of unread articles (S28 bulk cap 200; UI states the cap). */
    fun downloadUnread() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(ioDispatcher) {
            try {
                val ids = articleDao.queryUnreadIds(accountService.getCurrentAccountId())
                downloadService.enqueueBulk(ids)
            } finally {
                _busy.value = false
            }
        }
    }
}
