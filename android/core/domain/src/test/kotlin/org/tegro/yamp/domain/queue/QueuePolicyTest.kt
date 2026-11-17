package org.tegro.yamp.domain.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.queue.QueuePolicy.Advance
import kotlin.random.Random

class QueuePolicyTest {
    private fun entry(id: Long, original: Int) =
        QueueEntry(entryId = id, queueId = 1L, position = original, originalPosition = original, ref = TrackRef.yandex("$id"), title = "t$id", artist = "a")

    @Test
    fun nextMovesOnAndRepeatAllWrapsToTheStart() {
        assertEquals(Advance.To(1), QueuePolicy.next(0, 3, QueueKind.STATIC, RepeatMode.ALL, manual = false))
        assertEquals(Advance.To(0), QueuePolicy.next(2, 3, QueueKind.STATIC, RepeatMode.ALL, manual = false))
    }

    @Test
    fun repeatOffEndsAtTheLastTrack() {
        assertEquals(Advance.End, QueuePolicy.next(2, 3, QueueKind.STATIC, RepeatMode.OFF, manual = false))
        assertEquals(Advance.To(1), QueuePolicy.next(0, 3, QueueKind.STATIC, RepeatMode.OFF, manual = false))
    }

    @Test
    fun repeatOneRepeatsOnlyWhenTheTrackEndedByItself() {
        assertEquals(Advance.To(1), QueuePolicy.next(1, 3, QueueKind.STATIC, RepeatMode.ONE, manual = false))
        assertEquals(Advance.To(2), QueuePolicy.next(1, 3, QueueKind.STATIC, RepeatMode.ONE, manual = true))
        assertEquals("a press at the end wraps", Advance.To(0), QueuePolicy.next(2, 3, QueueKind.STATIC, RepeatMode.ONE, manual = true))
    }

    @Test
    fun aDynamicQueueAsksForMoreAtItsEnd() {
        assertEquals(Advance.FetchMore, QueuePolicy.next(2, 3, QueueKind.WAVE, RepeatMode.ALL, manual = true))
        assertEquals(Advance.To(1), QueuePolicy.next(0, 3, QueueKind.RADIO, RepeatMode.ALL, manual = false))
    }

    @Test
    fun skippedEntriesArePassedOverAndAQueueOfOnlySkippedOnesEnds() {
        assertEquals(Advance.To(2), QueuePolicy.next(0, 3, QueueKind.STATIC, RepeatMode.ALL, manual = false) { it == 1 })
        assertEquals(Advance.End, QueuePolicy.next(0, 3, QueueKind.STATIC, RepeatMode.ALL, manual = false) { true })
        assertEquals("wrapping skips too", Advance.To(1), QueuePolicy.next(2, 3, QueueKind.STATIC, RepeatMode.ALL, manual = false) { it == 0 })
    }

    @Test
    fun previousGoesBackAndIsNullAtTheStart() {
        assertEquals(1, QueuePolicy.previous(2))
        assertNull(QueuePolicy.previous(0))
        assertEquals(0, QueuePolicy.previous(2) { it == 1 })
    }

    @Test
    fun shuffleKeepsTheCursorFirstAndUnshuffleRestoresInsertionOrder() {
        val entries = listOf(entry(10, 0), entry(11, 1), entry(12, 2), entry(13, 3))
        val order = QueuePolicy.shuffled(entries, cursorEntryId = 12L, random = Random(7))
        assertEquals(12L, order.first())
        assertEquals(setOf(10L, 11L, 13L), order.drop(1).toSet())
        val shuffledEntries = order.mapIndexed { i, id -> entries.first { it.entryId == id }.copy(position = i) }
        assertEquals(listOf(10L, 11L, 12L, 13L), QueuePolicy.unshuffled(shuffledEntries))
    }

    /** D52: a radio without Keep drops what is more than RADIO_BEHIND tracks behind the cursor; with Keep, nothing. */
    @Test
    fun aRadioWithoutKeepDropsItsPlayedHeadAndWithKeepDropsNothing() {
        val entries = (0..9).map { entry(10L + it, it) }
        assertEquals(listOf(10L, 11L, 12L), QueuePolicy.radioTrim(entries, cursorIndex = 6, keepHistory = false))
        assertEquals(emptyList<Long>(), QueuePolicy.radioTrim(entries, cursorIndex = 3, keepHistory = false))
        assertEquals(emptyList<Long>(), QueuePolicy.radioTrim(entries, cursorIndex = -1, keepHistory = false))
        assertEquals(emptyList<Long>(), QueuePolicy.radioTrim(entries, cursorIndex = 9, keepHistory = true))
    }

    @Test
    fun aShuffledInsertLandsAfterTheCursor() {
        repeat(20) {
            val p = QueuePolicy.randomInsertPosition(cursorIndex = 2, size = 6, random = Random(it))
            assertTrue("position $p", p in 3..6)
        }
        assertEquals("nothing after the cursor: at the end", 3, QueuePolicy.randomInsertPosition(2, 3))
    }
}
