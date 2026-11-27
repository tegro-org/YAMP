package org.tegro.yamp.data.player

import android.media.AudioAttributes
import android.media.AudioManager

/**
 * What took the sound from the music, as the journal keeps it ([PlaybackJournal.Event.detail] of a pause): another
 * app for good, or — for a while — a call, a voice chat, a navigator, an assistant, a notification, an alarm or a game.
 * Pure; unit-tested.
 */
object SoundTaker {
    const val CALL = "call"
    const val VOICE_CHAT = "voice_chat"
    const val NAVIGATION = "navigation"
    const val ASSISTANT = "assistant"
    const val NOTIFICATION = "notification"
    const val ALARM = "alarm"
    const val GAME = "game"

    /** Some other app with sound, or nothing Android would name. */
    const val APP = "app"

    /** AudioAttributes.USAGE_ASSISTANT: Android 8 and later. */
    private const val USAGE_ASSISTANT = 16

    private val NOTIFICATIONS = setOf(
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        AudioAttributes.USAGE_NOTIFICATION_EVENT,
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
    )

    /**
     * What the audio mode alone says: a call or a ringing phone, a voice chat. Null where the mode says nothing and
     * only the sound playing now can tell ([byUsages]).
     */
    fun byMode(mode: Int): String? = when (mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_RINGTONE -> CALL
        AudioManager.MODE_IN_COMMUNICATION -> VOICE_CHAT
        else -> null
    }

    /** From the kinds of sound playing now (the music's own among them), the one that most likely took it. */
    fun byUsages(usages: Collection<Int>): String = when {
        AudioAttributes.USAGE_VOICE_COMMUNICATION in usages -> VOICE_CHAT
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE in usages -> CALL
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE in usages -> NAVIGATION
        USAGE_ASSISTANT in usages -> ASSISTANT
        AudioAttributes.USAGE_ALARM in usages -> ALARM
        usages.any { it in NOTIFICATIONS } -> NOTIFICATION
        AudioAttributes.USAGE_GAME in usages -> GAME
        else -> APP
    }
}
