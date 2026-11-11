package org.tegro.yamp.data.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the music went through in the last hour: stops, crossfades, skips, errors and what was done about
 * them, audio focus, every pause with its reason. Kept in memory only; shown in "About → Playback journal"
 * and shared as text with a report, so "it paused by itself" can be told apart: the link, the track, the
 * device or the app. Idea from upstream 1.14.1; the events are ours.
 */
@Singleton
class PlaybackJournal(
    private val now: () -> Long,
    /** Every event is also written to the diagnostic log file, which outlives the process. */
    private val file: org.tegro.yamp.data.diagnostics.DiagnosticLog? = null
) {
    @Inject
    constructor(file: org.tegro.yamp.data.diagnostics.DiagnosticLog) : this(System::currentTimeMillis, file)

    enum class Kind {
        /** A track started sounding (or was asked to). */
        START,
        /** The music ran dry mid-track; [Event.valueMs] is how long. */
        STALL,
        /** A crossfade with the next track ready in time. */
        CROSSFADE,
        /** A crossfade where the next track came late and faded in after the previous one. */
        CROSSFADE_LATE,
        /** The next track did not get ready in time: the hold was given up. */
        HOLD_TIMEOUT,
        /** The user skipped; the current track faded out. */
        SKIP,
        /** The player or the stream failed; [Event.detail] says what and what was done. */
        ERROR,
        /** Audio focus: lost, regained, denied, ducked. */
        FOCUS,
        /** Playback paused; [Event.detail] is who asked. */
        PAUSE
    }

    data class Event(val atMs: Long, val kind: Kind, val title: String? = null, val detail: String? = null, val valueMs: Long = 0L)

    data class Summary(
        val playedMs: Long = 0L,
        val stalls: Int = 0,
        val stalledMs: Long = 0L,
        val crossfades: Int = 0,
        val crossfadesLate: Int = 0,
        val errors: Int = 0,
        val pauses: Int = 0
    )

    data class Snapshot(val summary: Summary = Summary(), val events: List<Event> = emptyList())

    private val events = ArrayDeque<Event>()
    private val played = ArrayDeque<Pair<Long, Long>>()
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    @Synchronized
    fun record(kind: Kind, title: String? = null, detail: String? = null, valueMs: Long = 0L) {
        events.addLast(Event(now(), kind, title, detail, valueMs))
        publish()
        file?.event("PLAYBACK", listOfNotNull(kind.name, detail, title?.let { "«$it»" }, valueMs.takeIf { it > 0 }?.let { "${it} ms" }).joinToString(" "))
    }

    /** [ms] more of music heard. */
    @Synchronized
    fun addPlayed(ms: Long) {
        if (ms <= 0L) return
        played.addLast(now() to ms)
        publish()
    }

    @Synchronized
    fun clear() {
        events.clear()
        played.clear()
        publish()
    }

    private fun publish() {
        val since = now() - WINDOW_MS
        while (events.isNotEmpty() && events.first().atMs < since) events.removeFirst()
        while (played.isNotEmpty() && played.first().first < since) played.removeFirst()
        val stalls = events.filter { it.kind == Kind.STALL }
        _state.value = Snapshot(
            Summary(
                playedMs = played.sumOf { it.second },
                stalls = stalls.size,
                stalledMs = stalls.sumOf { it.valueMs },
                crossfades = events.count { it.kind == Kind.CROSSFADE || it.kind == Kind.CROSSFADE_LATE },
                crossfadesLate = events.count { it.kind == Kind.CROSSFADE_LATE },
                errors = events.count { it.kind == Kind.ERROR },
                pauses = events.count { it.kind == Kind.PAUSE }
            ),
            events.takeLast(MAX_KEPT)
        )
    }

    companion object {
        const val WINDOW_MS = 60 * 60_000L
        private const val MAX_KEPT = 200
    }
}
