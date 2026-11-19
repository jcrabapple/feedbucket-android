package me.ash.reader.infrastructure.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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

    /**
     * E17 SSRF guard — ONE shared stateless instance for the extract
     * pipeline AND the offline download asset fetcher (image URLs from RSS
     * content are untrusted input exactly like extraction targets).
     */
    @Provides
    @Singleton
    fun provideSsrfGuard(): SsrfGuard = SsrfGuard()

    /**
     * E2 (SPEC-EXTRACT-v1) — the ONE extraction pipeline. Fetch runs on the
     * E18 `@ExtractHttpClient` (no cache, 15/30/60 s, manual redirects) with
     * the E17 `SsrfGuard` on every hop; storage is the E4 `applyExtraction`
     * write on the article row. The legacy reader disk-cache helper binding
     * is removed with the cache (E3); its one-time hygiene delete of
     * `cacheDir/readability` runs inside the coordinator init.
     */
    @Provides
    @Singleton
    fun provideExtractCoordinator(
        @ApplicationContext context: Context,
        @ExtractHttpClient extractHttpClient: OkHttpClient,
        articleDao: ArticleDao,
        @IODispatcher ioDispatcher: CoroutineDispatcher,
        ssrfGuard: SsrfGuard,
    ): ExtractCoordinator = ExtractCoordinator(
        fetch = ExtractFetch(
            transport = ExtractFetch.OkHttpTransport(extractHttpClient),
            ssrfGuard = ssrfGuard,
            ioDispatcher = ioDispatcher,
        ),
        articleDao = articleDao,
        ioDispatcher = ioDispatcher,
        // E3 — one-time hygiene: the retired disk cache is deleted on init.
        legacyCacheDir = File(context.cacheDir, ExtractCoordinator.LEGACY_CACHE_DIR_NAME),
    )
}