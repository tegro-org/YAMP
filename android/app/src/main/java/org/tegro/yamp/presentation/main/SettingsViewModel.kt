package org.tegro.yamp.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tegro.yamp.data.diagnostics.FileShare
import org.tegro.yamp.data.download.DownloadScheduler
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.PlaybackJournal
import org.tegro.yamp.data.player.SoundMode
import org.tegro.yamp.data.provider.yandex.reserve.RoadReserve
import org.tegro.yamp.domain.model.AnimationMode
import org.tegro.yamp.domain.model.StartMusic
import org.tegro.yamp.domain.model.StartPage
import org.tegro.yamp.domain.repository.SettingsRepository
import org.tegro.yamp.presentation.main.components.SettingsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The settings: one [state] for the sheet and one function per change. The account, update and crash
 * lines come from their own view models and are added where the sheet is shown.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val soundMode: SoundMode,
    private val playbackController: PlaybackController,
    private val roadReserve: RoadReserve,
    private val downloadManager: TrackDownloadManager,
    private val downloadScheduler: DownloadScheduler,
    private val storage: org.tegro.yamp.data.download.DownloadStorage,
    private val equalizer: org.tegro.yamp.data.player.SystemEqualizer,
    private val journal: PlaybackJournal,
    private val diagnosticLog: org.tegro.yamp.data.diagnostics.DiagnosticLog,
    private val streamCache: org.tegro.yamp.data.player.StreamCache,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    qualityStore: org.tegro.yamp.data.player.AccountQualityStore,
    private val accountContext: org.tegro.yamp.data.account.AccountContext
) : ViewModel() {

    /** The quality the playing account gets on the network in use: its own choice, else the global one (summary line). */
    private val effectiveCompatible: Flow<Boolean> =
        combine(soundMode.compatible, qualityStore.choices, qualityStore.metered) { _, _, _ -> soundMode.compatibleFor(accountContext.current()) }

    private data class Sound(
        val compatible: Boolean, val crossfade: Int, val normalize: Boolean, val startMusic: StartMusic,
        val bluetooth: Boolean, val explicit: Boolean, val history: Boolean = true,
        val duck: Boolean = false, val resumeQuiet: Boolean = false, val fadeThenRise: Boolean = false
    )
    private data class Look(
        val startPage: StartPage, val expand: Boolean, val swipe: Boolean, val left: Boolean, val clock: Boolean,
        val carLayout: Boolean = false, val nextThree: Boolean = false, val likeGestures: Boolean = false
    )
    private data class Scale(val auto: Boolean, val manual: Float, val animations: AnimationMode)
    private data class Library(val reserveMb: Int, val reserveUsed: Long, val autoDownload: Boolean, val cacheBytes: Long, val journal: PlaybackJournal.Snapshot = PlaybackJournal.Snapshot(), val diagnosticLog: Boolean = false, val groupQueues: Boolean = true, val streamCacheMb: Int = SettingsRepository.DEFAULT_STREAM_CACHE_MB, val streamCacheUsed: Long = 0L)

    private val sound: Flow<Sound> = combine(
        combine(effectiveCompatible, settings.crossfadeSeconds, settings.normalizeVolume) { c, f, n -> Sound(c, f, n, StartMusic.OFF, false, false) },
        combine(settings.startMusic, settings.bluetoothAutoplay, settings.skipExplicit, settings.listeningHistory) { s, b, e, h ->
            Sound(false, 0, false, s, b, e, h)
        }
    ) { sound, rest -> sound.copy(startMusic = rest.startMusic, bluetooth = rest.bluetooth, explicit = rest.explicit, history = rest.history) }
        .combine(combine(settings.duckForAnnouncements, settings.resumeWhenQuiet, ::Pair)) { sound, (duck, quiet) ->
            sound.copy(duck = duck, resumeQuiet = quiet)
        }
        .combine(settings.transitionFadeThenRise) { sound, fadeRise -> sound.copy(fadeThenRise = fadeRise) }

    private val look: Flow<Look> = combine(
        settings.startPage, settings.expandPlayerOnPlay, settings.swipeToCloseMiniPlayer, settings.steeringWheelOnLeft, settings.carClock
    ) { page, expand, swipe, left, clock -> Look(page, expand, swipe, left, clock) }
        .combine(combine(settings.playerCarLayout, settings.playerNextThree, settings.likeGestures, ::Triple)) { look, (car, three, gestures) ->
            look.copy(carLayout = car, nextThree = three, likeGestures = gestures)
        }

    private val scale: Flow<Scale> = combine(settings.uiScaleAuto, settings.uiScaleManual, settings.animationMode, ::Scale)

    /** Bumped after the stream cache is emptied, so its "used" line is read again. */
    private val streamCacheTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val library: Flow<Library> = combine(
        combine(settings.roadReserveMb, roadReserve.usedBytes, settings.autoDownloadLikedTracks, downloadManager.cacheSizeBytes) { mb, used, auto, cache ->
            Library(mb, used, auto, cache)
        },
        journal.state,
        settings.diagnosticLog,
        settings.groupQueuesByAccount
    ) { lib, journal, diag, group -> lib.copy(journal = journal, diagnosticLog = diag, groupQueues = group) }
        .combine(combine(settings.streamCacheMb, streamCacheTick) { mb, _ -> mb }) { lib, mb ->
            lib.copy(streamCacheMb = mb, streamCacheUsed = streamCache.usedBytes)
        }

    val state: StateFlow<SettingsUiState> = combine(sound, look, scale, library, storage.state) { s, l, sc, lib, card ->
        SettingsUiState(
            compatibleSound = s.compatible,
            crossfadeSeconds = s.crossfade,
            normalizeVolume = s.normalize,
            duckForAnnouncements = s.duck,
            resumeWhenQuiet = s.resumeQuiet,
            transitionFadeThenRise = s.fadeThenRise,
            equalizerAvailable = equalizer.isAvailable(),
            powerampEqualizer = equalizer.powerampEqualizerInstalled(),
            startMusic = s.startMusic,
            bluetoothAutoplay = s.bluetooth,
            skipExplicit = s.explicit,
            listeningHistory = s.history,
            reserveMb = lib.reserveMb,
            reserveUsedBytes = lib.reserveUsed,
            streamCacheMb = lib.streamCacheMb,
            streamCacheUsedBytes = lib.streamCacheUsed,
            autoDownload = lib.autoDownload,
            cacheSizeBytes = lib.cacheBytes,
            journal = lib.journal,
            diagnosticLog = lib.diagnosticLog,
            groupQueues = lib.groupQueues,
            storage = card,
            startPage = l.startPage,
            expandPlayerOnPlay = l.expand,
            swipeToCloseMiniPlayer = l.swipe,
            steeringWheelOnLeft = l.left,
            carClock = l.clock,
            playerCarLayout = l.carLayout,
            playerNextThree = l.nextThree,
            likeGestures = l.likeGestures,
            uiScaleAuto = sc.auto,
            uiScaleManual = sc.manual,
            animationMode = sc.animations
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private fun save(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { settings.block() }
    }

    fun setCompatibleSound(enabled: Boolean) {
        viewModelScope.launch {
            soundMode.choose(enabled)
            playbackController.onSoundModeChanged(enabled)
        }
    }

    fun setCrossfade(seconds: Int) = save { setCrossfadeSeconds(seconds) }
    fun setNormalizeVolume(enabled: Boolean) = save { setNormalizeVolume(enabled) }
    fun setDuckForAnnouncements(enabled: Boolean) = save { setDuckForAnnouncements(enabled) }
    fun setResumeWhenQuiet(enabled: Boolean) = save { setResumeWhenQuiet(enabled) }
    fun setTransitionFadeThenRise(enabled: Boolean) = save { setTransitionFadeThenRise(enabled) }
    fun setGroupQueues(enabled: Boolean) = save { setGroupQueuesByAccount(enabled) }
    fun openEqualizer() = equalizer.open()
    fun openPowerampEqualizer() = equalizer.openPowerampEqualizer()
    fun clearJournal() = journal.clear()
    fun setDiagnosticLog(enabled: Boolean) = save { setDiagnosticLog(enabled) }
    fun clearDiagnosticLog() = diagnosticLog.clear()
    fun shareDiagnosticLog() {
        viewModelScope.launch { diagnosticLog.share() }
    }

    /** The journal as a file (and as text for messengers), to the app of the user's choice. */
    fun shareJournal(text: String) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                FileShare.writeTextFile(context, "yamp-journal", text)
            }
            FileShare.share(
                context,
                listOf(FileShare.uriFor(context, file)),
                subject = "YAMP journal",
                text = text,
            )
        }
    }
    fun setStartMusic(value: StartMusic) = save { setStartMusic(value) }
    fun setBluetoothAutoplay(enabled: Boolean) = save { setBluetoothAutoplay(enabled) }
    fun setSkipExplicit(enabled: Boolean) = save { setSkipExplicit(enabled) }
    fun setListeningHistory(enabled: Boolean) = save { setListeningHistory(enabled) }
    fun setReserveMb(megabytes: Int) = save { setRoadReserveMb(megabytes) }
    fun setStreamCacheMb(megabytes: Int) = save { setStreamCacheMb(megabytes) }
    fun clearStreamCache() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            streamCache.clear()
            streamCacheTick.value++
        }
    }

    fun clearReserve() {
        viewModelScope.launch { roadReserve.clear() }
    }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoDownloadLikedTracks(enabled)
            if (enabled) downloadScheduler.downloadAllLiked()
        }
    }

    fun upgradeDownloads() {
        viewModelScope.launch { downloadScheduler.upgradeDownloadedQuality() }
    }

    fun setDownloadsOnCard(onCard: Boolean) = storage.moveTo(onCard)
    fun setStartPage(value: StartPage) = save { setStartPage(value) }
    fun setExpandPlayer(enabled: Boolean) = save { setExpandPlayerOnPlay(enabled) }
    fun setSwipeToClose(enabled: Boolean) = save { setSwipeToCloseMiniPlayer(enabled) }
    fun setSteeringWheelOnLeft(onLeft: Boolean) = save { setSteeringWheelOnLeft(onLeft) }
    fun setCarClock(enabled: Boolean) = save { setCarClock(enabled) }
    fun setPlayerCarLayout(enabled: Boolean) = save { setPlayerCarLayout(enabled) }
    fun setPlayerNextThree(enabled: Boolean) = save { setPlayerNextThree(enabled) }
    fun setLikeGestures(enabled: Boolean) = save { setLikeGestures(enabled) }
    fun setUiScaleAuto(enabled: Boolean) = save { setUiScaleAuto(enabled) }
    fun setUiScaleManual(scale: Float) = save { setUiScaleManual(scale) }
    fun setAnimationMode(value: AnimationMode) = save { setAnimationMode(value) }
}
