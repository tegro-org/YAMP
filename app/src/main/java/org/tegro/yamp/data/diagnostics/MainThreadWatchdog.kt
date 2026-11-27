package org.tegro.yamp.data.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.domain.repository.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the main thread's stack to the diagnostic log when the thread is blocked (docs/anr-watchdog.md). While
 * the setting is on, a daemon thread posts a tick to the main looper every second; [StallDetector] turns the
 * time since the last answer into WATCHDOG lines. Off: no thread, no posts; the setting is followed live.
 */
@Singleton
class MainThreadWatchdog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val diagnosticLog: DiagnosticLog
) {
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null

    fun start() {
        if (BuildConfig.DEBUG) registerDebugHang()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settings.anrWatchdog.distinctUntilChanged().collect { on -> if (on) startThread() else stopThread() }
        }
    }

    /** Debug builds only, for testing the watchdog: `adb shell am broadcast -a $ACTION_DEBUG_HANG --ei seconds 8 -p <app id>`. */
    private fun registerDebugHang() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_HANG_SECONDS).coerceIn(1, MAX_HANG_SECONDS)
                Thread.sleep(seconds * 1000L)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_DEBUG_HANG), ContextCompat.RECEIVER_EXPORTED)
    }

    @Synchronized
    private fun startThread() {
        if (thread?.isAlive == true) return
        thread = Thread(::watch, "yamp-watchdog").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun stopThread() {
        thread?.interrupt()
        thread = null
    }

    private fun watch() {
        val detector = StallDetector()
        val pending = AtomicBoolean(false)
        val answeredAt = AtomicLong(SystemClock.uptimeMillis())
        val tick = Runnable {
            answeredAt.set(SystemClock.uptimeMillis())
            pending.set(false)
        }
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (pending.compareAndSet(false, true)) main.post(tick)
                Thread.sleep(TICK_MS)
                val event = detector.update(SystemClock.uptimeMillis(), answeredAt.get()) ?: continue
                diagnosticLog.event("WATCHDOG", message(event))
            }
        } catch (_: InterruptedException) {
            // Switched off
        } finally {
            main.removeCallbacks(tick)
        }
    }

    private fun message(event: StallDetector.Event): String = when (event) {
        is StallDetector.Event.Recovered -> "main thread recovered after ${event.seconds} s"
        else -> "main thread blocked ${event.seconds} s: " + formatStack(Looper.getMainLooper().thread.stackTrace)
    }

    companion object {
        private const val TICK_MS = 1_000L
        const val MAX_FRAMES = 25
        const val ACTION_DEBUG_HANG = "org.tegro.music.yamp.DEBUG_HANG"
        private const val EXTRA_SECONDS = "seconds"
        private const val DEFAULT_HANG_SECONDS = 8
        private const val MAX_HANG_SECONDS = 30

        /** The top [MAX_FRAMES] frames, innermost first, on one line: "a.B.c(B.kt:1) ← d.E.f(E.kt:2) ← … (+N more)". */
        fun formatStack(frames: Array<StackTraceElement>): String {
            val shown = frames.take(MAX_FRAMES).joinToString(" ← ")
            val more = frames.size - MAX_FRAMES
            return if (more > 0) "$shown ← … (+$more more)" else shown
        }
    }
}
