package org.tegro.yamp.data.provider.yandex.wave

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.provider.yandex.queue.RotorStations

/** The wave's fetch (1.15): on from what is queued, at most three asks, and never the same tracks in a circle. */
class WaveQueueTest {

    private fun track(id: String) = TrackDetailsDto(id = JsonPrimitive(id), title = "T$id")

    private fun answer(vararg ids: String, batch: String = "b") = RotorStations.Answer(ids.map(::track), batch)

    /** [answers] by the queue asked with (null — from the station's start); the queues asked are recorded. */
    private fun gather(
        lastQueued: String?,
        queued: Set<String> = emptySet(),
        heard: List<String> = emptyList(),
        answers: Map<String?, RotorStations.Answer?>
    ): Pair<RotorStations.Gathered, List<String?>> {
        val asked = mutableListOf<String?>()
        val recent = RecentlyPlayed(300).apply { restore(heard) }
        val result = runBlocking {
            WaveQueue.gather(lastQueued, queued, recent::age) { queue -> asked += queue; answers[queue] }
        }
        return result to asked
    }

    @Test
    fun theWaveGoesOnFromTheLastQueuedTrackAndStopsAtTheFirstFreshAnswer() {
        val (got, asked) = gather("9", answers = mapOf("9" to answer("1", "2", batch = "B1")))
        assertEquals("one answer was enough", listOf("9"), asked)
        assertEquals(listOf("1", "2"), got.tracks.map { it.idString })
        assertEquals(mapOf("1" to "B1", "2" to "B1"), got.batches)
    }

    @Test
    fun heardLatelyIsLeftOutAndTheStationIsAskedOnThenFromItsStart() {
        val (got, asked) = gather(
            "9", queued = setOf("3"), heard = listOf("1", "2"),
            answers = mapOf("9" to answer("1", "3"), "3" to answer("2"), null to answer("2", "5", batch = "B3"))
        )
        assertEquals("on from the end of the first answer, then from the start", listOf("9", "3", null), asked)
        assertEquals(listOf("5"), got.tracks.map { it.idString })
        assertEquals("B3", got.batches["5"])
        assertEquals("offered: 1, 3, 2, 5", 4, got.offered)
        assertEquals("heard lately among them", 2, got.heard)
    }

    @Test
    fun onlyHeardTracksOnOfferBringTheOnesHeardLongestAgoAFew() {
        val heard = listOf("1", "2", "3", "4", "5") // 1 is the oldest
        val (got, _) = gather("9", heard = heard, answers = mapOf("9" to answer("5", "4"), "4" to answer("3"), null to answer("2", "1")))
        assertEquals(listOf("1", "2", "3"), got.tracks.map { it.idString })
    }

    @Test
    fun aFetchMakesAtMostThreeAsks() {
        val (got, asked) = gather(
            "9", queued = setOf("3"), heard = listOf("1", "2"),
            answers = mapOf("9" to answer("1"), "1" to answer("2"), null to answer("3"))
        )
        assertEquals("last queued, then its end, then the start — and no more", listOf("9", "1", null), asked)
        assertEquals("nothing fresh: the ones heard longest ago, not the queued one", listOf("1", "2"), got.tracks.map { it.idString })
    }

    @Test
    fun anEmptyWaveAsksFromTheStartOnceAndFailuresEndQuietly() {
        val (got, asked) = gather(null, answers = mapOf(null to null))
        assertEquals("from the start, never twice", listOf<String?>(null), asked)
        assertTrue(got.tracks.isEmpty())
        assertEquals(0, got.offered)
    }

    @Test
    fun firstUnheardIsNullWhenEveryTrackWasHeard() {
        val wave = listOf(track("1"), track("2"), track("3"))
        assertNull("all heard: nothing, not «1» again", WaveQueue.firstUnheard(wave) { true })
        assertEquals("2", WaveQueue.firstUnheard(wave) { it == "1" }?.idString)
    }

    @Test
    fun aTrackHeardAgainBecomesFreshAgain() {
        val recent = RecentlyPlayed(3)
        recent.record("a")
        recent.record("b")
        recent.record("c")
        assertEquals(0, recent.age("c"))
        assertEquals(2, recent.age("a"))
        assertEquals("heard again: it moves to the end", listOf("b", "c", "a"), recent.record("a"))
        assertEquals(0, recent.age("a"))
        assertNull("the last one again: nothing to save", recent.record("a"))
        recent.record("d")
        assertFalse("the oldest leaves", "b" in recent)
        assertNull(recent.age("b"))
        assertNull("radio is not music heard", recent.record("radio_dfm"))
        assertEquals(listOf("c", "a", "d"), recent.snapshot())
    }
}
