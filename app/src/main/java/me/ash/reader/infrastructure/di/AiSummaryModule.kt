package me.ash.reader.infrastructure.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.ash.reader.infrastructure.aisummary.AiSummarySecretStore
import me.ash.reader.infrastructure.aisummary.AndroidKeystoreAiSummarySecretStore
import javax.inject.Singleton

/**
 * AI Summaries — binds the secret-storage seam so the API key is only ever
 * persisted AndroidKeyStore-encrypted (never plaintext DataStore). The
 * settings store and the summary service use plain @Inject constructors.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiSummaryModule {

    @Binds
    @Singleton
    abstract fun bindAiSummarySecretStore(
        impl: AndroidKeystoreAiSummarySecretStore,
    ): AiSummarySecretStore
}
