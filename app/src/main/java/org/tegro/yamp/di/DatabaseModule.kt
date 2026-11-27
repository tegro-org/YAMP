package org.tegro.yamp.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.tegro.yamp.data.cache.CacheDao
import org.tegro.yamp.data.cache.CacheDatabase
import org.tegro.yamp.data.history.HistoryDao
import org.tegro.yamp.data.queue.QueueDao
import javax.inject.Singleton

/** Room, Hilt-provided (ARCHITECTURE F1). The same instance `CacheDatabase.get` hands to the old code. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CacheDatabase = CacheDatabase.get(context)

    @Provides
    fun provideCacheDao(db: CacheDatabase): CacheDao = db.dao()

    @Provides
    fun provideQueueDao(db: CacheDatabase): QueueDao = db.queueDao()

    @Provides
    fun provideHistoryDao(db: CacheDatabase): HistoryDao = db.historyDao()
}
