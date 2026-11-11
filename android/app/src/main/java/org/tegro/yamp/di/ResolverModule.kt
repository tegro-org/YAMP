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
    abstract fun bindTrackUrlResolver(impl: TrackPreloadManager): TrackUrlResolver

    @Binds
    abstract fun bindQueueStore(impl: org.tegro.yamp.data.queue.RoomQueueStore): org.tegro.yamp.domain.queue.QueueStore

    @Binds
    abstract fun bindQueueTrackBridge(impl: org.tegro.yamp.data.queue.QueueTrackBridge): org.tegro.yamp.data.queue.QueueTrackBridgeApi
}
