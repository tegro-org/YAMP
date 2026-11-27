package org.tegro.yamp.data.player.fakes

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestDispatcher

/**
 * The main thread of a test, as the controller expects it: one thread for all its coroutines. On the test
 * thread a coroutine runs at once, as `Dispatchers.Main.immediate` does on the main thread; one resumed on
 * another thread (after an IO hop in a store, or Room's own threads) comes back to the test thread and runs
 * while the test waits in [await].
 *
 * An unconfined dispatcher would go on running such a coroutine on that other thread instead, beside the test:
 * a queue operation resumed there finishes before the jobs it launched (the play, the standby) have run, so the
 * test saw the operation done and the engine calls not yet made — or made after it cleared them.
 *
 * Delays keep [time]'s virtual clock: they wait until the test moves it, as before.
 */
@OptIn(InternalCoroutinesApi::class)
class TestThreadMain(private val time: TestDispatcher) : MainCoroutineDispatcher(), Delay by time {
    @Volatile
    private var thread: Thread = Thread.currentThread()
    private val tasks = LinkedBlockingQueue<Runnable>()

    /** The thread the test runs on; called from the test's set-up. */
    fun bindToCurrentThread() {
        thread = Thread.currentThread()
    }

    override val immediate: MainCoroutineDispatcher get() = this

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = Thread.currentThread() !== thread

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.put(block)
    }

    /** The same thread for the test's own waiting in [await], whose delays are real time (a poll of a request). */
    private val realTime = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = this@TestThreadMain.isDispatchNeeded(context)
        override fun dispatch(context: CoroutineContext, block: Runnable) = this@TestThreadMain.dispatch(context, block)
    }

    /**
     * Runs [block] on the test thread, running what comes back to it meanwhile, until [block] is done; then what
     * has arrived by then. Fails after [timeoutMs].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> await(timeoutMs: Long = 5_000, block: suspend () -> T): T {
        check(Thread.currentThread() === thread) { "await off the test thread" }
        val result = CoroutineScope(realTime).async(start = CoroutineStart.UNDISPATCHED) { block() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!result.isCompleted) {
            check(System.nanoTime() < deadline) { "Timed out after $timeoutMs ms" }
            tasks.poll(10, TimeUnit.MILLISECONDS)?.run()
        }
        while (true) tasks.poll()?.run() ?: break
        return result.getCompleted()
    }
}
