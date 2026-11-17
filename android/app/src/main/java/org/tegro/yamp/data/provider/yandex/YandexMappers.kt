package org.tegro.yamp.data.provider.yandex

import kotlinx.serialization.json.JsonPrimitive
import org.tegro.yamp.data.dto.ArtistBriefDto
import org.tegro.yamp.data.dto.AlbumDto
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.R128Dto
import org.tegro.yamp.data.dto.TrackAlbumDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.dto.UserPlaylistDto
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.AlbumBrief
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Cover
import org.tegro.yamp.domain.media.Loudness
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef

/*
 * Yandex DTOs → the neutral models (docs/phase-3-providers.md step 3). The only place outside data/dto that turns
 * Yandex shapes into domain ones; screens migrate onto the models step by step (steps 11–15).
 */

/** The FM stations dressed as tracks ([TrackDetailsDto.isRadio]) are not Yandex items: their refs say so. */
const val PROVIDER_RADIO = MediaRef.PROVIDER_RADIO

/**
 * A Yandex cover template ("avatars.yandex.net/…/%%"): the size goes where `%%` is. Anything without the
 * placeholder (a bundled image, a full URL) is used as it is, at every size.
 */
data class YandexCover(val template: String) : Cover {
    override val key: String get() = template

    override fun url(sizePx: Int): String {
        if (!template.contains(PLACEHOLDER)) return template
        val base = if (template.startsWith("http")) template else "https://$template"
        return base.replace(PLACEHOLDER, "${sizePx}x$sizePx")
    }

    companion object {
        private const val PLACEHOLDER = "%%"

        fun of(template: String?): YandexCover? = template?.takeIf { it.isNotBlank() }?.let(::YandexCover)
    }
}

private fun ref(kind: String, id: String?): MediaRef? =
    id?.takeIf { it.isNotBlank() && it != "0" }?.let { MediaRef(MediaRef.PROVIDER_YANDEX, kind, it) }

fun ArtistDto.toArtistBrief(): ArtistBrief = ArtistBrief(ref(KIND_ARTIST, idString), name.orEmpty())

fun TrackDetailsDto.toTrack(): Track = Track(
    ref = if (isRadio) TrackRef.of(PROVIDER_RADIO, idString) else TrackRef.yandex(idString),
    title = title.orEmpty(),
    artists = artists.map { it.toArtistBrief() },
    album = albums.firstOrNull()?.let { album ->
        AlbumBrief(ref(KIND_ALBUM, (album.id as? JsonPrimitive)?.content), album.title)
    },
    durationMs = durationMs,
    cover = YandexCover.of(coverUri),
    explicit = isExplicit,
    loudness = r128?.let { Loudness(it.integrated, it.truePeak) }
)

/**
 * The service object for a track this provider mapped (the reverse of [toTrack]). Lossless for every field the
 * data layer reads — id, title, artists (id, name), cover template, duration, first album (id, title), loudness,
 * the explicit mark, the station flag — so the controller and the repositories can take a shown [Track] without a
 * lookup table (docs/phase-3-providers.md step 11).
 */
fun Track.toYandexTrack(): TrackDetailsDto = TrackDetailsDto(
    id = JsonPrimitive(ref.id),
    title = title.ifEmpty { null },
    artists = artists.map { ArtistDto(id = it.ref?.id?.let(::JsonPrimitive), name = it.name) },
    coverUri = cover?.key,
    durationMs = durationMs,
    isRadio = ref.provider == PROVIDER_RADIO,
    albums = listOfNotNull(album?.let { TrackAlbumDto(it.ref?.id?.let(::JsonPrimitive), it.title) }),
    r128 = loudness?.let { R128Dto(it.integratedLufs, it.truePeakDbtp) },
    contentWarning = if (explicit) "explicit" else null
)

fun List<Track>.toYandexTracks(): List<TrackDetailsDto> = map { it.toYandexTrack() }

/** Null when the server sent no id: an artist without identity cannot be opened. */
fun ArtistDto.toArtist(): Artist? = ref(KIND_ARTIST, idString)?.let { Artist(it, name.orEmpty(), YandexCover.of(coverUri)) }

/** Null when the server sent no id. */
fun AlbumDto.toAlbum(): Album? = ref(KIND_ALBUM, idString)?.let {
    Album(
        ref = it,
        title = title.orEmpty(),
        artists = artists.map { artist -> artist.toArtistBrief() },
        cover = YandexCover.of(coverUri),
        year = year,
        trackCount = trackCount,
        tracks = tracks.map { track -> track.toTrack() }
    )
}

/**
 * A playlist is addressed by its owner and kind: `yandex:playlist:<ownerUid>:<kind>` (C1). Lists cached before
 * the owner was stored have only the kind; their ref is `yandex:playlist:<kind>`. Null without a kind.
 */
fun UserPlaylistDto.toPlaylist(): Playlist? {
    val kind = kindString.takeIf { it.isNotBlank() } ?: return null
    val id = ownerUid?.let { "$it:$kind" } ?: kind
    return Playlist(
        ref = MediaRef(MediaRef.PROVIDER_YANDEX, KIND_PLAYLIST, id),
        title = title.orEmpty(),
        trackCount = trackCount,
        cover = YandexCover.of(coverUri),
        isPublic = isPublic,
        tracks = tracks.mapNotNull { it.track?.toTrack() }
    )
}

private const val KIND_ARTIST = "artist"
private const val KIND_ALBUM = "album"
private const val KIND_PLAYLIST = "playlist"

/**
 * An artist's page as the service sends it, in neutral parts: the artist (null when the service sent none), the
 * popular tracks, and the albums that can be opened (those with an id).
 */
data class ArtistParts(val artist: Artist?, val popularTracks: List<Track>, val albums: List<Album>)

fun ArtistBriefDto.toArtistParts(): ArtistParts =
    ArtistParts(artist?.toArtist(), popularTracks.map { it.toTrack() }, albums.mapNotNull { it.toAlbum() })

