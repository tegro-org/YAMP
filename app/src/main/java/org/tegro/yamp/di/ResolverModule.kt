package org.tegro.yamp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.tegro.yamp.data.network.TrackPreloadManager
import org.tegro.yamp.data.network.TrackUrlResolver

/** Interfaces of the player's data side bound to their one implementation (ARCHITECTURE G1). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ResolverModule {
    @Binds
    internal abstract fun bindTrackUrlResolver(impl: TrackPreloadManager): TrackUrlResolver

    /** The controller resolves through the resolver of the entry's account (D74, phase 3 step 10). */
    @Binds
    abstract fun bindStreamResolvers(impl: org.tegro.yamp.data.provider.ProviderRegistry): org.tegro.yamp.data.provider.StreamResolvers

    @Binds
    abstract fun bindQueueStore(impl: org.tegro.yamp.data.queue.RoomQueueStore): org.tegro.yamp.domain.queue.QueueStore

    @Binds
    abstract fun bindCacheKeyMigration(impl: org.tegro.yamp.data.account.AccountKeyMigration): org.tegro.yamp.data.cache.CacheKeyMigration

    @Binds
    abstract fun bindQueueTrackBridge(impl: org.tegro.yamp.data.provider.yandex.queue.QueueTrackBridge): org.tegro.yamp.data.provider.yandex.queue.QueueTrackBridgeApi
}
