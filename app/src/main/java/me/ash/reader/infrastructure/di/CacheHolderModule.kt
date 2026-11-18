package me.ash.reader.infrastructure.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.ExtractCoordinator
import me.ash.reader.domain.service.ExtractFetch
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.infrastructure.net.SsrfGuard
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.rss.RssHelper
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CacheHolderModule {
    @Provides
    @Singleton
    fun provideDiffMapHolder(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
        @IODispatcher ioDispatcher: CoroutineDispatcher,
        accountService: AccountService,
        rssService: RssService,
    ): DiffMapHolder {
        return DiffMapHolder(
            context = context, applicationScope, ioDispatcher, accountService, rssService
        )
    }

    @Provides
    @Singleton
    fun provideCacheHelper(
        @ApplicationContext context: Context,
        @IODispatcher ioDispatcher: CoroutineDispatcher,
        rssHelper: RssHelper,
        accountService: AccountService,
    ): ReaderCacheHelper = ReaderCacheHelper(
        context = context, ioDispatcher = ioDispatcher,
        rssHelper = rssHelper,
        accountService = accountService,
    )

    /**
     * E2 (SPEC-EXTRACT-v1) — the ONE extraction pipeline. Fetch runs on the
     * E18 `@ExtractHttpClient` (no cache, 15/30/60 s, manual redirects) with
     * the E17 `SsrfGuard` on every hop; storage is the E4 `applyExtraction`
     * write on the article row.
     */
    @Provides
    @Singleton
    fun provideExtractCoordinator(
        @ExtractHttpClient extractHttpClient: OkHttpClient,
        articleDao: ArticleDao,
        @IODispatcher ioDispatcher: CoroutineDispatcher,
    ): ExtractCoordinator = ExtractCoordinator(
        fetch = ExtractFetch(
            transport = ExtractFetch.OkHttpTransport(extractHttpClient),
            ssrfGuard = SsrfGuard(),
            ioDispatcher = ioDispatcher,
        ),
        articleDao = articleDao,
        ioDispatcher = ioDispatcher,
    )
}