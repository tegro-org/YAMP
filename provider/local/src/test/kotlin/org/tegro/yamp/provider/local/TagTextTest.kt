package org.tegro.yamp.provider.local

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Test

class TagTextTest {

    private val cp1251 = Charset.forName("windows-1251")

    /** What a Latin-1 reader shows for CP1251 bytes. */
    private fun mojibake(text: String) = String(text.toByteArray(cp1251), Charsets.ISO_8859_1)

    /** What a Latin-1 reader shows for UTF-8 bytes. */
    private fun utf8Mojibake(text: String) = String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    @Test
    fun theClassicPatternsAreRepaired() {
        assertEquals("Мельница", TagText.repair("Ìåëüíèöà"))
        assertEquals("Кино", TagText.repair("Êèíî"))
        assertEquals("Звезда по имени Солнце", TagText.repair("Çâåçäà ïî èìåíè Ñîëíöå"))
        assertEquals("Ёлка", TagText.repair("¨ëêà"))
        assertEquals("Флёр", TagText.repair("Ôë¸ð"))
    }

    @Test
    fun realTitlesRoundTrip() {
        val titles = listOf(
            "Ты",
            "Сплин — Орбит без сахара",
            "ДДТ - Что такое осень",
            "Nautilus Pompilius - Прогулки по воде",
            "Би-2 feat. Земфира",
            "Ария – Беспечный ангел (live)",
            "№ 5 «Вальс»",
            "Песня… (remix)",
            "DJ Грув — Счастье есть",
            "Я в моём уме",
            "Король и Шут - Лесник 1996"
        )
        for (title in titles) assertEquals(title, TagText.repair(mojibake(title)))
    }

    @Test
    fun utf8InALatin1FrameIsRepaired() {
        assertEquals("Мельница", TagText.repair(utf8Mojibake("Мельница")))
        assertEquals("Björk", TagText.repair(utf8Mojibake("Björk")))
        assertEquals("Sigur Rós – Hoppípolla", TagText.repair(utf8Mojibake("Sigur Rós – Hoppípolla")))
    }

    @Test
    fun trueLatin1StaysAsItIs() {
        val texts = listOf(
            "Björk", "Café del Mar", "Motörhead", "Sigur Rós", "Mötley Crüe", "Françoise Hardy",
            "Édith Piaf - Non, je ne regrette rien", "Señor", "Ça plane pour moi", "Straße", "Ångström", "Hôtel à Paris"
        )
        for (text in texts) assertEquals(text, TagText.repair(text))
    }

    @Test
    fun asciiAndAlreadyDecodedTextIsUntouched() {
        assertEquals("Pink Floyd", TagText.repair("Pink Floyd"))
        assertEquals("", TagText.repair(""))
        // a UTF-16 / UTF-8 frame already gave real Cyrillic: above U+00FF, left alone
        assertEquals("Мельница", TagText.repair("Мельница"))
        assertEquals("Ìåëüíèöà и", TagText.repair("Ìåëüíèöà и"))
    }

    @Test
    fun legacyBytesDecodeByContent() {
        assertEquals("Мельница", TagText.decode("Мельница".toByteArray(cp1251)))
        assertEquals("Мельница", TagText.decode("Мельница".toByteArray(Charsets.UTF_8)))
        assertEquals("Björk", TagText.decode("Björk".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("Björk", TagText.decode("Björk".toByteArray(Charsets.UTF_8)))
        assertEquals("Plain", TagText.decode("Plain".toByteArray(Charsets.US_ASCII)))
    }
}
