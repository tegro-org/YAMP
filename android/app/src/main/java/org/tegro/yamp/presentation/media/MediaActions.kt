package org.tegro.yamp.presentation.media

import androidx.compose.runtime.staticCompositionLocalOf
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.AlbumKind
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.provider.ArtistPage
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.service

/**
 * An album or an artist the actions menu is about (tasks/todo.md "Album / artist actions", canvas `am-*`): from a
 * long press wherever they are listed, or the ⋮ of their page. The account is found from the ref (D78, D84).
 */
data class MediaTarget(
    val ref: MediaRef,
    val title: String,
    /** "Album · Мельница · 2009" — what the sheet's header shows under the title. */
    val subtitle: String,
    val cover: String?,
    val isArtist: Boolean
) {
    val provider: Provider? get() = ref.service

    companion object {
        fun of(album: Album, albumWord: String): MediaTarget = MediaTarget(
            ref = album.ref,
            title = album.title,
            subtitle = listOfNotNull(albumWord, album.artists.firstOrNull()?.name?.ifEmpty { null }, album.year?.toString())
                .joinToString(" · "),
            cover = album.cover?.key,
            isArtist = false
        )

        fun of(artist: Artist, artistWord: String): MediaTarget =
            MediaTarget(artist.ref, artist.name, artistWord, artist.cover?.key, isArtist = true)
    }
}

/** Opens the album / artist menu. MainScreen provides it; where none is (previews, tests) a long press does nothing. */
fun interface MediaMenuLauncher {
    fun open(target: MediaTarget)
}

val LocalMediaMenu = staticCompositionLocalOf<MediaMenuLauncher?> { null }

/** What a menu row does with the album's or artist's tracks. */
enum class MediaAction { PLAY_NOW, PLAY_NEXT, ADD_TO_END, ADD_TO_QUEUE }

/** The menu's rows, in order (canvas `AmMenu`). Pure; unit-tested. */
enum class MediaMenuRow { PLAY_NOW, PLAY_NEXT, ADD_TO_END, ADD_TO_QUEUE, START_RADIO }

/**
 * The rows for [target]: "Play next" and "Add to end" only when the active queue can take its tracks (D84) — else
 * "Add to queue…" offers its own account's queues; "Start radio" only for Yandex (`album:` / `artist:` stations,
 * Jellyfin's Instant Mix is Phase 6).
 */
fun mediaMenuRows(target: MediaTarget, fitsActiveQueue: Boolean): List<MediaMenuRow> = buildList {
    add(MediaMenuRow.PLAY_NOW)
    if (fitsActiveQueue) {
        add(MediaMenuRow.PLAY_NEXT)
        add(MediaMenuRow.ADD_TO_END)
    }
    add(MediaMenuRow.ADD_TO_QUEUE)
    if (target.provider == Provider.YANDEX) add(MediaMenuRow.START_RADIO)
}

/** "Add the artist": every album in release order, or the popular tracks. */
enum class ArtistChoice { ALBUMS, POPULAR }

/** What the artist dialog shows for each choice. */
data class ArtistCounts(
    val albums: Int,
    val albumTracks: Int,
    /** Albums, singles and compilations together, when they are taken too. */
    val releases: Int,
    val releaseTracks: Int,
    val popular: Int,
    /** The artist has singles or compilations: the switch shows only then. */
    val hasExtras: Boolean
)

/**
 * The releases "all albums" takes, oldest first (a release without a year last, the page's order kept among
 * equals): proper albums, plus singles and compilations when [withExtras].
 */
fun artistReleases(page: ArtistPage, withExtras: Boolean): List<Album> {
    val own = if (withExtras) page.albums + page.alsoAlbums else page.albums.filter { it.kind == AlbumKind.ALBUM }
    return own.distinctBy { it.ref }.sortedWith(compareBy(nullsLast()) { it.year })
}

fun artistCounts(page: ArtistPage): ArtistCounts {
    val albums = artistReleases(page, withExtras = false)
    val releases = artistReleases(page, withExtras = true)
    return ArtistCounts(
        albums = albums.size,
        albumTracks = albums.sumOf { it.trackCount },
        releases = releases.size,
        releaseTracks = releases.sumOf { it.trackCount },
        popular = page.popularTracks.size,
        hasExtras = releases.size > albums.size
    )
}
