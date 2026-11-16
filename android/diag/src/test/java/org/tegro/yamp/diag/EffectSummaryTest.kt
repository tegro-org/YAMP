package org.tegro.yamp.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The effect line, from a trimmed audio_flinger dump of the head unit (2026-11-16, Poweramp DVC on YAMP's session). */
class EffectSummaryTest {

    private val dump = javaClass.classLoader!!.getResource("audio_flinger_dvc.txt")!!.readText()

    @Test
    fun yampsSessionIsFoundByItsPid() {
        assertEquals(setOf(193), EffectSummary.yampSessions(dump, yampPid = 7484))
    }

    @Test
    fun theThreeEffectsAndWhichTakeTheVolume() {
        val effects = EffectSummary.effects(dump).filter { it.session == 193 }
        assertEquals(listOf("Visualizer", "Dynamics Processing", "Volume"), effects.map { it.name })
        assertEquals(listOf(false, true, true), effects.map { it.takesVolume })
        assertEquals(listOf(4451), effects.first().clientPids)
    }

    @Test
    fun theLineNamesPowerampAndSaysDvc() {
        val line = EffectSummary.line(dump, "7484\n", "  PID NAME\n 4451 com.maxmpz.equalizer\n 7484 org.tegro.music.yamp\n")
        assertTrue(line, line.startsWith("DVC on YAMP session 193: yes (Dynamics Processing, Volume)"))
        assertTrue(line, "Dynamics Processing (com.maxmpz.equalizer) [takes volume]" in line)
    }

    @Test
    fun noYampNoGuess() {
        assertEquals("effects: YAMP not running", EffectSummary.line(dump, "", ""))
        assertEquals("effects: no audio_flinger dump", EffectSummary.line(null, "7484", ""))
    }
}
