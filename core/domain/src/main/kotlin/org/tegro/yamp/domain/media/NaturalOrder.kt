package org.tegro.yamp.domain.media

/**
 * The order of hand-named folders (Phase 4b): case-insensitive, Latin before Cyrillic before anything else, and runs
 * of digits compared by value ("Disc 2" before "Disc 10"). Ties fall back to the plain text so the order is total.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = digitsEnd(a, i)
                val endB = digitsEnd(b, j)
                val byValue = compareNumbers(a.substring(i, endA), b.substring(j, endB))
                if (byValue != 0) return byValue
                i = endA
                j = endB
                continue
            }
            val byChar = compareChars(ca, cb)
            if (byChar != 0) return byChar
            i++
            j++
        }
        val byLength = (a.length - i).compareTo(b.length - j)
        return if (byLength != 0) byLength else a.compareTo(b)
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isDigit()) k++
        return k
    }

    /** Two digit runs by value, of any length (no overflow): fewer significant digits = smaller. */
    private fun compareNumbers(x: String, y: String): Int {
        val a = x.trimStart('0')
        val b = y.trimStart('0')
        return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
    }

    private fun compareChars(a: Char, b: Char): Int {
        val la = a.lowercaseChar()
        val lb = b.lowercaseChar()
        if (la == lb) return 0
        val ga = group(la)
        val gb = group(lb)
        return if (ga != gb) ga.compareTo(gb) else rank(la).compareTo(rank(lb))
    }

    /** Ё right after Е, as the alphabet has it (its code point is after Я). */
    private fun rank(c: Char): Int = if (c == 'ё') 'е'.code * 2 + 1 else c.code * 2

    /** Spaces and punctuation, digits, Latin, Cyrillic, everything else — in that order. */
    private fun group(c: Char): Int = when {
        c.isDigit() -> 1
        c in 'a'..'z' -> 2
        c == 'ё' -> 3
        Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC -> 3
        c.isLetter() -> 4
        else -> 0
    }
}
