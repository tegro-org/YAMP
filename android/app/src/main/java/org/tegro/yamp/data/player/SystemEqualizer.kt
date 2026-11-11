package org.tegro.yamp.data.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's or head unit's own equalizer. By default both players share one audio session, so an
 * equalizer attached to it shapes every track, the crossfade included. With "separate audio sessions"
 * (Settings → Playback) the second player gets [secondAudioSessionId] and both are announced: a sound
 * chain that applies one volume per session (some head units) then no longer blurs the crossfade.
 * The app has no equalizer of its own.
 */
@Singleton
class SystemEqualizer @Inject constructor(@ApplicationContext private val context: Context) {

    private fun newSessionId(): Int = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
    }.getOrDefault(0).coerceAtLeast(0)

    /** The main session, shared by both players unless separated; 0 lets each player get its own when the system cannot make one. */
    val audioSessionId: Int = newSessionId()

    /** The second player's own session when the sessions are separated. */
    val secondAudioSessionId: Int by lazy { newSessionId() }

    private fun panelIntent() = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)

    /** Something on the device can show an equalizer for this session. */
    val available: Boolean by lazy {
        audioSessionId > 0 && runCatching { panelIntent().resolveActivity(context.packageManager) != null }.getOrDefault(false)
    }

    /** Tells equalizer apps the session exists, so a system-wide one applies its preset to the music. */
    fun announce(sessionId: Int = audioSessionId) {
        if (sessionId <= 0) return
        runCatching {
            context.sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            )
        }.onFailure { Log.w(TAG, "Equalizer session not announced", it) }
    }

    fun open() {
        if (!available) return
        try {
            context.startActivity(panelIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No equalizer to open", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Equalizer refused to open", e)
        }
    }

    private companion object {
        const val TAG = "SystemEqualizer"
    }
}
