package org.tegro.yamp.data.provider.radio

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.R
import org.tegro.yamp.data.repository.RadioRepository
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef

/** Step 16 (D76): FM radio is a built-in, account-less source; a station on air reads as it did before. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FmRadioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val radio = FmRadio(context, RadioRepository(context))
    private val station = radio.stations().first()

    @Test
    fun aStationOnAirIsARadioTrackNamedByItsFrequency() {
        val track = radio.trackOf(station)

        assertEquals(TrackRef.of(MediaRef.PROVIDER_RADIO, "radio_${station.id}"), track.ref)
        assertEquals(station.name, track.title)
        assertEquals(listOf(context.getString(R.string.radio_live_frequency, station.frequency)), track.artists.map { it.name })
        assertNull("a frequency is not an artist one can open", track.artists.single().ref)
        assertEquals(0L, track.durationMs)
        assertEquals(station, radio.station(track.ref.id))
    }

    @Test
    fun theStreamIsTheStationsOwnUrlNeverCached() = runBlocking {
        val ref = radio.trackOf(station).ref

        assertEquals(Outcome.Ok(ResolvedStream(station.streamUrl)), radio.streams.resolve(ref))
        assertNull(ResolvedStream(station.streamUrl).cacheKey)
        assertEquals(Outcome.Err(ProviderError.NotFound), radio.streams.resolve(TrackRef.of(MediaRef.PROVIDER_RADIO, "radio_gone")))
    }
}
