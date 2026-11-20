package me.ash.reader.infrastructure.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.ArticleSummaryDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.ImportDao
import me.ash.reader.domain.repository.PositionDao
import me.ash.reader.domain.repository.SavedSearchDao
import me.ash.reader.domain.repository.StateApplyDao
import me.ash.reader.domain.repository.ActionRuleDao
import me.ash.reader.domain.repository.DownloadDao
import me.ash.reader.infrastructure.db.AndroidDatabase
import javax.inject.Singleton

/**
 * Provides Data Access Objects for database.
 *
 * - [ArticleDao]
 * - [FeedDao]
 * - [GroupDao]
 * - [AccountDao]
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideArticleDao(androidDatabase: AndroidDatabase): ArticleDao =
        androidDatabase.articleDao()

    @Provides
    @Singleton
    fun provideFeedDao(androidDatabase: AndroidDatabase): FeedDao =
        androidDatabase.feedDao()

    @Provides
    @Singleton
    fun provideGroupDao(androidDatabase: AndroidDatabase): GroupDao =
        androidDatabase.groupDao()

    @Provides
    @Singleton
    fun provideAccountDao(androidDatabase: AndroidDatabase): AccountDao =
        androidDatabase.accountDao()

    @Provides
    @Singleton
    fun provideSavedSearchDao(androidDatabase: AndroidDatabase): SavedSearchDao =
        androidDatabase.savedSearchDao()

    @Provides
    @Singleton
    fun provideActionRuleDao(androidDatabase: AndroidDatabase): ActionRuleDao =
        androidDatabase.actionRuleDao()

    @Provides
    @Singleton
    fun provideDownloadDao(androidDatabase: AndroidDatabase): DownloadDao =
        androidDatabase.downloadDao()

    @Provides
    @Singleton
    fun providePositionDao(androidDatabase: AndroidDatabase): PositionDao =
        androidDatabase.positionDao()

    @Provides
    @Singleton
    fun provideImportDao(androidDatabase: AndroidDatabase): ImportDao =
        androidDatabase.importDao()

    @Provides
    @Singleton
    fun provideStateApplyDao(androidDatabase: AndroidDatabase): StateApplyDao =
        androidDatabase.stateApplyDao()

    @Provides
    @Singleton
    fun provideArticleSummaryDao(androidDatabase: AndroidDatabase): ArticleSummaryDao =
        androidDatabase.articleSummaryDao()

    @Provides
    @Singleton
    fun provideReaderDatabase(@ApplicationContext context: Context): AndroidDatabase =
        AndroidDatabase.getInstance(context)
}
