package org.tegro.yamp.presentation.main

import org.tegro.yamp.domain.queue.service
import org.tegro.yamp.domain.model.WaveTuning
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import org.tegro.yamp.presentation.main.components.MainTab
import org.tegro.yamp.domain.model.StartPage
import org.tegro.yamp.domain.model.StartMusic
import org.tegro.yamp.domain.model.AnimationMode
import org.tegro.yamp.data.player.PlayRequests
import org.tegro.yamp.data.player.HomeKnob
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tegro.yamp.data.account.AccountSessionManager
import org.tegro.yamp.data.download.DownloadQueueState
import org.tegro.yamp.data.download.DownloadScheduler
import org.tegro.yamp.data.provider.yandex.toTrack
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.common.id
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.PlaylistSource
import org.tegro.yamp.data.player.SleepTimerState
import org.tegro.yamp.data.provider.yandex.repository.LibraryRepository
import org.tegro.yamp.domain.repository.AuthRepository
import org.tegro.yamp.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Playback and the "Liked" library with its downloads and settings. Accounts, search, playlists, radio
 * and updates have their own ViewModels; data and the network live in the repositories.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    authRepository: AuthRepository,
    signedInAccounts: org.tegro.yamp.data.account.SignedInAccounts,
    private val session: AccountSessionManager,
    private val playbackController: PlaybackController,
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val downloadManager: TrackDownloadManager,
    private val downloadScheduler: DownloadScheduler,
    networkMonitor: org.tegro.yamp.data.network.NetworkMonitor,
    private val reactions: org.tegro.yamp.data.player.TrackReactions,
    private val playRequests: PlayRequests
) : ViewModel() {

    val isAuthorized = authRepository.isAuthorized()
        .stateIn(viewModelScope, SharingStarted.Eagerly, authRepository.getToken() != null)

    // region Library

    // Library and charts live in the process-wide LibraryRepository (single-flight, incremental sync)
    val tracks: StateFlow<List<Track>> = libraryRepository.likedTracks.map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, libraryRepository.likedTracks.value.map { it.toTrack() })
    val likedTrackIds: StateFlow<Set<String>> = libraryRepository.likedIds
    val popularTracks: StateFlow<List<Track>> = libraryRepository.popularTracks.map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, libraryRepository.popularTracks.value.map { it.toTrack() })
    val isLoading: StateFlow<Boolean> = libraryRepository.isSyncingLikes
    /** The likes could not be loaded; they are retried by themselves as soon as the network is back. */
    val libraryLoadFailed: StateFlow<Boolean> = libraryRepository.likesSyncFailed

    init {
        networkMonitor.isConnectedFlow
            .onEach { online -> if (online && libraryRepository.likesSyncFailed.value) loadLikedTracks() }
            .launchIn(viewModelScope)
        // Once music has sounded, the wave knob's play is a resume (see waveKnob)
        playbackController.isPlaying
            .onEach { if (it) playRequests.markPlayed() }
            .launchIn(viewModelScope)
    }

    val isDownloadingAll: StateFlow<Boolean> = downloadScheduler.isDownloadingAll
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val downloadAllState: StateFlow<DownloadQueueState> = downloadScheduler.downloadAllState
        .stateIn(viewModelScope, SharingStarted.Eagerly, DownloadQueueState.IDLE)
    val downloadedTrackIds: StateFlow<Set<String>> = downloadManager.downloadedTrackIds
    val downloadProgress = downloadManager.downloadProgress

    val steeringWheelOnLeft: StateFlow<Boolean> = settingsRepository.steeringWheelOnLeft
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_STEERING_WHEEL_ON_LEFT)
    val linkState = playbackController.linkState

    // endregion

    // region Playback state

    val currentSource: StateFlow<PlaylistSource> = playbackController.currentSource
    val currentPlaylist: StateFlow<List<Track>> = playbackController.currentPlaylist
    val currentTrack: StateFlow<Track?> = playbackController.currentTrack
    val currentTrackId: StateFlow<String?> = playbackController.currentTrack
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, playbackController.currentTrack.value?.id)
    val isPlaying: StateFlow<Boolean> = playbackController.isPlaying
    val isBuffering: StateFlow<Boolean> = playbackController.isBuffering
    val progress: StateFlow<Long> = playbackController.progress
    /** How far the current track is loaded: the lighter part of the progress bars ([Long.MAX_VALUE] = all of it). */
    val bufferedProgress: StateFlow<Long> = playbackController.bufferedProgress
    /** The next track's state: the Next button shows it (D81). */
    val nextTrack: StateFlow<org.tegro.yamp.domain.player.NextTrack> = playbackController.nextTrack
    val isFetchingMoreQueue: StateFlow<Boolean> = playbackController.isFetchingMoreQueue
    val wavePreview: StateFlow<List<Track>> = playbackController.wavePreview
    val streamQuality: StateFlow<String?> = playbackController.streamQuality
    val sleepTimer: StateFlow<SleepTimerState?> = playbackController.sleepTimer
    val playbackMessages: SharedFlow<String> = playbackController.messages
    /** The queues for the count badge, and the active one for the queue sheet's rows and modes. */
    val queues: StateFlow<List<org.tegro.yamp.domain.queue.QueueSummary>> = playbackController.queues
    val activeQueue: StateFlow<org.tegro.yamp.data.queue.ActiveQueue?> = playbackController.activeQueue

    /** The signed-in accounts as markers; markers show only with more than one (D58). */
    val accountTags: StateFlow<List<org.tegro.yamp.presentation.account.AccountTag>> = signedInAccounts.accounts
        .map { org.tegro.yamp.presentation.account.providerAccountTags(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, org.tegro.yamp.presentation.account.providerAccountTags(signedInAccounts.current()))

    /** The marker of [queue]'s account with more than one account; null otherwise (D58, D64). */
    fun markOf(tags: List<org.tegro.yamp.presentation.account.AccountTag>, account: String?): org.tegro.yamp.presentation.account.AccountTag? =
        if (tags.size > 1) tags.firstOrNull { it.key == account } else null

    // endregion

    init {
        // The controller outlives this ViewModel; give it the library for "play liked" fallbacks.
        libraryRepository.likedTracks.onEach { playbackController.updateLibrary(it.map { track -> track.toTrack() }) }.launchIn(viewModelScope)

        viewModelScope.launch { session.refreshProfileIfIncomplete() }

        // Cached library instantly, then exactly one network sync per authorization
        viewModelScope.launch {
            session.loadCached()
            authRepository.isAuthorized().collect { isAuth ->
                if (isAuth) session.syncAll()
            }
        }
    }

    // region Library actions

    /** Pull-to-refresh and the Library tab: sync likes (joins a sync already running). */
    fun loadLikedTracks() {
        viewModelScope.launch { session.syncLikes() }
    }

    /** The heart: a track's like, or a station's favourite (shared with the player in the notification). */
    /** A Jellyfin track has no likes yet (P5): nothing goes to the Yandex account for it. */
    fun toggleLike(track: Track) {
        if (track.ref.service == org.tegro.yamp.domain.account.Provider.JELLYFIN) return
        reactions.toggleLike(track)
    }

    /** "Не нравится": a track leaves the wave and the next one plays; a station leaves the favourites. */
    fun dislikeTrack(track: Track) {
        if (track.ref.service == org.tegro.yamp.domain.account.Provider.JELLYFIN) return
        reactions.dislike(track)
    }

    /** Offline downloads run in WorkManager: they continue after the app is closed. */
    fun downloadAllLikedTracks() {
        viewModelScope.launch { downloadScheduler.downloadAllLiked() }
    }

    fun cancelDownloadAll() {
        downloadScheduler.cancelAll()
        downloadManager.setDownloadProgress(null)
    }

    /** What plays now and what the server offered, for the quality details in the player. */
    fun qualityDetails() = playbackController.qualityDetails()

    /** Deletes the active user's downloads (except tracks other saved accounts liked) and reloads the lists. */
    fun clearMusicCache() = session.launch { clearMusicCache() }

    // endregion

    // region Playback: delegated to the process-wide PlaybackController

    fun playMyVibe() = playbackController.playMyVibe()
    fun playWaveFrom(trackId: String) {
        playRequests.userStarted()
        playbackController.playWaveFrom(trackId)
    }

    /**
     * The big knob on the first tab — the active queue's page (D49). It starts what the page shows (the wave,
     * or the queue at its cursor when a live station plays or nothing does), or plays the session restored at
     * launch: both count as switching music on (the full player may open). A play after the user's own pause
     * is a resume and opens nothing; so are the mini-player's buttons.
     */
    fun homeKnob(wavePage: Boolean, pagePlays: Boolean) {
        val press = HomeKnob.press(wavePage, pagePlays, playbackController.isPlaying.value, playRequests.hasPlayed)
        if (press.opensPlayer) playRequests.userStarted()
        when (press.action) {
            HomeKnob.Action.START_WAVE -> playbackController.playMyVibe()
            // The cursor track, where the queue was left; a queue without a cursor starts from its first entry
            HomeKnob.Action.START_QUEUE -> activeQueue.value?.cursorEntry?.let { playbackController.playEntry(it.entryId) }
                ?: playbackController.play()
            HomeKnob.Action.TOGGLE -> playbackController.togglePlayPause()
        }
    }

    fun loadMoreQueue() = playbackController.loadMoreQueue()

    fun playAllLikedTracks() {
        playRequests.userStarted()
        playbackController.playAllLikedTracks()
    }

    /** The liked tracks that are on the device, in the library's order. */
    fun playDownloaded() {
        val downloaded = tracks.value.filter { it.id in downloadedTrackIds.value }
        downloaded.firstOrNull()?.let {
            playRequests.userStarted()
            playbackController.playTrack(it.id, downloaded, PlaylistSource.LIBRARY)
        }
    }

    fun shuffleLikedTracks() {
        playRequests.userStarted()
        playbackController.playAllLikedTracks(shuffle = true)
    }

    fun playHits(shuffle: Boolean = true) {
        playRequests.userStarted()
        playbackController.playHits(libraryRepository.weeklyHits.value.map { it.toTrack() }, shuffle)
    }

    /** @param byUser the user chose this track (a list, a card); false when the app moves on by itself. */
    fun playTrack(trackId: String, playlist: List<Track>, source: PlaylistSource? = null, byUser: Boolean = true) {
        if (byUser) playRequests.userStarted()
        playbackController.playTrack(trackId, playlist, source)
    }

    // region My Wave: tuning and a wave by a track

    val waveTuning: StateFlow<WaveTuning> = playbackController.waveTuning
    val waveSeed: StateFlow<Track?> = playbackController.waveSeed
    val trackWaveStarted: Flow<Track> = playbackController.trackWaveStarted

    fun setWaveTuning(tuning: WaveTuning) = playbackController.setWaveTuning(tuning)
    fun startTrackWave(track: Track) {
        playRequests.userStarted()
        playbackController.startTrackWave(track)
    }
    /** The active queue is My Wave: the wave controls are wave-internal there and "Start radio" elsewhere (D54). */
    val waveActive: StateFlow<Boolean> = activeQueue
        .map { it?.queue?.kind == org.tegro.yamp.domain.queue.QueueKind.WAVE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** D54, the one place the choice is made: a wave by [track] on My Wave, the radio picker on any other queue. */
    fun waveOrRadio(track: Track, openRadioPicker: () -> Unit) {
        if (waveActive.value) startTrackWave(track) else openRadioPicker()
    }

    fun returnToMyWave() = playbackController.returnToMyWave()
    fun undoTrackWave() = playbackController.undoTrackWave()

    // endregion

    // region Launch and the look of things

    /** Opens the full player when the user starts music, if the setting asks for it. */
    val openPlayerRequests: Flow<Unit> = playRequests.userStarted
        .filter { settingsRepository.expandPlayerOnPlay.first() }

    /** "Без ненормативной лексики": what is shown as coming next leaves out what will be passed over. */
    val skipExplicit: StateFlow<Boolean> = settingsRepository.skipExplicit
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val swipeToCloseMiniPlayer: StateFlow<Boolean> = settingsRepository.swipeToCloseMiniPlayer
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val carClock: StateFlow<Boolean> = settingsRepository.carClock
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val animationMode: StateFlow<AnimationMode?> = settingsRepository.animationMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The tab the app opens on; null until the setting has been read, so no other tab flashes first. */
    val startTab: StateFlow<MainTab?> = combine(settingsRepository.startPage, settingsRepository.lastTab) { page, last ->
        when (page) {
            StartPage.WAVE -> MainTab.HOME
            StartPage.CAR -> MainTab.CAR
            StartPage.LAST -> MainTab.entries.firstOrNull { it.route == last } ?: MainTab.HOME
        }
    }.take(1).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun rememberTab(tab: MainTab) {
        viewModelScope.launch { settingsRepository.setLastTab(tab.route) }
    }

    /** "Music on launch": once per process, and never over music that already plays. */
    fun applyStartMusic() {
        if (!isAuthorized.value || !playRequests.claimLaunchStart()) return
        viewModelScope.launch {
            val mode = settingsRepository.startMusic.first()
            if (mode == StartMusic.OFF) return@launch
            playbackController.awaitRestoredSession()
            if (playbackController.isPlaying.value) return@launch
            when (mode) {
                StartMusic.CONTINUE -> playbackController.play()
                // "My Wave at start" meant "play something" when the wave was the only thing that resumed; with
                // persistent queues the queue the user chose last goes on (D35), the wave only when there is none
                StartMusic.WAVE -> if (playbackController.currentTrack.value != null) {
                    playbackController.play()
                } else {
                    playbackController.playMyVibe()
                }
                StartMusic.OFF -> Unit
            }
        }
    }

    // endregion

    fun playNext() = playbackController.playNext()

    fun playPrev() = playbackController.playPrev()

    fun togglePlayPause() = playbackController.togglePlayPause()

    fun seekTo(positionMs: Long) = playbackController.seekTo(positionMs)

    fun removeFromQueue(trackId: String) = playbackController.removeFromQueue(trackId)
    fun moveInQueue(trackId: String, targetId: String) = playbackController.moveInQueue(trackId, targetId)

    fun enqueueNext(track: Track) = playbackController.enqueueNext(track)

    /** "Add to end of queue" for a track or a whole list. */
    fun addToEnd(tracks: List<Track>) = playbackController.addToEnd(tracks)

    /** History's row: the track plays now, right after the cursor; the active queue goes on after it. */
    fun playNowKeepQueue(track: Track) {
        playRequests.userStarted()
        playbackController.playNowKeepQueue(track)
    }

    /** The queue sheet's row: that entry, so a track that is in the queue twice works. */
    fun playEntry(entryId: Long) {
        playRequests.userStarted()
        playbackController.playEntry(entryId)
    }

    fun setShuffle(on: Boolean) = playbackController.setShuffle(on)
    fun setRepeat(mode: org.tegro.yamp.domain.queue.RepeatMode) = playbackController.setRepeat(mode)

    fun stopAndClearPlayer() = playbackController.stopAndClear()

    fun setSleepTimer(minutes: Int) = playbackController.setSleepTimer(minutes)

    fun setSleepAtEndOfTrack() = playbackController.setSleepAtEndOfTrack()

    fun cancelSleepTimer() = playbackController.cancelSleepTimer()

    // endregion
}
