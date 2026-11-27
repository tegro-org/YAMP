package org.tegro.yamp.data.player

import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.MediaRef

/*
 * The few derived facts the player code asks a track for (phase 3 step 11). They carry the names the service's
 * track object used, so a list may hold either while the wave keeps the service's objects: the same expression
 * reads the same fact from both, never a different field of the same name.
 */

/** The provider's id of the track (the Yandex id, or "radio_<n>" for an FM station). */
internal val Track.idString: String get() = ref.id

/** An FM station dressed as a track. */
internal val Track.isRadio: Boolean get() = ref.provider == MediaRef.PROVIDER_RADIO

/** A track the service streams (not a station, not a file): what the rotor's feedback is about. */
internal val Track.isStreamed: Boolean get() = ref.provider == MediaRef.PROVIDER_YANDEX && !isRadio

/** Marked 18+ by the provider. */
internal val Track.isExplicit: Boolean get() = explicit

/** The provider's cover template (what the image cache keys by). */
internal val Track.coverUri: String? get() = cover?.key
