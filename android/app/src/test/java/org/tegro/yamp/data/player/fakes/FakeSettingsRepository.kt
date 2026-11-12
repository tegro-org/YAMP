package org.tegro.yamp.data.player.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import org.tegro.yamp.domain.model.AnimationMode
import org.tegro.yamp.domain.model.StartMusic
import org.tegro.yamp.domain.model.StartPage
import org.tegro.yamp.domain.repository.SettingsRepository

/** Every setting as a state flow the test can change; defaults as in the app, except no road reserve and no log file. */
class FakeSettingsRepository : SettingsRepository {
    override val isDarkTheme = MutableStateFlow(true)
    override val autoDownloadLikedTracks = MutableStateFlow(false)
    override val crossfadeSeconds = MutableStateFlow(SettingsRepository.DEFAULT_CROSSFADE_SECONDS)
    override val steeringWheelOnLeft = MutableStateFlow(SettingsRepository.DEFAULT_STEERING_WHEEL_ON_LEFT)
    override val uiScaleAuto = MutableStateFlow(SettingsRepository.DEFAULT_UI_SCALE_AUTO)
    override val uiScaleManual = MutableStateFlow(SettingsRepository.DEFAULT_UI_SCALE)
    override val skippedUpdateVersion = MutableStateFlow<String?>(null)
    /** 0: the reserve keeper stays idle; the road reserve has its own tests. */
    override val roadReserveMb = MutableStateFlow(0)
    override val compatibleSound = MutableStateFlow<Boolean?>(null)
    override val startMusic = MutableStateFlow(StartMusic.OFF)
    override val startPage = MutableStateFlow(StartPage.LAST)
    override val lastTab = MutableStateFlow<String?>(null)
    override val expandPlayerOnPlay = MutableStateFlow(false)
    override val swipeToCloseMiniPlayer = MutableStateFlow(false)
    override val carClock = MutableStateFlow(false)
    override val animationMode = MutableStateFlow(AnimationMode.AUTO)
    override val normalizeVolume = MutableStateFlow(false)
    override val bluetoothAutoplay = MutableStateFlow(false)
    override val skipExplicit = MutableStateFlow(false)
    override val downloadsOnSdCard = MutableStateFlow(false)
    override val separateAudioSessions = MutableStateFlow(false)
    override val diagnosticLog = MutableStateFlow(false)
    override val announceEqualizer = MutableStateFlow(true)
    override val listeningHistory = MutableStateFlow(true)

    override suspend fun setDarkTheme(enabled: Boolean) { isDarkTheme.value = enabled }
    override suspend fun setAutoDownloadLikedTracks(enabled: Boolean) { autoDownloadLikedTracks.value = enabled }
    override suspend fun setCrossfadeSeconds(seconds: Int) { crossfadeSeconds.value = seconds }
    override suspend fun setSteeringWheelOnLeft(onLeft: Boolean) { steeringWheelOnLeft.value = onLeft }
    override suspend fun setUiScaleAuto(enabled: Boolean) { uiScaleAuto.value = enabled }
    override suspend fun setUiScaleManual(scale: Float) { uiScaleManual.value = scale }
    override suspend fun setSkippedUpdateVersion(version: String?) { skippedUpdateVersion.value = version }
    override suspend fun setRoadReserveMb(megabytes: Int) { roadReserveMb.value = megabytes }
    override suspend fun setCompatibleSound(enabled: Boolean) { compatibleSound.value = enabled }
    override suspend fun setStartMusic(value: StartMusic) { startMusic.value = value }
    override suspend fun setStartPage(value: StartPage) { startPage.value = value }
    override suspend fun setLastTab(route: String) { lastTab.value = route }
    override suspend fun setExpandPlayerOnPlay(enabled: Boolean) { expandPlayerOnPlay.value = enabled }
    override suspend fun setSwipeToCloseMiniPlayer(enabled: Boolean) { swipeToCloseMiniPlayer.value = enabled }
    override suspend fun setCarClock(enabled: Boolean) { carClock.value = enabled }
    override suspend fun setAnimationMode(value: AnimationMode) { animationMode.value = value }
    override suspend fun setNormalizeVolume(enabled: Boolean) { normalizeVolume.value = enabled }
    override suspend fun setBluetoothAutoplay(enabled: Boolean) { bluetoothAutoplay.value = enabled }
    override suspend fun setSkipExplicit(enabled: Boolean) { skipExplicit.value = enabled }
    override suspend fun setDownloadsOnSdCard(enabled: Boolean) { downloadsOnSdCard.value = enabled }
    override suspend fun setSeparateAudioSessions(enabled: Boolean) { separateAudioSessions.value = enabled }
    override suspend fun setDiagnosticLog(enabled: Boolean) { diagnosticLog.value = enabled }
    override suspend fun setAnnounceEqualizer(enabled: Boolean) { announceEqualizer.value = enabled }
    override suspend fun setListeningHistory(enabled: Boolean) { listeningHistory.value = enabled }
}
