package org.tegro.yamp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore
import org.tegro.yamp.data.provider.jellyfin.KeystoreJellyfinAccountStore

/** Jellyfin accounts (docs/phase-3-providers.md §5): the encrypted store. */
@Module
@InstallIn(SingletonComponent::class)
abstract class JellyfinModule {
    @Binds
    abstract fun bindJellyfinAccountStore(store: KeystoreJellyfinAccountStore): JellyfinAccountStore

    /** Pinned folders and the car Library's last tab, per account (Phase 4b). */
    @Binds
    abstract fun bindFolderPins(pins: org.tegro.yamp.data.folders.AppFolderPins): org.tegro.yamp.data.folders.FolderPins
}
