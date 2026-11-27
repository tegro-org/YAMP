package org.tegro.yamp.presentation.common

import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.service

/*
 * What screens read off a neutral Track (docs/phase-3-providers.md steps 12–15): the same values the screens took
 * from the Yandex track DTO before, so the migration changes no pixel.
 */

/**
 * The provider's own id of the track (the Yandex `12345` / `12345:678`, the station id of an FM station). Read off
 * the ref's text, so a track the service sent without an id gives "" instead of failing to parse.
 */
val Track.id: String get() = ref.value.substringAfter(':').substringAfter(':')

/** An FM station dressed as a track: no duration, a logo instead of a cover. */
val Track.isRadio: Boolean get() = ref.value.startsWith(MediaRef.PROVIDER_RADIO + ":")

/** Like / dislike exist only for Yandex tracks; Jellyfin and local favourites and FM stations have none (yet). */
val Track.supportsLikes: Boolean get() = !isRadio && ref.service == org.tegro.yamp.domain.account.Provider.YANDEX

/** The cover as the image helpers take it (a size template, a bundled image or a full URL); null without one. */
val Track.coverUri: String? get() = cover?.key

/** The artists' names, comma-separated; empty when the track names none. */
val Track.artistNames: String get() = artists.joinToString { it.name }

/** A playlist's cover as the image helpers take it; null without one. */
val Playlist.coverUri: String? get() = cover?.key
