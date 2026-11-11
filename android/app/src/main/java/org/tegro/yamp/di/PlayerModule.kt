package org.tegro.yamp.di

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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

    private fun createConfiguredExoPlayer(context: Context, audioSessionId: Int, card: org.tegro.yamp.data.download.CardStorage): ExoPlayer =
        createConfiguredExoPlayer(context, card).apply { if (audioSessionId > 0) setAudioSessionId(audioSessionId) }

    private fun createConfiguredExoPlayer(context: Context, card: org.tegro.yamp.data.download.CardStorage): ExoPlayer {
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

        val renderersFactory = DefaultRenderersFactory(context)
            .setMediaCodecSelector(audioSoftwareCodecSelector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val encrawDataSourceFactory = org.tegro.yamp.data.player.EncrawDataSourceFactory(context)
        // Encrypted tracks on the SD card are read through their own source; everything else as before
        val cardAware = org.tegro.yamp.data.download.CardAwareDataSourceFactory(encrawDataSourceFactory, card)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cardAware)

        return try {
            ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        } catch (e: Exception) {
            ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false)
                .setHandleAudioBecomingNoisy(true)
                .build()
        }
    }

    @Provides
    @Singleton
    fun providePlayerRepository(
        @ApplicationContext context: Context,
        downloadManager: org.tegro.yamp.data.network.TrackDownloadManager,
        preloadManager: org.tegro.yamp.data.network.TrackPreloadManager,
        wakeLockHelper: org.tegro.yamp.data.player.PlaybackWakeLockHelper,
        equalizer: org.tegro.yamp.data.player.SystemEqualizer,
        card: org.tegro.yamp.data.download.CardStorage,
        settings: org.tegro.yamp.domain.repository.SettingsRepository
    ): org.tegro.yamp.domain.repository.PlayerRepository {
        // Both players start in the shared session; the engine moves player B to its own one when the
        // "separate audio sessions" setting says so (read asynchronously, applied while B is idle).
        val playerA = createConfiguredExoPlayer(context, equalizer.audioSessionId, card)
        val playerB = createConfiguredExoPlayer(context, equalizer.audioSessionId, card)
        equalizer.announce()
        val sessions = org.tegro.yamp.data.repository.PlayerRepositoryImpl.AudioSessionPolicy(
            shared = equalizer.audioSessionId,
            own = equalizer.secondAudioSessionId,
            separate = settings.separateAudioSessions,
            announce = equalizer::announce
        )
        return org.tegro.yamp.data.repository.PlayerRepositoryImpl(
            playerA, playerB, downloadManager, preloadManager, wakeLockHelper, sessions
        )
    }

}


