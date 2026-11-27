package org.tegro.yamp.data.provider.local

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.domain.provider.MidTrackAction
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.provider.StreamPrefs
import org.tegro.yamp.domain.provider.StreamResolver
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.provider.local.AudioFormats
import org.tegro.yamp.provider.local.LocalRefs

/**
 * A local track's stream (docs/phase-4d-local-files.md §9): the file itself — `file://` on API ≤ 29, the SAF
 * document's `content://` on API ≥ 30 — always seekable, never in the stream cache or downloaded ([ResolvedStream.localFile],
 * no [ResolvedStream.cacheKey]), no network. The ref is resolved without the index (a path, D95), after the move aliases;
 * the index adds the length. Why it can't play, as the controller acts on it (§4):
 * - [REASON_VOLUME]: the volume is not mounted — skipped during playback, waited for at restore;
 * - [REASON_ACCESS]: no source covers it any more, or the grant is gone;
 * - [REASON_FORMAT]: not a format Stage 1 plays.
 */
class LocalStreamResolver(
    private val index: LocalLibrary,
    private val volumes: VolumeState,
    private val fsFor: (SourceEntity) -> SourceFs?
) : StreamResolver {

    /** From the snapshot in memory, when one is built: no disk (the SAF uri is built, not looked up). */
    override fun cached(track: TrackRef): ResolvedStream? = index.snapshotOrNull()?.let { (streamOf(it, track) as? Outcome.Ok)?.value }

    override suspend fun resolve(track: TrackRef, prefs: StreamPrefs): Outcome<ResolvedStream> = streamOf(index.snapshot(), track)

    override fun invalidate(track: TrackRef) = Unit

    /** A file that stops playing is gone with its volume or broken: never a link to renew. */
    override fun midTrackAction(track: TrackRef, httpCode: Int?): MidTrackAction = MidTrackAction.UNAVAILABLE

    /** Whether [track]'s volume is mounted now (no disk): the controller's "skip, don't wait" test. */
    fun isAvailable(track: TrackRef): Boolean = LocalRefs.path(track.value)?.let { volumes.isMounted(it.volumeId) } ?: false

    private fun streamOf(s: IndexSnapshot, track: TrackRef): Outcome<ResolvedStream> {
        val ref = s.follow(track.value)
        val path = LocalRefs.path(ref) ?: return Outcome.Err(ProviderError.NotFound)
        if (!volumes.isMounted(path.volumeId)) return Outcome.Err(ProviderError.Unavailable(REASON_VOLUME))
        if (!AudioFormats.isPlayable(path.relPath)) return Outcome.Err(ProviderError.Unavailable(REASON_FORMAT))
        val source = s.sources.filter { it.volumeId == path.volumeId && isUnder(path.relPath, it.rootRel) }.maxByOrNull { it.rootRel.length }
            ?: return Outcome.Err(ProviderError.Unavailable(REASON_ACCESS))
        val fs = fsFor(source) ?: return Outcome.Err(ProviderError.Unavailable(REASON_ACCESS))
        val row = s.byRef[ref]
        return Outcome.Ok(ResolvedStream(uri = fs.uri(path.relPath).toString(), localFile = true, durationMs = row?.durationMs?.takeIf { it > 0 }))
    }

    companion object {
        const val REASON_VOLUME = "volume"
        const val REASON_ACCESS = "access"
        const val REASON_FORMAT = "format"
    }
}

/** [org.tegro.yamp.data.player.LocalPlayback] over the mounted volumes and the provider's volume names. */
class VolumeLocalPlayback(
    private val volumes: VolumeMonitor,
    private val provider: LocalProvider,
    private val index: LocalLibrary
) : org.tegro.yamp.data.player.LocalPlayback {
    override fun isAvailable(track: TrackRef): Boolean {
        val path = LocalRefs.path(track.value) ?: return true
        return volumes.isMounted(path.volumeId)
    }

    override fun volumeName(track: TrackRef): String? {
        val path = LocalRefs.path(track.value) ?: return null
        return index.snapshotOrNull()?.let { provider.volumeLabel(it, path.volumeId) } ?: volumes.label(path.volumeId) ?: path.volumeId
    }

    override val changes: kotlinx.coroutines.flow.Flow<Unit> =
        volumes.volumes.map { all -> all.filterValues { it.mounted }.keys }.distinctUntilChanged().map { }
}
