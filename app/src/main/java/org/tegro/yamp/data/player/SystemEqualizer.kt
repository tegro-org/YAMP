package org.tegro.yamp.data.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's or head unit's own equalizer. Both players share one audio session for the life of the process
 * (D70): every AudioTrack a player opens — a new track, a crossfade, a rebuild — joins that same session, so an
 * equalizer attached to it shapes every track, the crossfade included. The app has no equalizer of its own.
 *
 * Equalizer apps learn about the session from one broadcast pair: OPEN once, when the playback service first
 * plays, and CLOSE only when the service goes away. Never per track, per pause or per focus regain: Poweramp
 * Equalizer, for one, briefly takes the audio focus whenever a session it equalizes closes or is re-announced,
 * and a head unit turns that into a permanent focus loss for the player (docs/playback-stability.md, F9).
 */
@Singleton
class SystemEqualizer(
    private val context: Context,
    /** The one session both players use; 0 lets each player get its own when the system cannot make one. */
    val audioSessionId: Int,
    private val log: org.tegro.yamp.data.diagnostics.DiagnosticLog? = null
) {
    @Inject
    constructor(@ApplicationContext context: Context, log: org.tegro.yamp.data.diagnostics.DiagnosticLog) :
        this(context, newSessionId(context), log = log)

    /** The OPEN went out and no CLOSE yet: this playback-service lifetime has announced its session. */
    private var opened = false

    private fun panelIntent() = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)

    /**
     * Something on the device can show an equalizer for this session. Asked each time: an equalizer app
     * installed, removed or switched off while the app runs counts at once.
     */
    fun isAvailable(): Boolean =
        audioSessionId > 0 && runCatching { panelIntent().resolveActivity(context.packageManager) != null }.getOrDefault(false)

    /**
     * Tells equalizer apps the session exists — once per service lifetime; later calls do nothing until [close].
     */
    @Synchronized
    fun openOnce() {
        if (audioSessionId <= 0 || opened) return
        opened = true
        log?.event("EQ", "session $audioSessionId opened")
        runCatching {
            context.sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            )
        }.onFailure { Log.w(TAG, "Equalizer session not opened", it) }
    }

    /** The service goes away: equalizer apps let go of the session instead of holding a dead one. */
    @Synchronized
    fun close() {
        if (!opened) return
        opened = false
        log?.event("EQ", "session $audioSessionId closed")
        runCatching {
            context.sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            )
        }.onFailure { Log.w(TAG, "Equalizer session not closed", it) }
    }

    /** Opens the session the first time the sound starts in this service lifetime (see [openOnce]). */
    fun openWhenTheSoundStarts(scope: CoroutineScope, isPlaying: Flow<Boolean>): Job =
        scope.launch { isPlaying.collect { playing -> if (playing) openOnce() } }

    fun open() {
        if (!isAvailable()) return
        try {
            context.startActivity(panelIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No equalizer to open", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Equalizer refused to open", e)
        }
    }

    /**
     * Poweramp Equalizer is installed. It takes over a session's volume (DVC) and fades a player when a session
     * closes ("Fade"); its per-app settings for YAMP decide whether a crossfade sounds clean, and only the user can
     * change them — the app has no API for that (docs/playback-stability.md).
     */
    fun powerampEqualizerInstalled(): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(POWERAMP_EQUALIZER) != null }.getOrDefault(false)

    /** Opens Poweramp Equalizer itself, where the user finds Known Players → YAMP. */
    fun openPowerampEqualizer() {
        val launch = runCatching { context.packageManager.getLaunchIntentForPackage(POWERAMP_EQUALIZER) }.getOrNull() ?: return
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Poweramp Equalizer did not open", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Poweramp Equalizer refused to open", e)
        }
    }

    companion object {
        private const val TAG = "SystemEqualizer"
        const val POWERAMP_EQUALIZER = "com.maxmpz.equalizer"

        private fun newSessionId(context: Context): Int = runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        }.getOrDefault(0).coerceAtLeast(0)
    }
}
