package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import me.ash.reader.domain.model.download.DownloadErrorCause
import me.ash.reader.domain.model.download.DownloadPolicy

/**
 * S28a — one-time download work. Manual requests carry ONE articleId and NO
 * network constraint (explicit user intent; an airplane-mode tap runs and
 * fails fast to `failed(network)`). Bulk/chained requests carry no articleId
 * and drain the pending batch under UNMETERED. Retry rule is worker-enforced:
 * `runAttemptCount >= 3` → terminal `failed(network)`; else `Result.retry()`
 * with EXPONENTIAL 30 s backoff (floors 30/60/120 s, clamped [10 s, 5 h] —
 * floors only, never promised wake times).
 */
@HiltWorker
class DownloadWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadService: DownloadService,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val articleId = inputData.getString(KEY_ARTICLE_ID)
        return try {
            if (articleId != null) downloadService.download(articleId)
            else downloadService.downloadPendingBatch()
            Result.success()
        } catch (e: DownloadService.AttemptFailed) {
            when {
                // Storage/oversize failures never benefit from a retry.
                e.errorCause == DownloadErrorCause.STORAGE || e.errorCause == DownloadErrorCause.OVERSIZE -> {
                    if (articleId != null) downloadService.markFailed(articleId, e.errorCause)
                    Result.success()
                }
                // Batch mode: the service already bounded row attempts; the
                // next sync chain re-drains surviving pending rows.
                articleId == null -> Result.success()
                else ->
                    when (DownloadPolicy.retryOutcome(runAttemptCount)) {
                        DownloadPolicy.RetryOutcome.RETRY -> Result.retry()
                        DownloadPolicy.RetryOutcome.TERMINAL_NETWORK -> {
                            downloadService.markFailed(articleId, DownloadErrorCause.NETWORK)
                            Result.success()
                        }
                    }
            }
        }
    }

    companion object {
        const val KEY_ARTICLE_ID = "download_article_id"
        const val DOWNLOAD_TAG = "DOWNLOAD_TAG"
        const val ARTICLE_TAG_PREFIX = "download_article:"
        const val BULK_WORK_NAME = "download_bulk"

        fun uniqueName(articleId: String) = "download-$articleId"

        fun articleTag(articleId: String) = ARTICLE_TAG_PREFIX + articleId

        private fun baseBuilder(): OneTimeWorkRequest.Builder =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .addTag(DOWNLOAD_TAG)
                .addTag(SyncWorker.ONETIME_WORK_TAG)
                .setBackoffCriteria(
                    DownloadPolicy.BACKOFF_POLICY,
                    DownloadPolicy.BACKOFF_DELAY_SECS,
                    TimeUnit.SECONDS,
                )

        /** Manual single-article request: NO network constraint (R6). */
        fun manualRequest(articleId: String): OneTimeWorkRequest =
            baseBuilder()
                .setInputData(workDataOf(KEY_ARTICLE_ID to articleId))
                .addTag(articleTag(articleId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            DownloadPolicy.networkTypeFor(DownloadPolicy.NetworkPolicy.MANUAL)
                        )
                        .build()
                )
                .build()

        /** Bulk/chained request: UNMETERED, drains the whole pending batch. */
        fun bulkRequest(requiresCharging: Boolean = false): OneTimeWorkRequest =
            baseBuilder()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            DownloadPolicy.networkTypeFor(DownloadPolicy.NetworkPolicy.BULK)
                        )
                        .setRequiresCharging(requiresCharging)
                        .build()
                )
                .build()

        /** Manual retry is always allowed and resets the counter (S28a). */
        fun enqueueManual(workManager: WorkManager, articleId: String) {
            workManager.enqueueUniqueWork(
                uniqueName(articleId),
                ExistingWorkPolicy.REPLACE,
                manualRequest(articleId),
            )
        }
    }
}
