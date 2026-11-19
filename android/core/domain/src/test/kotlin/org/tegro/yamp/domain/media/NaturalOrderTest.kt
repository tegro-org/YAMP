package org.tegro.yamp.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.toList().sortedWith(NaturalOrder)

    @Test
    fun latinBeforeCyrillicCaseInsensitive() {
        assertEquals(
            listOf("Metallica (mp3)", "pink floyd", "RC - Sorted", "Аквариум и БГ (mp3)", "детские", "Мельница & Хелависа - альбомы"),
            sorted("Мельница & Хелависа - альбомы", "RC - Sorted", "детские", "pink floyd", "Аквариум и БГ (mp3)", "Metallica (mp3)")
        )
    }

    @Test
    fun numbersByValue() {
        assertEquals(listOf("Disc 1", "Disc 2", "Disc 10"), sorted("Disc 10", "Disc 2", "Disc 1"))
        assertEquals(listOf("2003 — Перевал", "2009 — Дикие травы", "2012 — Ангелофрения"), sorted("2012 — Ангелофрения", "2003 — Перевал", "2009 — Дикие травы"))
        assertTrue(NaturalOrder.compare("Track 007", "Track 7") != 0)
        assertTrue(NaturalOrder.compare("a99999999999999999999", "a100000000000000000000") < 0)
    }

    @Test
    fun yoComesAfterYe() {
        assertEquals(listOf("Ежи", "Ёжик", "Жук"), sorted("Жук", "Ёжик", "Ежи"))
    }

    @Test
    fun prefixFirst() {
        assertEquals(listOf("Queen", "Queen - Live"), sorted("Queen - Live", "Queen"))
    }
}
