package org.tegro.yamp.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.tegro.yamp.R
import org.tegro.yamp.data.provider.local.AppLocalAccountHooks
import org.tegro.yamp.data.provider.local.AppLocalAccountStore
import org.tegro.yamp.data.provider.local.LocalAccountHooks
import org.tegro.yamp.data.provider.local.LocalAccountStore
import org.tegro.yamp.data.provider.local.LocalArt
import org.tegro.yamp.data.provider.local.LocalJournal
import org.tegro.yamp.data.provider.local.LocalLabels
import org.tegro.yamp.data.provider.local.LocalLibrary
import org.tegro.yamp.data.provider.local.LocalProvider
import org.tegro.yamp.data.provider.local.LocalSources
import org.tegro.yamp.data.provider.local.LocalStreamResolver
import org.tegro.yamp.data.provider.local.VolumeMonitor
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import org.tegro.yamp.data.provider.local.scan.LocalScanStatus
import org.tegro.yamp.data.provider.local.scan.LocalScanner
import org.tegro.yamp.data.provider.local.scan.Media3TagReader
import javax.inject.Singleton

/** The local files provider (docs/phase-4d-local-files.md): storage, scanner (steps 3–4), provider and account (step 5). */
@Module
@InstallIn(SingletonComponent::class)
object LocalModule {
    @Provides
    @Singleton
    fun provideLocalDatabase(@ApplicationContext context: Context): LocalDatabase = LocalDatabase.build(context)

    @Provides
    @Singleton
    fun provideLocalScanner(
        @ApplicationContext context: Context,
        db: LocalDatabase,
        volumes: VolumeMonitor,
        sources: LocalSources,
        status: LocalScanStatus,
        journal: LocalJournal
    ): LocalScanner = LocalScanner(db, volumes, sources::fsFor, Media3TagReader(context), status, journal::event)

    @Provides
    @Singleton
    fun provideLocalLibrary(db: LocalDatabase): LocalLibrary = LocalLibrary(db)

    @Provides
    @Singleton
    fun provideLocalStreams(library: LocalLibrary, volumes: VolumeMonitor, sources: LocalSources): LocalStreamResolver =
        LocalStreamResolver(library, volumes, sources::fsFor)

    @Provides
    @Singleton
    fun provideLocalProvider(
        @ApplicationContext context: Context,
        library: LocalLibrary,
        volumes: VolumeMonitor,
        streams: LocalStreamResolver,
        art: LocalArt
    ): LocalProvider = LocalProvider(library, volumes, streams, {
        LocalLabels(context.getString(R.string.local_unknown_artist), context.getString(R.string.local_volume_phone))
    }, art)

    @Provides
    @Singleton
    fun provideLocalPlayback(volumes: VolumeMonitor, provider: LocalProvider, library: LocalLibrary): org.tegro.yamp.data.player.LocalPlayback =
        org.tegro.yamp.data.provider.local.VolumeLocalPlayback(volumes, provider, library)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalBindings {
    @Binds
    abstract fun bindLocalAccountStore(store: AppLocalAccountStore): LocalAccountStore

    @Binds
    abstract fun bindLocalAccountHooks(hooks: AppLocalAccountHooks): LocalAccountHooks
}
