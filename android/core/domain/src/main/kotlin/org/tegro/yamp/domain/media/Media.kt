package org.tegro.yamp.domain.media

import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef

/*
 * The neutral models every provider maps its own data to (docs/phase-3-providers.md §1, C2). They carry only
 * what screens and the player use; fields are added while screens migrate, not speculatively. Refs say which
 * provider (and, for Jellyfin, which server) an item belongs to.
 */

/** A cover image at whatever size a screen needs: the provider knows how to ask its server for it. */
interface Cover {
    /** The same for every size of one image: the image cache keys by it. */
    val key: String

    /** A loadable URL for the image about [sizePx] pixels wide. */
    fun url(sizePx: Int): String
}

/** An artist as a track or album names it. [ref] is null when the provider gives no identity (a guest name). */
data class ArtistBrief(val ref: MediaRef?, val name: String)

/** The album a track belongs to, as the track names it. */
data class AlbumBrief(val ref: MediaRef?, val title: String?)

/** Loudness the provider measured (EBU R128): integrated loudness in LUFS, true peak in dBTP. */
data class Loudness(val integratedLufs: Double?, val truePeakDbtp: Double?)

/** One playable track. [title] is empty when the provider gave none. */
data class Track(
    val ref: TrackRef,
    val title: String,
    val artists: List<ArtistBrief>,
    val album: AlbumBrief?,
    val durationMs: Long?,
    val cover: Cover?,
    val explicit: Boolean,
    val available: Boolean = true,
    val loudness: Loudness? = null
)

data class Artist(val ref: MediaRef, val name: String, val cover: Cover?)

data class Album(
    val ref: MediaRef,
    val title: String,
    val artists: List<ArtistBrief>,
    val cover: Cover?,
    val year: Int?,
    val trackCount: Int,
    /** In play order, discs one after another; empty when the list did not include them. */
    val tracks: List<Track> = emptyList()
)

data class Playlist(
    val ref: MediaRef,
    val title: String,
    val trackCount: Int,
    val cover: Cover?,
    val isPublic: Boolean,
    /** Empty when the list did not include them. */
    val tracks: List<Track> = emptyList()
)

/** A genre of a library that browses by genre (Jellyfin); its albums are listed through [org.tegro.yamp.domain.provider.Browse]. */
data class Genre(val ref: MediaRef, val name: String, val cover: Cover?)
