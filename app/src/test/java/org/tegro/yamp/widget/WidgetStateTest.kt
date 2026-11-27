package org.tegro.yamp.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WidgetStateTest {

    private val playing = WidgetNow("A", "B", true, false, false, false, false, 0.2f, 1, 5, null, null, null)
    private val paused = playing.copy(isPlaying = false)

    @Test
    fun `a widget that drew an older state, never drew, or whose draw failed after its composition is stale`() {
        val ledger = RenderLedger()
        ledger.drew(1, playing)
        ledger.drew(2, paused)
        ledger.drew(4, playing)
        ledger.failed(4)
        assertEquals(listOf(2, 3, 4), ledger.staleAmong(listOf(1, 2, 3, 4), playing))
        ledger.drew(4, playing)
        assertEquals(listOf(2, 3), ledger.staleAmong(listOf(1, 2, 3, 4), playing))
    }

    @Test
    fun `a widget is asked again at most a few times for one state, a tick is not a new state`() {
        val budget = RetryBudget(3)
        val key = widgetSyncKey(playing)
        assertTrue(budget.take(key) && budget.take(key) && budget.take(key))
        assertFalse(budget.take(key))
        // 30 s later: the position moved, the state did not
        assertFalse(budget.take(widgetSyncKey(playing.copy(positionMs = 31_000, progress = 0.4f))))
        assertTrue(budget.take(widgetSyncKey(paused)))
    }

    @Test
    fun `a press is meant for the track the widget showed`() {
        assertTrue(widgetReactionTargets("yandex:1", "yandex:1"))
        assertFalse(widgetReactionTargets("yandex:1", "yandex:2"))
        assertFalse(widgetReactionTargets("yandex:1", null))
        assertTrue(widgetReactionTargets(null, "yandex:2"))
    }

    @Test
    fun `a stuck widget update times out and the others still run`() = runBlocking {
        val done = mutableListOf<Int>()
        val errors = mutableListOf<Int>()
        withTimeout(5_000) {
            updateTogether(listOf(1, 2), timeoutMs = 100, onError = { id, _ -> synchronized(errors) { errors += id } }) { id ->
                if (id == 1) delay(60_000)
                synchronized(done) { done += id }
            }
        }
        assertEquals(listOf(2), done)
        assertEquals(listOf(1), errors)
    }

    @Test
    fun `one failing widget does not stop the others, and they all run at the same time`() = runBlocking {
        val allStarted = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val done = mutableListOf<Int>()
        val errors = mutableListOf<Int>()
        withTimeout(5_000) {
            updateTogether(listOf(1, 2, 3), onError = { id, _ -> synchronized(errors) { errors += id } }) { id ->
                // A sequential run would wait here forever for the others to start
                if (started.incrementAndGet() == 3) allStarted.complete(Unit)
                allStarted.await()
                if (id == 2) error("boom")
                synchronized(done) { done += id }
            }
        }
        assertEquals(listOf(1, 3), done.sorted())
        assertEquals(listOf(2), errors)
    }

    @Test
    fun `a cancellation is passed on, not reported as a failed widget`() = runBlocking {
        val errors = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            updateTogether(listOf(1), onError = { _, _ -> errors.incrementAndGet() }) {
                entered.complete(Unit)
                delay(60_000)
            }
        }
        entered.await()
        job.cancel(CancellationException("next round"))
        job.join()
        assertEquals(0, errors.get())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `the cover of a track is loaded once however many widgets ask at once`() = runBlocking {
        val loads = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val cache = SingleFlightCache<String, String>(2, scope, { it.isNotEmpty() }) { key ->
            loads.incrementAndGet()
            gate.await()
            "art-$key"
        }
        val asks = List(3) { scope.async { cache.get("k") } }
        gate.complete(Unit)
        assertEquals(listOf("art-k", "art-k", "art-k"), asks.awaitAll())
        cache.get("k")
        assertEquals(1, loads.get())
        scope.cancel()
    }

    @Test
    fun `a cover load nobody waits for is cancelled, one somebody still waits for goes on`() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val cache = SingleFlightCache<String, String>(3, scope, { true }) { key ->
            started.complete(Unit)
            try {
                delay(60_000)
                "art-$key"
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        }
        val first = scope.launch { cache.get("k") }
        val second = scope.launch { cache.get("k") }
        started.await()
        first.cancelAndJoin()
        delay(100)
        assertFalse(cancelled.isCompleted)
        second.cancelAndJoin()
        withTimeout(5_000) { cancelled.await() }
        scope.cancel()
    }

    @Test
    fun `the oldest cover is dropped past the capacity and a failed load is tried again`() = runBlocking {
        val loads = AtomicInteger()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val cache = SingleFlightCache<String, String>(2, scope, { it.isNotEmpty() }) { key ->
            loads.incrementAndGet()
            if (key == "bad") "" else "art-$key"
        }
        cache.get("a")
        cache.get("b")
        cache.get("c")
        assertEquals(3, loads.get())
        cache.get("b")
        cache.get("c")
        assertEquals(3, loads.get())
        cache.get("a")
        assertEquals(4, loads.get())
        cache.get("bad")
        cache.get("bad")
        assertEquals(6, loads.get())
        scope.cancel()
    }
}
