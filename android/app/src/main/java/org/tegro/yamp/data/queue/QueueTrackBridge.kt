package org.tegro.yamp.data.queue

import kotlinx.serialization.json.Json
import org.tegro.yamp.data.cache.CacheDao
import org.tegro.yamp.data.cache.TrackEntity
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.domain.queue.QueueEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 1 bridge between queue entries (a [org.tegro.yamp.domain.queue.TrackRef] each) and the Yandex track
 * objects the old player still needs: the DTOs live in the `tracks` table keyed by the Yandex id. An entry
 * whose DTO is missing (should not happen; the orphan rule keeps them) gets a stub built from its display
 * columns so the queue still renders and plays what it can. Goes away with Phase 3's neutral `Track`.
 */
interface QueueTrackBridgeApi {
    suspend fun remember(tracks: List<TrackDetailsDto>)
    suspend fun recall(entries: List<QueueEntry>): List<TrackDetailsDto>
}

@Singleton
class QueueTrackBridge @Inject constructor(
    private val dao: CacheDao,
    private val json: Json
) : QueueTrackBridgeApi {
    override suspend fun remember(tracks: List<TrackDetailsDto>) {
        if (tracks.isEmpty()) return
        dao.upsertTracks(tracks.distinctBy { it.idString }.map { TrackEntity(it.idString, json.encodeToString(it)) })
    }

    /** The DTOs of [entries], aligned with them. */
    override suspend fun recall(entries: List<QueueEntry>): List<TrackDetailsDto> {
        if (entries.isEmpty()) return emptyList()
        val byId = entries.map { it.ref.id }.distinct().chunked(900).flatMap { dao.tracksById(it) }
            .associate { it.id to runCatching { json.decodeFromString<TrackDetailsDto>(it.json) }.getOrNull() }
        return entries.map { e -> byId[e.ref.id] ?: stub(e) }
    }

    private fun stub(e: QueueEntry) = TrackDetailsDto(
        id = kotlinx.serialization.json.JsonPrimitive(e.ref.id),
        title = e.title,
        artists = listOf(org.tegro.yamp.data.dto.ArtistDto(name = e.artist)),
        coverUri = e.coverUrl,
        durationMs = e.durationMs
    )
}
