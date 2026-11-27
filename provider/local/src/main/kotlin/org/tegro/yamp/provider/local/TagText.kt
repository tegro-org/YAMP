package org.tegro.yamp.provider.local

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Repair of tag text that was decoded as Latin-1 although its bytes are something else
 * (docs/phase-4d-local-files.md §6). Old Russian MP3s write CP1251 into ID3 "Latin-1" frames and ID3v1, so a reader
 * that trusts the spec shows `Ìåëüíèöà` for `Мельница`; some taggers write UTF-8 there (`ÐœÐµÐ»ÑŒÐ½Ð¸Ñ†Ð°`).
 *
 * Only for text that came from a Latin-1 source: never apply it to UTF-8 / UTF-16 frames or Vorbis comments.
 */
object TagText {
    private val CP1251: Charset = Charset.forName("windows-1251")

    /**
     * [text] decoded from a Latin-1 source, repaired when its bytes are valid multi-byte UTF-8 or look like CP1251;
     * otherwise unchanged (true Latin-1 such as `Björk` or `Café` stays as it is).
     */
    fun repair(text: String): String {
        if (text.none { it.code >= HIGH_START }) return text
        if (text.any { it.code > LATIN1_MAX }) return text
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        strictUtf8(bytes)?.let { return it }
        return if (looksLikeCp1251(text)) String(bytes, CP1251) else text
    }

    /**
     * Legacy tag bytes of unknown encoding (ID3v1, Stage 2 cue sheets): UTF-8 when they are valid multi-byte UTF-8,
     * else Latin-1 with [repair].
     */
    fun decode(bytes: ByteArray): String = strictUtf8(bytes) ?: repair(String(bytes, Charsets.ISO_8859_1))

    /** The UTF-8 text of [bytes] when they hold at least one multi-byte sequence and nothing invalid; else null. */
    private fun strictUtf8(bytes: ByteArray): String? {
        if (bytes.none { it < 0 }) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * Whether Latin-1 [text] is CP1251 bytes: C1 control characters (U+0080–U+009F, never in real Latin-1 text but
     * CP1251's `…`, `–`, `“”`, `№`-row) give it away; otherwise the words decide. A word whose letters are all from
     * the upper half (`À`–`ÿ`, `¨`, `¸` = CP1251 `А`–`я`, `Ё`, `ё`) and has two or more of them is a Russian word;
     * a word that mixes ASCII letters with upper-half ones (`Björk`, `Café`) is real Latin-1. More Russian-looking
     * words than mixed ones → CP1251.
     */
    private fun looksLikeCp1251(text: String): Boolean {
        if (text.any { it.code in C1_START..C1_END }) return true
        var cyrillicWords = 0
        var latinWords = 0
        for (word in words(text)) {
            val high = word.count(::isUpperHalfLetter)
            when {
                high == word.length && high >= 2 -> cyrillicWords++
                high > 0 -> latinWords++
            }
        }
        return cyrillicWords > 0 && cyrillicWords >= latinWords
    }

    private fun isUpperHalfLetter(c: Char): Boolean = c.code in UPPER_HALF_START..LATIN1_MAX || c.code == YO_UPPER || c.code == YO_LOWER

    /** The runs of letters (ASCII or upper-half) in [text]. */
    private fun words(text: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        for (c in text + ' ') {
            if (c in 'a'..'z' || c in 'A'..'Z' || isUpperHalfLetter(c)) {
                word.append(c)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
        }
        return words
    }

    private const val HIGH_START = 0x80
    private const val C1_START = 0x80
    private const val C1_END = 0x9F
    private const val UPPER_HALF_START = 0xC0
    private const val LATIN1_MAX = 0xFF
    private const val YO_UPPER = 0xA8
    private const val YO_LOWER = 0xB8
}
