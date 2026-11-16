package org.tegro.yamp.diag

/**
 * Keeps the **newest** lines of a long log within [capBytes] (a ride's end is what matters; the old cap kept the
 * start and cut the rest), drops lines containing any of [dropContaining] (counted), and folds runs of the same
 * message into one line with a repeat count. Fed line by line, so the whole log never sits in memory.
 */
class LogTail(
    private val capBytes: Int,
    private val dropContaining: List<String> = emptyList(),
) {
    private val kept = ArrayDeque<String>()
    private var keptBytes = 0L
    private var evicted = 0
    private val dropped = LinkedHashMap<String, Int>()

    private var lastKey: String? = null
    private var repeats = 0

    fun accept(line: String) {
        dropContaining.firstOrNull { line.contains(it) }?.let { pattern ->
            dropped[pattern] = (dropped[pattern] ?: 0) + 1
            return
        }
        val key = messageOf(line)
        if (key == lastKey) {
            repeats++
            return
        }
        flushRepeats()
        lastKey = key
        push(line)
    }

    /** The kept lines, with a header saying what was left out. */
    fun result(): String {
        flushRepeats()
        val header = buildList {
            if (evicted > 0) add("[YAMP Diag: $evicted older lines left out to keep the newest]")
            dropped.forEach { (pattern, n) -> add("[YAMP Diag: $n lines containing \"$pattern\" left out]") }
        }
        return (header + kept).joinToString("\n", postfix = "\n")
    }

    private fun flushRepeats() {
        if (repeats > 0) push("    … the same message repeated $repeats more times")
        repeats = 0
    }

    private fun push(line: String) {
        kept.addLast(line)
        keptBytes += line.utf8Size() + 1
        while (keptBytes > capBytes && kept.size > 1) {
            keptBytes -= kept.removeFirst().utf8Size() + 1
            evicted++
        }
    }

    private fun String.utf8Size(): Int = encodeToByteArray().size

    companion object {
        /** `MM-dd HH:mm:ss.SSS  PID  TID L ` — logcat's threadtime prefix; the rest is the tag and the message. */
        private val THREADTIME_PREFIX = Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+\S\s+""")

        fun messageOf(line: String): String = line.replaceFirst(THREADTIME_PREFIX, "")
    }
}
