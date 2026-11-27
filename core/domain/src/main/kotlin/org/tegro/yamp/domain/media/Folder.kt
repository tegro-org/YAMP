package org.tegro.yamp.domain.media

import org.tegro.yamp.domain.queue.MediaRef

/**
 * A folder of a library browsed by its file tree (Phase 4b, docs/multi-source-player.md §Phase 4b). [childCount]:
 * items right inside it (folders and tracks), [trackCount]: tracks anywhere under it — null when the service didn't
 * say. [cover] is the folder's own image (or the album's, for an album folder); none → the drawn placeholder.
 * [available] false: its storage is not connected (a local folder on an absent USB stick, D96) — listed, dimmed, not
 * playable.
 */
data class Folder(
    val ref: MediaRef,
    val title: String,
    val cover: Cover?,
    val childCount: Int? = null,
    val trackCount: Int? = null,
    val available: Boolean = true
)

/** One level of the tree: its subfolders in [NaturalOrder], then the loose tracks in disc / track order. */
data class FolderLevel(val folders: List<Folder>, val tracks: List<Track>)
