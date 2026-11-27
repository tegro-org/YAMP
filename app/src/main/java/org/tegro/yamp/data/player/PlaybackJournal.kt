package org.tegro.yamp.data.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the music went through in the last hour: stops, crossfades, skips, errors and what was done about
 * them, audio focus, every pause with its reason, how the previous run of the app ended. Kept in memory only;
 * shown in "About → Playback journal" and shared as text with a report, so "it paused by itself" can be told
 * apart: the link, the track, the device or the app. Idea from upstream 1.14.1; the events are ours.
 */
@Singleton
class PlaybackJournal(
    private val now: () -> Long,
    /** Every event is also written to the diagnostic log file, which outlives the process. */
    private val file: org.tegro.yamp.data.diagnostics.DiagnosticLog? = null,
    /** Android's word on how the previous run ended, if it has one (Android 11+); null where it cannot say. */
    private val exitSince: ((Long) -> ProcessExit?)? = null
) {
    @Inject
    constructor(
        file: org.tegro.yamp.data.diagnostics.DiagnosticLog,
        @ApplicationContext context: Context
    ) : this(System::currentTimeMillis, file, ProcessExits(context)::lastSince)

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
        PAUSE,
        /**
         * The headphones or the Bluetooth output went away and the player paused by itself; a fresh output within
         * [OUTPUT_BLIP_MS] plays on (R12).
         */
        OUTPUT_GONE,
        /** The sound card ran out of samples (a click or stutter, not the network); [Event.detail] has the numbers. */
        UNDERRUN,
        /** How long a start took, from the user's skip or the request to the track sounding; [Event.detail]. */
        TIMING,
        /** The audio format of the track that just started sounding (codec, rate, channels, bitrate); [Event.detail]. */
        FORMAT,
        /** A player (re)configured its sound-card output (an AudioTrack: encoding, rate, buffer); [Event.detail]. */
        OUTPUT,
        /** A user action: next / prev / pick; [Event.title] is the track it aimed at (or the current one). */
        TAP,
        /** The device's mixer: native output rate and frames per buffer; [Event.detail]. */
        AUDIO,
        /** The next track's state changed: loading / ready / failed (D80); [Event.detail] says how and why. */
        NEXT,
        /** The on-disk stream cache: a track found, missed or written in full (D86); [Event.detail]. */
        CACHE,
        /** A play report a provider's server didn't take (Jellyfin `/Sessions/Playing*`, step 26). */
        REPORT,
        /** The quality in effect for an account changed: its own choice for the network in use, or the global one. */
        QUALITY,
        /** A smooth pause / play / seek from the crossfade length; [Event.detail] is "pause|play|seek N ms". */
        FADE,
        /** The playing track's position jumped to its start with nobody asking (a stream that broke off); [Event.detail]. */
        JUMP,
        /** MonJaro Audio Router (`com.usbswitch`) took over or released the media output; [Event.detail]. */
        ROUTER,
        /** A Yandex station feedback send and how it went (OK, HTTP code with a scrubbed excerpt, network); [Event.detail]. */
        ROTOR,
        /** Play after the device slept or a long pause: the track opened afresh; [Event.valueMs] the pause. */
        REOPENED_AFTER_REST,
        /** A wave fetch: how many tracks the station offered and how many of them were heard lately; [Event.detail]. */
        WAVE,
        /** The link changed state: [Event.detail] has the states and why (a stall, a silent stream host, a recovery). */
        LINK,
        /** The output took no sound while the track played; [Event.valueMs] how long, [Event.detail] where the sound goes. */
        OUTPUT_STALL,
        /** The position went back without a seek; [Event.valueMs] by how much, [Event.detail] where the sound goes. */
        POSITION_BACK,
        /** Android closed the app while the music played: [Event.detail] its reason, [Event.valueMs] when. */
        KILLED,
        /** The music went back from the device's mix to the streams: a trial that must sound within a few seconds. */
        RETURN_TRY,
        /** The trial stayed silent: back to the device's mix; [Event.valueMs] is the wait before the next try. */
        RETURN_FAILED,
        /** The app refused or stopped playback for a reason: [Event.detail] (`no-plus`: the account has no Yandex Plus, D101). */
        STOP,
        /** A Yandex Plus question and its answer (`asked <account>`, `answered <account>: yes|no|unknown`, D101); the account as a hash. */
        PLUS
    }

    data class Event(val atMs: Long, val kind: Kind, val title: String? = null, val detail: String? = null, val valueMs: Long = 0L)

    data class Summary(
        val playedMs: Long = 0L,
        val stalls: Int = 0,
        val stalledMs: Long = 0L,
        val crossfades: Int = 0,
        val crossfadesLate: Int = 0,
        val errors: Int = 0,
        val pauses: Int = 0,
        /** The output stopped taking sound while the track played, and how long in all. */
        val outputStalls: Int = 0,
        val outputStalledMs: Long = 0L,
        /** The track's time went back with no seek. */
        val positionJumps: Int = 0,
        /** How many times Android ended the app's process while the music played. */
        val kills: Int = 0
    )

    data class Snapshot(val summary: Summary = Summary(), val events: List<Event> = emptyList())

    /** The last pause of the music, while it lasts. */
    data class Pause(val kind: Kind, val atMs: Long) {
        /** The output went away a moment ago and paused it: its coming back brings the music back. Pure. */
        fun isOutputBlip(nowMs: Long): Boolean = kind == Kind.OUTPUT_GONE && nowMs - atMs in 0L..OUTPUT_BLIP_MS
    }

    private val events = ArrayDeque<Event>()
    private val played = ArrayDeque<Pair<Long, Long>>()
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** The last pause of the playing track; [Kind.PAUSE] or [Kind.OUTPUT_GONE]. */
    private var lastPause: Pause? = null

    /** Android's word on the previous run has been asked for: once per run of the app. */
    private var exitChecked = false

    @Synchronized
    fun record(kind: Kind, title: String? = null, detail: String? = null, valueMs: Long = 0L) {
        events.addLast(Event(now(), kind, title, detail, valueMs))
        publish()
        file?.event("PLAYBACK", listOfNotNull(kind.name, detail, title?.let { "«$it»" }, valueMs.takeIf { it > 0 }?.let { "${it} ms" }).joinToString(" "))
    }

    /** The playing track has just paused, for [kind]: the last one tells whether the output went away a moment ago. */
    @Synchronized
    fun onPaused(kind: Kind, title: String? = null, detail: String? = null) {
        lastPause = Pause(kind, now())
        record(kind, title, detail)
    }

    /** What the last pause was, while the music has not played since. */
    @Synchronized
    fun lastPause(): Pause? = lastPause

    /**
     * The music was mid-track when the app's previous run ended: [savedAtMs] is when that was last saved (the position
     * is saved every 15 s while the music plays). If Android's own word on the exit is close behind that save, it is a
     * [Kind.KILLED] line, once per run. Nothing where Android does not say (before Android 11 — the head unit) and
     * nothing when the exit came long after the save (the app was not playing then).
     */
    @Synchronized
    fun checkProcessExit(savedAtMs: Long) {
        if (exitChecked) return
        exitChecked = true
        val exit = runCatching { exitSince?.invoke(savedAtMs) }.getOrNull() ?: return
        if (exit.atMs - savedAtMs > KILLED_WITHIN_MS) return
        record(Kind.KILLED, detail = exit.reason, valueMs = exit.atMs)
    }

    /** The headphones or the Bluetooth went away a moment ago and paused the music: their return plays on. */
    @Synchronized
    fun isOutputBlip(): Boolean = lastPause?.isOutputBlip(now()) == true

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
        lastPause = null
        publish()
    }

    private fun publish() {
        val since = now() - WINDOW_MS
        while (events.isNotEmpty() && events.first().atMs < since) events.removeFirst()
        while (played.isNotEmpty() && played.first().first < since) played.removeFirst()
        val stalls = events.filter { it.kind == Kind.STALL }
        val outputStalls = events.filter { it.kind == Kind.OUTPUT_STALL }
        _state.value = Snapshot(
            Summary(
                playedMs = played.sumOf { it.second },
                stalls = stalls.size,
                stalledMs = stalls.sumOf { it.valueMs },
                crossfades = events.count { it.kind == Kind.CROSSFADE || it.kind == Kind.CROSSFADE_LATE },
                crossfadesLate = events.count { it.kind == Kind.CROSSFADE_LATE },
                errors = events.count { it.kind == Kind.ERROR },
                pauses = events.count { it.kind == Kind.PAUSE || it.kind == Kind.OUTPUT_GONE },
                outputStalls = outputStalls.size,
                outputStalledMs = outputStalls.sumOf { it.valueMs },
                positionJumps = events.count { it.kind == Kind.POSITION_BACK },
                kills = events.count { it.kind == Kind.KILLED }
            ),
            events.takeLast(MAX_KEPT)
        )
    }

    companion object {
        const val WINDOW_MS = 60 * 60_000L
        private const val MAX_KEPT = 200

        /** The headphones or the Bluetooth back within this: the music plays on by itself (R12). */
        const val OUTPUT_BLIP_MS = 15_000L

        /**
         * An exit of the process this long after the position was last saved is not a kill mid-track: while the music
         * plays the position is saved every 15 s, so a kill that found it playing comes sooner.
         */
        const val KILLED_WITHIN_MS = 30_000L
    }
}
