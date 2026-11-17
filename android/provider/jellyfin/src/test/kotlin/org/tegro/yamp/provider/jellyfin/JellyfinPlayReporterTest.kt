package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.provider.Outcome
import java.io.IOException

class JellyfinPlayReporterTest {

    private class Recording(private val fail: Boolean = false) : FakeJellyfinApi() {
        val calls = mutableListOf<Pair<String, PlaybackReportDto>>()
        private fun record(kind: String, body: PlaybackReportDto) {
            if (fail) throw IOException("server away")
            calls += kind to body
        }
        override suspend fun reportPlaying(body: PlaybackReportDto) = record("playing", body)
        override suspend fun reportProgress(body: PlaybackReportDto) = record("progress", body)
        override suspend fun reportStopped(body: PlaybackReportDto) = record("stopped", body)
    }

    @Test
    fun positionsAreSentInTicksWithThePlaySession() = runBlocking {
        val api = Recording()
        val reporter = JellyfinPlayReporter(api)
        reporter.started("item1", 0, "s1")
        reporter.progress("item1", 12_500, paused = true, "s1")
        reporter.stopped("item1", 30_000, "s1")
        assertEquals(listOf("playing", "progress", "stopped"), api.calls.map { it.first })
        val progress = api.calls[1].second
        assertEquals(125_000_000L, progress.positionTicks)
        assertTrue(progress.isPaused)
        assertEquals("s1", progress.playSessionId)
        assertEquals("item1", api.calls[2].second.itemId)
        assertEquals("TimeUpdate", progress.eventName)
        assertEquals("DirectStream", progress.playMethod)
    }

    @Test
    fun aFailingServerIsAnOutcomeNotAnException() = runBlocking {
        val reporter = JellyfinPlayReporter(Recording(fail = true))
        assertTrue(reporter.started("item1", 0, "s1") is Outcome.Err)
        assertTrue(reporter.stopped("item1", 1_000, "s1") is Outcome.Err)
    }
}
