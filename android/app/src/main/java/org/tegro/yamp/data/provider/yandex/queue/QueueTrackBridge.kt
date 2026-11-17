package org.tegro.yamp.data.provider.yandex.queue

import kotlinx.serialization.json.Json
import org.tegro.yamp.data.cache.CacheDao
import org.tegro.yamp.data.cache.TrackEntity
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.provider.yandex.toTrack
import org.tegro.yamp.data.provider.yandex.toYandexTrack
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.QueueEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The tracks behind queue entries (a [org.tegro.yamp.domain.queue.TrackRef] each). They are stored in the `tracks`
 * table keyed by the Yandex id in the service's own shape (the rows predate phase 3; no migration, D72) and come
 * back as neutral [Track]s. An entry whose row is missing (should not happen; the orphan rule keeps them) gets a
 * stub built from its display columns so the queue still renders and plays what it can.
 */
interface QueueTrackBridgeApi {
    suspend fun remember(tracks: List<Track>)
    suspend fun recall(entries: List<QueueEntry>): List<Track>
}

@Singleton
class QueueTrackBridge @Inject constructor(
    private val dao: CacheDao,
    private val json: Json
) : QueueTrackBridgeApi {
    override suspend fun remember(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        dao.upsertTracks(tracks.distinctBy { it.ref.id }.map { TrackEntity(it.ref.id, json.encodeToString(it.toYandexTrack())) })
    }

    /** The tracks of [entries], aligned with them. */
    override suspend fun recall(entries: List<QueueEntry>): List<Track> {
        if (entries.isEmpty()) return emptyList()
        val byId = entries.map { it.ref.id }.distinct().chunked(900).flatMap { dao.tracksById(it) }
            .associate { it.id to runCatching { json.decodeFromString<TrackDetailsDto>(it.json).toTrack() }.getOrNull() }
        return entries.map { e -> byId[e.ref.id] ?: stub(e) }
    }

    private fun stub(e: QueueEntry) = TrackDetailsDto(
        id = kotlinx.serialization.json.JsonPrimitive(e.ref.id),
        title = e.title,
        artists = listOf(org.tegro.yamp.data.dto.ArtistDto(name = e.artist)),
        coverUri = e.coverUrl,
        durationMs = e.durationMs
    ).toTrack()
}
