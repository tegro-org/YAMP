package org.tegro.yamp.data.provider.radio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.R
import org.tegro.yamp.data.repository.RadioRepository
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Cover
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.model.RadioStation
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.provider.StreamPrefs
import org.tegro.yamp.domain.provider.StreamResolver
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FM radio as a built-in, account-less source (D76, docs/phase-3-providers.md step 16): the stations, a station
 * on air as a [Track], and its stream — the station's own URL, never cached (D86). No account, no provider of a
 * music service: a station plays the same for everyone and never goes into a queue.
 */
@Singleton
class FmRadio @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stations: RadioRepository
) {
    fun stations(): List<RadioStation> = stations.getBuiltinStations()

    /** The station a radio track ([trackIdOf]) stands for; null when it is no longer bundled. */
    fun station(trackId: String): RadioStation? = stations().firstOrNull { trackIdOf(it) == trackId }

    fun stationById(stationId: String): RadioStation? = stations().firstOrNull { it.id == stationId }

    /** The station on air as the player shows it: its name, its frequency as the "artist", its logo. */
    fun trackOf(station: RadioStation): Track = Track(
        ref = TrackRef.of(MediaRef.PROVIDER_RADIO, trackIdOf(station)),
        title = station.name,
        artists = listOf(ArtistBrief(null, context.getString(R.string.radio_live_frequency, station.frequency))),
        album = null,
        durationMs = 0,
        cover = (station.iconRes?.let { "android.resource://${BuildConfig.APPLICATION_ID}/$it" } ?: station.logoUrl.ifEmpty { null })
            ?.let(::FixedCover),
        explicit = false
    )

    /** A station's stream: its URL as it is; unknown stations are not found. */
    val streams: StreamResolver = object : StreamResolver {
        override fun cached(track: TrackRef): ResolvedStream? = station(track.id)?.let { ResolvedStream(it.streamUrl) }

        override suspend fun resolve(track: TrackRef, prefs: StreamPrefs): Outcome<ResolvedStream> =
            cached(track)?.let { Outcome.Ok(it) } ?: Outcome.Err(ProviderError.NotFound)

        override fun invalidate(track: TrackRef) = Unit
    }

    companion object {
        fun trackIdOf(station: RadioStation): String = "radio_${station.id}"
    }
}

/** An image that has one size: a bundled logo or a full URL. */
data class FixedCover(val uri: String) : Cover {
    override val key: String get() = uri

    override fun url(sizePx: Int): String = uri
}
