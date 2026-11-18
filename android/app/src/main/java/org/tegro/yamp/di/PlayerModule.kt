package org.tegro.yamp.di

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.tegro.yamp.data.player.FixedOutputRate
import org.tegro.yamp.data.player.StreamCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    private fun createConfiguredExoPlayer(
        context: Context,
        audioSessionId: Int,
        card: org.tegro.yamp.data.download.CardStorage,
        fixedOutput: FixedOutputRate,
        streamCache: StreamCache
    ): ExoPlayer =
        createConfiguredExoPlayer(context, card, fixedOutput, streamCache).apply { if (audioSessionId > 0) setAudioSessionId(audioSessionId) }

    private fun createConfiguredExoPlayer(
        context: Context,
        card: org.tegro.yamp.data.download.CardStorage,
        fixedOutput: FixedOutputRate,
        streamCache: StreamCache
    ): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val audioSoftwareCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
            )
            if (mimeType.startsWith("audio/")) {
                decoders.sortedByDescending {
                    it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.")
                }
            } else {
                decoders
            }
        }

        // Each player's sink resamples to the mixer's rate while "Fixed output format" is on (D69)
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = fixedOutput.buildSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        }
            .setMediaCodecSelector(audioSoftwareCodecSelector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        // The on-disk stream cache sits below the decryption (D86)
        val encrawDataSourceFactory = org.tegro.yamp.data.player.EncrawDataSourceFactory(context, streamCache)
        // Encrypted tracks on the SD card are read through their own source; everything else as before
        val cardAware = org.tegro.yamp.data.download.CardAwareDataSourceFactory(encrawDataSourceFactory, card)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cardAware)
            // A broken download is retried while the music plays on from what is ahead
            .setLoadErrorHandlingPolicy(org.tegro.yamp.data.player.PlaybackBuffering.PatientLoadErrors())

        return try {
            ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(org.tegro.yamp.data.player.PlaybackBuffering.loadControl())
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        } catch (e: Exception) {
            ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(org.tegro.yamp.data.player.PlaybackBuffering.loadControl())
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false)
                .setHandleAudioBecomingNoisy(true)
                .build()
        }
    }

    /** One on-disk stream cache for the process (D86); its size follows the setting, 0 = off. */
    @Provides
    @Singleton
    fun provideStreamCache(
        @ApplicationContext context: Context,
        settings: org.tegro.yamp.domain.repository.SettingsRepository
    ): StreamCache {
        val cache = StreamCache.create(context)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.streamCacheMb.collect { cache.maxBytes = StreamCache.bytesOf(it) }
        }
        return cache
    }

    /** Writes the next track to disk ahead of time (D86), one at a time, off the main thread. */
    @Provides
    @Singleton
    fun provideNextTrackFetch(cache: StreamCache): org.tegro.yamp.data.player.NextTrackFetch =
        org.tegro.yamp.data.player.NextTrackCacher(cache, CoroutineScope(SupervisorJob()), Dispatchers.IO)

    @Provides
    fun provideCachedStreams(cache: StreamCache): org.tegro.yamp.data.player.CachedStreams = cache

    @Provides
    @Singleton
    fun provideFixedOutputRate(@ApplicationContext context: Context): FixedOutputRate =
        FixedOutputRate(FixedOutputRate.mixerRate(context))

    @Provides
    @Singleton
    fun providePlayerEngine(
        @ApplicationContext context: Context,
        fixedOutput: FixedOutputRate,
        downloadManager: org.tegro.yamp.data.network.TrackDownloadManager,
        wakeLockHelper: org.tegro.yamp.data.player.PlaybackWakeLockHelper,
        equalizer: org.tegro.yamp.data.player.SystemEqualizer,
        card: org.tegro.yamp.data.download.CardStorage,
        journal: org.tegro.yamp.data.player.PlaybackJournal,
        streamCache: StreamCache
    ): org.tegro.yamp.data.repository.PlayerRepositoryImpl {
        // Both players share one audio session for the whole process (D70): every AudioTrack they open, rebuild
        // or swap keeps it, so an equalizer app never sees the session close. The service announces it once.
        val playerA = createConfiguredExoPlayer(context, equalizer.audioSessionId, card, fixedOutput, streamCache)
        val playerB = createConfiguredExoPlayer(context, equalizer.audioSessionId, card, fixedOutput, streamCache)
        return org.tegro.yamp.data.repository.PlayerRepositoryImpl(
            playerA, playerB, downloadManager, wakeLockHelper, journal,
            fullyCached = { key -> streamCache.enabled && streamCache.isComplete(key) }
        )
    }

    @Provides
    fun providePlayerRepository(engine: org.tegro.yamp.data.repository.PlayerRepositoryImpl): org.tegro.yamp.domain.repository.PlayerRepository = engine

    @Provides
    fun provideSessionPlayers(engine: org.tegro.yamp.data.repository.PlayerRepositoryImpl): org.tegro.yamp.data.player.SessionPlayers = engine
}
