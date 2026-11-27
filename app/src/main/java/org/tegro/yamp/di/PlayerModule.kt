package org.tegro.yamp.di

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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

    /**
     * A station on HLS (Like FM, Авторадио) offers 128, 64 and 32 kbit/s: always the best, never the thin 32.
     * A new one for each player: a selector belongs to one player.
     */
    internal fun bestVariantSelector(context: Context) = DefaultTrackSelector(
        context,
        DefaultTrackSelector.Parameters.Builder(context).setForceHighestSupportedBitrate(true).build()
    )

    private fun createConfiguredExoPlayer(
        context: Context,
        audioSessionId: Int,
        card: org.tegro.yamp.data.download.CardStorage,
        fixedOutput: FixedOutputRate,
        streamCache: StreamCache,
        gain: org.tegro.yamp.data.player.SampleGain
    ): ExoPlayer =
        createConfiguredExoPlayer(context, card, fixedOutput, streamCache, gain).apply { if (audioSessionId > 0) setAudioSessionId(audioSessionId) }

    private fun createConfiguredExoPlayer(
        context: Context,
        card: org.tegro.yamp.data.download.CardStorage,
        fixedOutput: FixedOutputRate,
        streamCache: StreamCache,
        gain: org.tegro.yamp.data.player.SampleGain
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

        // Each player's sink resamples to the mixer's rate (D69) and applies the player's fades to its samples (D98)
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = fixedOutput.buildSink(context, enableFloatOutput, enableAudioTrackPlaybackParams, gain)
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
                .setTrackSelector(bestVariantSelector(context))
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(org.tegro.yamp.data.player.PlaybackBuffering.loadControl())
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false)
                .setHandleAudioBecomingNoisy(true)
                // The processor and the Wi-Fi stay awake while the music plays: a sleeping Wi-Fi with the screen
                // off fills the buffer in jerks, and the music runs dry
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
        } catch (e: Exception) {
            ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(bestVariantSelector(context))
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
        settings: org.tegro.yamp.domain.repository.SettingsRepository,
        journal: org.tegro.yamp.data.player.PlaybackJournal,
        quality: org.tegro.yamp.data.player.AccountQualityStore
    ): StreamCache {
        val cache = StreamCache.create(context)
        fun trackOf(key: String) = key.split('|').getOrNull(1) ?: "?"
        cache.onOfflineStart = { key ->
            journal.record(org.tegro.yamp.data.player.PlaybackJournal.Kind.CACHE, detail = "offline start, track ${trackOf(key)}")
        }
        cache.onProgressive = { key, detail ->
            journal.record(org.tegro.yamp.data.player.PlaybackJournal.Kind.CACHE, detail = "progressive ${trackOf(key)}: $detail")
        }
        // On a metered network the next track's conversion is downloaded after the playing one's (D91)
        cache.progressive.metered = { quality.isMetered() }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Older builds' playing-track copies and partial entries of streams with no length can never complete
            cache.dropStaleEntries()
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
        // Fades are in the samples (D98): a player's own volume stays 1, an effect that controls the volume can't undo them
        val gainA = org.tegro.yamp.data.player.SampleGain()
        val gainB = org.tegro.yamp.data.player.SampleGain()
        val playerA = createConfiguredExoPlayer(context, equalizer.audioSessionId, card, fixedOutput, streamCache, gainA)
        val playerB = createConfiguredExoPlayer(context, equalizer.audioSessionId, card, fixedOutput, streamCache, gainB)
        return org.tegro.yamp.data.repository.PlayerRepositoryImpl(
            playerA, playerB, gainA, gainB, downloadManager, wakeLockHelper, journal,
            fullyCached = { key -> streamCache.enabled && streamCache.isComplete(key) || streamCache.progressive.isComplete(key) },
            downloadShare = { key, bufferedMs, durationMs -> streamCache.downloadShare(key, bufferedMs, durationMs) },
            progressive = streamCache.progressive,
            outputRoute = { org.tegro.yamp.data.player.AudioOutputs.current(context) }
        )
    }

    @Provides
    fun providePlayerRepository(engine: org.tegro.yamp.data.repository.PlayerRepositoryImpl): org.tegro.yamp.domain.repository.PlayerRepository = engine

    @Provides
    fun provideSessionPlayers(engine: org.tegro.yamp.data.repository.PlayerRepositoryImpl): org.tegro.yamp.data.player.SessionPlayers = engine

    /** MonJaro Audio Router awareness (hard cuts while it routes to USB). */
    @Provides
    @Singleton
    fun provideAudioRouterWatch(detector: org.tegro.yamp.data.player.AudioRouterDetector): org.tegro.yamp.data.player.AudioRouterWatch = detector

    /** The Plus gate (D101): which Yandex accounts' music is refused. */
    @Provides
    @Singleton
    fun providePlusGate(gate: org.tegro.yamp.data.account.AccountPlusGate): org.tegro.yamp.data.account.PlusGate = gate
}
