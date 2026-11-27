package org.tegro.yamp.data.player

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What took the sound: the audio mode first, then the sound playing now. Pure. */
class SoundTakerTest {

    @Test
    fun theAudioModeKnowsACallAndAVoiceChat() {
        assertEquals(SoundTaker.CALL, SoundTaker.byMode(AudioManager.MODE_IN_CALL))
        assertEquals(SoundTaker.CALL, SoundTaker.byMode(AudioManager.MODE_RINGTONE))
        assertEquals(SoundTaker.VOICE_CHAT, SoundTaker.byMode(AudioManager.MODE_IN_COMMUNICATION))
        assertNull("the mode says nothing then", SoundTaker.byMode(AudioManager.MODE_NORMAL))
    }

    @Test
    fun theSoundPlayingNowSaysWhatItIs() {
        assertEquals(SoundTaker.VOICE_CHAT, SoundTaker.byUsages(listOf(AudioAttributes.USAGE_VOICE_COMMUNICATION)))
        assertEquals(SoundTaker.CALL, SoundTaker.byUsages(listOf(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)))
        assertEquals(SoundTaker.NAVIGATION, SoundTaker.byUsages(listOf(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)))
        assertEquals(SoundTaker.ASSISTANT, SoundTaker.byUsages(listOf(AudioAttributes.USAGE_ASSISTANT)))
        assertEquals(SoundTaker.ALARM, SoundTaker.byUsages(listOf(AudioAttributes.USAGE_ALARM)))
        assertEquals(SoundTaker.NOTIFICATION, SoundTaker.byUsages(listOf(AudioAttributes.USAGE_NOTIFICATION)))
        assertEquals(SoundTaker.GAME, SoundTaker.byUsages(listOf(AudioAttributes.USAGE_GAME)))
    }

    /** The music's own usage is always among them: something else playing beside it is what counts. */
    @Test
    fun theMusicItselfIsTheLastResort() {
        assertEquals(SoundTaker.APP, SoundTaker.byUsages(listOf(AudioAttributes.USAGE_MEDIA)))
        assertEquals(SoundTaker.APP, SoundTaker.byUsages(emptyList()))
    }

    @Test
    fun aCallBeatsWhatIsHeardWithIt() {
        assertEquals(
            SoundTaker.CALL,
            SoundTaker.byUsages(listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioAttributes.USAGE_ALARM))
        )
        assertEquals(
            SoundTaker.NAVIGATION,
            SoundTaker.byUsages(listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
        )
    }
}
