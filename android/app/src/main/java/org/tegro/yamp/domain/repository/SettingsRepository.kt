package org.tegro.yamp.domain.repository

import org.tegro.yamp.domain.model.StartPage
import org.tegro.yamp.domain.model.StartMusic
import org.tegro.yamp.domain.model.AnimationMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val isDarkTheme: Flow<Boolean>
    val autoDownloadLikedTracks: Flow<Boolean>
    /** Crossfade between tracks in seconds; 0 turns it off. */
    val crossfadeSeconds: Flow<Int>
    /**
     * Steering wheel side, for the car-mode landscape layout. True (left-hand drive) puts the
     * controls on the left (near the driver) and the cover on the right; false mirrors it.
     */
    val steeringWheelOnLeft: Flow<Boolean>
    /** Auto UI scale: the app picks the scale from the screen metrics instead of [uiScaleManual]. */
    val uiScaleAuto: Flow<Boolean>
    /** Manual UI-scale multiplier used when [uiScaleAuto] is off ([MIN_UI_SCALE]…[MAX_UI_SCALE]). */
    val uiScaleManual: Flow<Float>
    /** The update the user chose to skip ("1.9.3"); the launch check does not offer it again. */
    val skippedUpdateVersion: Flow<String?>
    /** The road reserve in megabytes, one of [ROAD_RESERVE_SIZES_MB]; 0 turns it off. */
    val roadReserveMb: Flow<Int>

    suspend fun setDarkTheme(enabled: Boolean)
    suspend fun setAutoDownloadLikedTracks(enabled: Boolean)
    suspend fun setCrossfadeSeconds(seconds: Int)
    suspend fun setSteeringWheelOnLeft(onLeft: Boolean)
    suspend fun setUiScaleAuto(enabled: Boolean)
    suspend fun setUiScaleManual(scale: Float)
    suspend fun setSkippedUpdateVersion(version: String?)
    suspend fun setRoadReserveMb(megabytes: Int)

    /** True: only the ordinary stream, for a device that plays FLAC in silence. Null: nothing chosen yet. */
    val compatibleSound: Flow<Boolean?>
    suspend fun setCompatibleSound(enabled: Boolean)

    /** What plays by itself when the app starts. */
    val startMusic: Flow<StartMusic>
    suspend fun setStartMusic(value: StartMusic)

    /** Which page the app opens on; [lastTab] is remembered for [StartPage.LAST]. */
    val startPage: Flow<StartPage>
    suspend fun setStartPage(value: StartPage)
    val lastTab: Flow<String?>
    suspend fun setLastTab(route: String)

    /** Open the full player when the user starts music from a list. */
    val expandPlayerOnPlay: Flow<Boolean>
    suspend fun setExpandPlayerOnPlay(enabled: Boolean)

    /** Swiping the mini-player away stops the music. */
    val swipeToCloseMiniPlayer: Flow<Boolean>
    suspend fun setSwipeToCloseMiniPlayer(enabled: Boolean)

    /** The clock and date in the car mode. */
    val carClock: Flow<Boolean>
    suspend fun setCarClock(enabled: Boolean)

    val animationMode: Flow<AnimationMode>
    suspend fun setAnimationMode(value: AnimationMode)

    /** Quiet the tracks louder than the target so all of them sound alike. */
    val normalizeVolume: Flow<Boolean>
    suspend fun setNormalizeVolume(enabled: Boolean)

    /** Start the music when the phone connects to a Bluetooth audio device (the car). */
    val bluetoothAutoplay: Flow<Boolean>
    suspend fun setBluetoothAutoplay(enabled: Boolean)

    /** Skip tracks the service marks as explicit in the wave and when moving on by itself. */
    val skipExplicit: Flow<Boolean>
    suspend fun setSkipExplicit(enabled: Boolean)

    /** Downloads on the removable SD card (encrypted) instead of the internal storage. */
    val downloadsOnSdCard: Flow<Boolean>
    suspend fun setDownloadsOnSdCard(enabled: Boolean)

    /**
     * Each of the two players in its own audio session instead of one shared session. Off = shared (the
     * system equalizer shapes both tracks of a crossfade as one). On = separate: for a head unit whose sound
     * chain applies one volume per session and blurs the crossfade.
     */
    val separateAudioSessions: Flow<Boolean>
    suspend fun setSeparateAudioSessions(enabled: Boolean)

    /**
     * Tell equalizer apps about our audio sessions (the OPEN_AUDIO_EFFECT_CONTROL_SESSION broadcast). Off for a
     * head unit whose sound chain takes audio focus when it attaches to the session — the music paused by itself
     * a second after every start on the user's unit (diagnosed from the log, 2026-11-12).
     */
    val announceEqualizer: Flow<Boolean>
    suspend fun setAnnounceEqualizer(enabled: Boolean)

    /**
     * Keep the two players' audio streams open between tracks (a finished player is parked on a silent clip
     * instead of being stopped). An experiment for a head unit that takes the sound whenever a new stream
     * appears (2026-11-13).
     */
    val keepAudioStreams: Flow<Boolean>
    suspend fun setKeepAudioStreams(enabled: Boolean)

    /** The diagnostic log file (Settings → About). Default: on in debug builds, off in release. */
    val diagnosticLog: Flow<Boolean>
    suspend fun setDiagnosticLog(enabled: Boolean)

    /** Record every track that sounded in Library → History (D46). Off: nothing is written; the list stays. */
    val listeningHistory: Flow<Boolean>
    suspend fun setListeningHistory(enabled: Boolean)

    companion object {
        const val DEFAULT_CROSSFADE_SECONDS = 3
        const val MAX_CROSSFADE_SECONDS = 12
        /** Left-hand drive is the global majority, so it is the default. */
        const val DEFAULT_STEERING_WHEEL_ON_LEFT = true
        /** UI scale defaults to automatic so misreported head-unit screens are corrected out of the box. */
        const val DEFAULT_UI_SCALE_AUTO = true
        const val DEFAULT_UI_SCALE = 1.0f
        const val MIN_UI_SCALE = 0.5f
        const val MAX_UI_SCALE = 2.0f
        const val DEFAULT_ROAD_RESERVE_MB = 100
        val ROAD_RESERVE_SIZES_MB = listOf(0, 100, 300, 500, 1024)
    }
}
