package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.general.toVersion
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.net.Download
import me.ash.reader.infrastructure.net.FeedBucketUpdate
import me.ash.reader.infrastructure.net.NetworkDataSource
import me.ash.reader.infrastructure.net.downloadToFileWithProgress
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.infrastructure.preference.NewVersionSizePreference.formatSize
import me.ash.reader.ui.ext.getCurrentVersion
import me.ash.reader.ui.ext.getLatestApk
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.ext.skipVersionNumber
import javax.inject.Inject

class AppService @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val networkDataSource: NetworkDataSource,
    @IODispatcher
    private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher
    private val mainDispatcher: CoroutineDispatcher,
) {

    suspend fun checkUpdate(showToast: Boolean = true): Boolean? = withContext(ioDispatcher) {
        // Debug builds never check for (or install) updates.
        if (context.packageName.endsWith(".debug")) return@withContext null
        try {
            val response = networkDataSource.getReleases(
                FeedBucketUpdate.releaseApiUrl(context.getString(R.string.feedbucket_release_repo))
            )
            when {
                response.code() == 403 -> {
                    withContext(mainDispatcher) {
                        if (showToast) context.showToast(context.getString(R.string.rate_limit))
                    }
                    return@withContext null
                }

                response.body() == null -> {
                    withContext(mainDispatcher) {
                        if (showToast) context.showToast(context.getString(R.string.check_failure))
                    }
                    return@withContext null
                }
            }
            val skipVersion = context.skipVersionNumber.toVersion()
            val currentVersion = context.getCurrentVersion()
            val candidate = FeedBucketUpdate.pickUpdate(response.body()!!)

            Log.i("RLog", "current version $currentVersion")
            if (candidate != null && candidate.version.whetherNeedUpdate(currentVersion, skipVersion)) {
                Log.i("RLog", "new version ${candidate.version}")
                NewVersionNumberPreference.put(context, this, candidate.version.toString())
                NewVersionLogPreference.put(context, this, candidate.log)
                NewVersionPublishDatePreference.put(context, this, candidate.publishDate)
                NewVersionSizePreference.put(context, this, candidate.size.formatSize())
                NewVersionDownloadUrlPreference.put(context, this, candidate.apkUrl)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("RLog", "checkUpdate: ${e.message}")
            withContext(mainDispatcher) {
                if (showToast) context.showToast(context.getString(R.string.check_failure))
            }
            null
        }
    }

    suspend fun downloadFile(url: String): Flow<Download> =
        withContext(ioDispatcher) {
            Log.i("RLog", "downloadFile start: $url")
            try {
                networkDataSource.downloadFile(url)
                    .downloadToFileWithProgress(context.getLatestApk())
                    .transform { download ->
                        if (download is Download.Finished) {
                            // The sidecar asset is the APK asset name + ".sha256"
                            // (sibling release assets, enforced by FeedBucketUpdate.pickUpdate).
                            val verified = try {
                                FeedBucketUpdate.verifyDownload(
                                    download.file,
                                    networkDataSource.downloadFile("$url.sha256").string(),
                                )
                            } catch (e: Exception) {
                                Log.e("RLog", "downloadFile: sidecar fetch failed: ${e.message}")
                                download.file.delete()
                                false
                            }
                            if (verified) {
                                emit(download)
                            } else {
                                Log.e("RLog", "downloadFile: SHA-256 mismatch, download discarded")
                                emit(Download.NotYet)
                            }
                        } else {
                            emit(download)
                        }
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("RLog", "downloadFile: ${e.message}")
                withContext(mainDispatcher) {
                    context.showToast(context.getString(R.string.download_failure))
                }
                emptyFlow()
            }
        }
}
