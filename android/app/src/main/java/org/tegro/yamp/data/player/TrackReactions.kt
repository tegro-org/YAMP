package org.tegro.yamp.data.player

import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import android.util.Log
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import org.tegro.yamp.data.download.DownloadScheduler
import org.tegro.yamp.data.provider.yandex.toYandexTrack
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.provider.yandex.repository.LibraryRepository
import org.tegro.yamp.data.repository.RadioRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Like and dislike, the same from every place that offers them: the app's screens and the player in
 * the notification shade, on the lock screen and in the car. For a station the heart is "favourite".
 * Process-wide, so the notification's buttons work while no screen of the app is open.
 */
@Singleton
class TrackReactions @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val radioRepository: RadioRepository,
    private val playbackController: PlaybackController,
    private val downloadManager: TrackDownloadManager,
    private val downloadScheduler: DownloadScheduler
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // What was liked and disliked without a connection goes out as soon as there is one
        playbackController.linkState
            .onEach { if (it == LinkState.GOOD) flushPendingReactions() }
            .launchIn(scope)
    }

    val likedTrackIds: StateFlow<Set<String>> = libraryRepository.likedIds
    val favoriteStationIds: StateFlow<Set<String>> = radioRepository.favoriteStationIds
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Whether the heart of [track] is filled: a liked track, or a favourite station. */
    fun isLiked(track: Track): Boolean =
        if (track.isRadio) stationId(track) in favoriteStationIds.value else track.idString in likedTrackIds.value

    /** The heart: like / unlike a track (a new like is downloaded when its account's auto-download is on), favourite a station. */
    fun toggleLike(track: Track) {
        if (track.isRadio) {
            scope.launch { radioRepository.toggleFavorite(stationId(track)) }
            return
        }
        val trackId = track.idString
        val like = trackId !in likedTrackIds.value
        libraryRepository.setLiked(track.toYandexTrack(), like) { ok ->
            if (!ok) return@setLiked
            scope.launch {
                if (!like) downloadManager.removeTrack(trackId)
                else downloadScheduler.downloadTrack(trackId, auto = true)
            }
        }
    }

    private val _dislikes = MutableSharedFlow<Dislike>(extraBufferCapacity = 4)

    /** Every track dislike, from any place (screen, gesture, notification, car): the app offers Undo for it. */
    val dislikes: SharedFlow<Dislike> = _dislikes.asSharedFlow()

    /** "Не нравится": the track leaves the wave and the next one plays; a station leaves the favourites. */
    fun dislike(track: Track) {
        if (track.isRadio) {
            scope.launch { radioRepository.removeFavorite(stationId(track)) }
            return
        }
        val wasLiked = libraryRepository.dislike(track.toYandexTrack())
        val queue = playbackController.onTrackDisliked(track.idString)
        scope.launch { downloadManager.removeTrack(track.idString) }
        _dislikes.tryEmit(Dislike(track, wasLiked, queue))
    }

    /**
     * Undo of [dislike]: the track leaves the dislikes (liked again if it was), comes back to its place in the
     * queue and, if it was playing, plays on from where it was. A liked track is downloaded again when
     * auto-download is on.
     */
    fun undoDislike(dislike: Dislike) {
        libraryRepository.undoDislike(dislike.track.toYandexTrack(), dislike.wasLiked)
        playbackController.undoDislike(dislike.queue)
        if (dislike.wasLiked) {
            scope.launch { downloadScheduler.downloadTrack(dislike.track.idString, auto = true) }
        }
    }

    private suspend fun flushPendingReactions() {
        try {
            withContext(Dispatchers.IO) { libraryRepository.flushPendingReactions() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Saved reactions stay for the next time", e)
        }
    }

    private fun stationId(track: Track) = track.idString.removePrefix("radio_")

    private companion object {
        const val TAG = "TrackReactions"
    }
}

/** A track dislike that can still be undone with [TrackReactions.undoDislike]. */
class Dislike internal constructor(
    val track: Track,
    internal val wasLiked: Boolean,
    internal val queue: DislikeUndo
)
