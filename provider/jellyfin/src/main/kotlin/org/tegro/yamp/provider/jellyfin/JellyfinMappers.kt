package org.tegro.yamp.provider.jellyfin

import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.AlbumBrief
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Cover
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.Genre
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef

/**
 * Refs for one Jellyfin server (D78): `jellyfin@<serverId>:<kind>:<itemId>` — item ids are unique per server,
 * shared by its users, so the ref names the server and not the account.
 */
class JellyfinRefs(val serverId: String) {
    val provider: String = PROVIDER + MediaRef.INSTANCE_SEPARATOR + serverId

    fun ref(kind: String, itemId: String) = MediaRef(provider, kind, itemId)
    fun track(itemId: String) = TrackRef.of(provider, itemId)

    companion object {
        const val PROVIDER = "jellyfin"
        const val KIND_ALBUM = "album"
        const val KIND_ARTIST = "artist"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_GENRE = "genre"
        const val KIND_FOLDER = "folder"
    }
}

/**
 * A primary image of one item (D79): `Items/{id}/Images/Primary` sized by the server, with the image tag so a
 * changed cover gets a new URL (and a new cache key). [baseUrl] ends with `/`.
 */
data class JellyfinCover(val baseUrl: String, val itemId: String, val tag: String) : Cover {
    /**
     * The URL with a [SIZE] placeholder (step 23): the same for every size, so the image cache keys by it, and the
     * app's cover helper fills in the size it loads.
     */
    override val key: String get() = "${baseUrl}Items/$itemId/Images/Primary?fillWidth=$SIZE&fillHeight=$SIZE&quality=90&tag=$tag"
    override fun url(sizePx: Int): String = key.replace(SIZE, sizePx.toString())

    companion object {
        /** Stands for the pixel size in [key]. */
        const val SIZE = "{px}"
    }
}

/** Maps Jellyfin items of one server to the neutral models. [baseUrl] is the server's URL ending with `/`. */
class JellyfinMappers(private val refs: JellyfinRefs, private val baseUrl: String) {

    fun track(item: BaseItemDto): Track = Track(
        ref = refs.track(item.id),
        title = item.name.orEmpty(),
        artists = artists(item.artistItems.ifEmpty { item.albumArtists }),
        album = item.album?.let { AlbumBrief(item.albumId?.let { id -> refs.ref(JellyfinRefs.KIND_ALBUM, id) }, it) },
        durationMs = item.runTimeTicks?.let { it / TICKS_PER_MS },
        cover = cover(item) ?: item.albumId?.let { id -> item.albumPrimaryImageTag?.let { JellyfinCover(baseUrl, id, it) } },
        explicit = false
    )

    fun album(item: BaseItemDto, tracks: List<BaseItemDto> = emptyList()): Album = Album(
        ref = refs.ref(JellyfinRefs.KIND_ALBUM, item.id),
        title = item.name.orEmpty(),
        artists = artists(item.albumArtists.ifEmpty { item.artistItems }),
        cover = cover(item),
        year = item.productionYear,
        trackCount = item.childCount ?: tracks.size,
        tracks = tracks.sortedWith(compareBy({ it.parentIndexNumber ?: 1 }, { it.indexNumber ?: Int.MAX_VALUE })).map(::track)
    )

    fun artist(item: BaseItemDto): Artist =
        Artist(ref = refs.ref(JellyfinRefs.KIND_ARTIST, item.id), name = item.name.orEmpty(), cover = cover(item))

    fun playlist(item: BaseItemDto, tracks: List<BaseItemDto> = emptyList()): Playlist = Playlist(
        ref = refs.ref(JellyfinRefs.KIND_PLAYLIST, item.id),
        title = item.name.orEmpty(),
        trackCount = item.childCount ?: tracks.size,
        cover = cover(item),
        isPublic = false,
        tracks = tracks.map(::track)
    )

    /** A folder of the file tree (Phase 4b): any item that is a folder — a plain folder, an artist's, an album's. */
    fun folder(item: BaseItemDto): Folder = Folder(
        ref = refs.ref(JellyfinRefs.KIND_FOLDER, item.id),
        title = item.name.orEmpty(),
        cover = cover(item),
        childCount = item.childCount,
        trackCount = item.recursiveItemCount
    )

    fun genre(item: BaseItemDto): Genre =
        Genre(ref = refs.ref(JellyfinRefs.KIND_GENRE, item.id), name = item.name.orEmpty(), cover = cover(item))

    private fun artists(list: List<NameIdPairDto>): List<ArtistBrief> = list.mapNotNull { pair ->
        pair.name?.takeIf { it.isNotBlank() }?.let { ArtistBrief(pair.id?.let { id -> refs.ref(JellyfinRefs.KIND_ARTIST, id) }, it) }
    }

    private fun cover(item: BaseItemDto): Cover? = item.imageTags["Primary"]?.let { JellyfinCover(baseUrl, item.id, it) }

    private companion object {
        /** Jellyfin durations are .NET ticks: 10 000 per millisecond. */
        const val TICKS_PER_MS = 10_000L
    }
}
