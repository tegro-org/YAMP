package org.tegro.yamp.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tegro.yamp.data.download.DownloadScheduler
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.PlaybackJournal
import org.tegro.yamp.data.player.SoundMode
import org.tegro.yamp.data.reserve.RoadReserve
import org.tegro.yamp.domain.model.AnimationMode
import org.tegro.yamp.domain.model.StartMusic
import org.tegro.yamp.domain.model.StartPage
import org.tegro.yamp.domain.repository.SettingsRepository
import org.tegro.yamp.presentation.main.components.SettingsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private data class Sound(
        val compatible: Boolean, val crossfade: Int, val normalize: Boolean, val separateSessions: Boolean, val startMusic: StartMusic,
        val bluetooth: Boolean, val explicit: Boolean, val announce: Boolean = true, val history: Boolean = true
    )
    private data class Look(val startPage: StartPage, val expand: Boolean, val swipe: Boolean, val left: Boolean, val clock: Boolean)
    private data class Scale(val auto: Boolean, val manual: Float, val animations: AnimationMode)
    private data class Library(val reserveMb: Int, val reserveUsed: Long, val autoDownload: Boolean, val cacheBytes: Long, val journal: PlaybackJournal.Snapshot = PlaybackJournal.Snapshot(), val diagnosticLog: Boolean = false)

    private val sound: Flow<Sound> = combine(
        combine(soundMode.compatible, settings.crossfadeSeconds, settings.normalizeVolume, settings.separateAudioSessions) { c, f, n, s -> Sound(c, f, n, s, StartMusic.OFF, false, false) },
        combine(settings.startMusic, settings.bluetoothAutoplay, settings.skipExplicit, settings.announceEqualizer, settings.listeningHistory) { s, b, e, a, h ->
            Sound(false, 0, false, false, s, b, e, a, h)
        }
    ) { sound, rest -> sound.copy(startMusic = rest.startMusic, bluetooth = rest.bluetooth, explicit = rest.explicit, announce = rest.announce, history = rest.history) }

    private val look: Flow<Look> = combine(
        settings.startPage, settings.expandPlayerOnPlay, settings.swipeToCloseMiniPlayer, settings.steeringWheelOnLeft, settings.carClock, ::Look
    )

    private val scale: Flow<Scale> = combine(settings.uiScaleAuto, settings.uiScaleManual, settings.animationMode, ::Scale)

    private val library: Flow<Library> = combine(
        combine(settings.roadReserveMb, roadReserve.usedBytes, settings.autoDownloadLikedTracks, downloadManager.cacheSizeBytes) { mb, used, auto, cache ->
            Library(mb, used, auto, cache)
        },
        journal.state,
        settings.diagnosticLog
    ) { lib, journal, diag -> lib.copy(journal = journal, diagnosticLog = diag) }

    val state: StateFlow<SettingsUiState> = combine(sound, look, scale, library, storage.state) { s, l, sc, lib, card ->
        SettingsUiState(
            compatibleSound = s.compatible,
            crossfadeSeconds = s.crossfade,
            normalizeVolume = s.normalize,
            separateAudioSessions = s.separateSessions,
            announceEqualizer = s.announce,
            equalizerAvailable = equalizer.isAvailable(),
            startMusic = s.startMusic,
            bluetoothAutoplay = s.bluetooth,
            skipExplicit = s.explicit,
            listeningHistory = s.history,
            reserveMb = lib.reserveMb,
            reserveUsedBytes = lib.reserveUsed,
            autoDownload = lib.autoDownload,
            cacheSizeBytes = lib.cacheBytes,
            journal = lib.journal,
            diagnosticLog = lib.diagnosticLog,
            storage = card,
            startPage = l.startPage,
            expandPlayerOnPlay = l.expand,
            swipeToCloseMiniPlayer = l.swipe,
            steeringWheelOnLeft = l.left,
            carClock = l.clock,
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
    fun setSeparateAudioSessions(enabled: Boolean) = save { setSeparateAudioSessions(enabled) }
    fun setAnnounceEqualizer(enabled: Boolean) = save { setAnnounceEqualizer(enabled) }
    fun openEqualizer() = equalizer.open()
    fun clearJournal() = journal.clear()
    fun setDiagnosticLog(enabled: Boolean) = save { setDiagnosticLog(enabled) }
    fun clearDiagnosticLog() = diagnosticLog.clear()
    fun shareDiagnosticLog() {
        viewModelScope.launch { diagnosticLog.share() }
    }

    /** The journal as text, to a messenger or mail of the user's choice. */
    fun shareJournal(text: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
        runCatching {
            context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { android.util.Log.w("SettingsViewModel", "Journal not shared", it) }
    }
    fun setStartMusic(value: StartMusic) = save { setStartMusic(value) }
    fun setBluetoothAutoplay(enabled: Boolean) = save { setBluetoothAutoplay(enabled) }
    fun setSkipExplicit(enabled: Boolean) = save { setSkipExplicit(enabled) }
    fun setListeningHistory(enabled: Boolean) = save { setListeningHistory(enabled) }
    fun setReserveMb(megabytes: Int) = save { setRoadReserveMb(megabytes) }
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
    fun setUiScaleAuto(enabled: Boolean) = save { setUiScaleAuto(enabled) }
    fun setUiScaleManual(scale: Float) = save { setUiScaleManual(scale) }
    fun setAnimationMode(value: AnimationMode) = save { setAnimationMode(value) }
}
