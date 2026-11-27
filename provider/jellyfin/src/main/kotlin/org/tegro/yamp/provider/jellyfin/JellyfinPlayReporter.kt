package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.CancellationException
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError

/**
 * Tells one account's server what is playing (docs/phase-3-providers.md step 26): started, progress (periodic, on
 * pause / resume / seek) and stopped, so the server's "now playing", play counts and resume positions follow YAMP.
 * Each call answers an [Outcome] and never throws — a report that fails must not touch playback.
 */
class JellyfinPlayReporter(private val api: JellyfinApi) {

    suspend fun started(itemId: String, positionMs: Long, playSessionId: String): Outcome<Unit> =
        send { api.reportPlaying(body(itemId, positionMs, paused = false, playSessionId, event = null)) }

    suspend fun progress(itemId: String, positionMs: Long, paused: Boolean, playSessionId: String, event: Event = Event.TIME_UPDATE): Outcome<Unit> =
        send { api.reportProgress(body(itemId, positionMs, paused, playSessionId, event)) }

    suspend fun stopped(itemId: String, positionMs: Long, playSessionId: String): Outcome<Unit> =
        send { api.reportStopped(body(itemId, positionMs, paused = false, playSessionId, event = null)) }

    /** What a progress report is about. */
    enum class Event(val wire: String) { TIME_UPDATE("TimeUpdate"), PAUSE("Pause"), UNPAUSE("Unpause") }

    private fun body(itemId: String, positionMs: Long, paused: Boolean, playSessionId: String, event: Event?) = PlaybackReportDto(
        itemId = itemId,
        positionTicks = positionMs.coerceAtLeast(0L) * TICKS_PER_MS,
        isPaused = paused,
        canSeek = true,
        // YAMP plays the file as the server sends it (universal endpoint, D79)
        playMethod = "DirectStream",
        eventName = event?.wire,
        playSessionId = playSessionId
    )

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any failure of a fire-and-forget report becomes an Outcome
    private suspend fun send(call: suspend () -> Unit): Outcome<Unit> = try {
        call()
        Outcome.Ok(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Err(ProviderError.Network)
    }

    companion object {
        const val TICKS_PER_MS = 10_000L
    }
}
