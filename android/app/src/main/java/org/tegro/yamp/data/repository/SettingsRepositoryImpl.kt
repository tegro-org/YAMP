package org.tegro.yamp.data.repository

import org.tegro.yamp.domain.model.StartPage
import org.tegro.yamp.domain.model.StartMusic
import org.tegro.yamp.domain.model.AnimationMode
import androidx.datastore.preferences.core.stringPreferencesKey
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import org.tegro.yamp.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepositoryImpl @Inject constructor(
    private val context: Context
) : SettingsRepository {

    /** Defaults of the settings declared through [bool] / [choice] and the keys below: the journal lists them too. */
    private val defaults = linkedMapOf(
        "dark_theme" to "true",
        "auto_download_liked" to "false",
        "road_reserve_mb" to SettingsRepository.DEFAULT_ROAD_RESERVE_MB.toString(),
        "crossfade_seconds" to SettingsRepository.DEFAULT_CROSSFADE_SECONDS.toString(),
        "stream_cache_mb" to SettingsRepository.DEFAULT_STREAM_CACHE_MB.toString(),
    )

    override val allSettings: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        defaults + prefs.asMap().entries.associate { (key, value) -> key.name to value.toString() }
    }

    private val DARK_THEME_KEY = booleanPreferencesKey("dark_theme")
    private val AUTO_DOWNLOAD_KEY = booleanPreferencesKey("auto_download_liked")
    private val ROAD_RESERVE_MB_KEY = androidx.datastore.preferences.core.intPreferencesKey("road_reserve_mb")
    private val CROSSFADE_SECONDS_KEY = androidx.datastore.preferences.core.intPreferencesKey("crossfade_seconds")
    private val STREAM_CACHE_MB_KEY = androidx.datastore.preferences.core.intPreferencesKey("stream_cache_mb")

    override val isDarkTheme: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DARK_THEME_KEY] ?: true
    }

    override val roadReserveMb: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[ROAD_RESERVE_MB_KEY] ?: SettingsRepository.DEFAULT_ROAD_RESERVE_MB
    }

    override val autoDownloadLikedTracks: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_DOWNLOAD_KEY] ?: false
    }

    override val crossfadeSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[CROSSFADE_SECONDS_KEY] ?: SettingsRepository.DEFAULT_CROSSFADE_SECONDS
    }

    private val STEERING_WHEEL_ON_LEFT_KEY = booleanPreferencesKey("steering_wheel_on_left")
    override val steeringWheelOnLeft: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[STEERING_WHEEL_ON_LEFT_KEY] ?: SettingsRepository.DEFAULT_STEERING_WHEEL_ON_LEFT
    }

    override suspend fun setSteeringWheelOnLeft(onLeft: Boolean) {
        context.dataStore.edit { prefs -> prefs[STEERING_WHEEL_ON_LEFT_KEY] = onLeft }
    }

    private val UI_SCALE_AUTO_KEY = booleanPreferencesKey("ui_scale_auto")
    override val uiScaleAuto: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[UI_SCALE_AUTO_KEY] ?: SettingsRepository.DEFAULT_UI_SCALE_AUTO
    }

    override suspend fun setUiScaleAuto(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[UI_SCALE_AUTO_KEY] = enabled }
    }

    private val UI_SCALE_MANUAL_KEY = floatPreferencesKey("ui_scale_manual")
    override val uiScaleManual: Flow<Float> = context.dataStore.data.map { prefs ->
        (prefs[UI_SCALE_MANUAL_KEY] ?: SettingsRepository.DEFAULT_UI_SCALE)
            .coerceIn(SettingsRepository.MIN_UI_SCALE, SettingsRepository.MAX_UI_SCALE)
    }

    override suspend fun setUiScaleManual(scale: Float) {
        context.dataStore.edit { prefs ->
            prefs[UI_SCALE_MANUAL_KEY] = scale.coerceIn(SettingsRepository.MIN_UI_SCALE, SettingsRepository.MAX_UI_SCALE)
        }
    }

    private val SKIPPED_UPDATE_KEY = stringPreferencesKey("skipped_update_version")
    override val skippedUpdateVersion: Flow<String?> = context.dataStore.data.map { prefs -> prefs[SKIPPED_UPDATE_KEY] }

    override suspend fun setSkippedUpdateVersion(version: String?) {
        context.dataStore.edit { prefs ->
            if (version == null) prefs.remove(SKIPPED_UPDATE_KEY) else prefs[SKIPPED_UPDATE_KEY] = version
        }
    }

    override suspend fun setCrossfadeSeconds(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[CROSSFADE_SECONDS_KEY] = seconds.coerceIn(0, SettingsRepository.MAX_CROSSFADE_SECONDS)
        }
    }

    override suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[DARK_THEME_KEY] = enabled
        }
    }

    private val COMPATIBLE_SOUND_KEY = booleanPreferencesKey("compatible_sound")
    override val compatibleSound: Flow<Boolean?> = context.dataStore.data.map { prefs -> prefs[COMPATIBLE_SOUND_KEY] }

    override suspend fun setCompatibleSound(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[COMPATIBLE_SOUND_KEY] = enabled }
    }

    override val accountQualities: Flow<Map<String, Boolean>> = context.dataStore.data.map { prefs ->
        prefs.asMap().entries
            .filter { (key, value) -> key.name.startsWith("quality|") && value is Boolean }
            .associate { (key, value) -> key.name to (value as Boolean) }
    }

    override suspend fun setAccountQuality(account: String, metered: Boolean, compatible: Boolean) {
        val key = booleanPreferencesKey(SettingsRepository.accountQualityKey(account, metered))
        context.dataStore.edit { prefs -> prefs[key] = compatible }
    }

    override val streamCacheMb: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[STREAM_CACHE_MB_KEY] ?: SettingsRepository.DEFAULT_STREAM_CACHE_MB
    }

    override suspend fun setStreamCacheMb(megabytes: Int) {
        context.dataStore.edit { prefs ->
            prefs[STREAM_CACHE_MB_KEY] = megabytes.coerceAtLeast(0)
        }
    }

    override suspend fun setRoadReserveMb(megabytes: Int) {
        context.dataStore.edit { prefs ->
            prefs[ROAD_RESERVE_MB_KEY] = megabytes
        }
    }

    override suspend fun setAutoDownloadLikedTracks(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_DOWNLOAD_KEY] = enabled
        }
    }

    private fun bool(name: String, default: Boolean): Pair<Flow<Boolean>, suspend (Boolean) -> Unit> {
        val key = booleanPreferencesKey(name)
        defaults[name] = default.toString()
        return context.dataStore.data.map { it[key] ?: default } to { value -> context.dataStore.edit { it[key] = value } }
    }

    private inline fun <reified E : Enum<E>> choice(name: String, default: E): Pair<Flow<E>, suspend (E) -> Unit> {
        val key = stringPreferencesKey(name)
        defaults[name] = default.name
        return context.dataStore.data.map { prefs -> enumValues<E>().firstOrNull { it.name == prefs[key] } ?: default } to
            { value -> context.dataStore.edit { it[key] = value.name } }
    }

    private val startMusicPref = choice("start_music", StartMusic.OFF)
    override val startMusic = startMusicPref.first
    override suspend fun setStartMusic(value: StartMusic) = startMusicPref.second(value)

    private val startPagePref = choice("start_page", StartPage.WAVE)
    override val startPage = startPagePref.first
    override suspend fun setStartPage(value: StartPage) = startPagePref.second(value)

    private val LAST_TAB_KEY = stringPreferencesKey("last_tab")
    override val lastTab: Flow<String?> = context.dataStore.data.map { it[LAST_TAB_KEY] }
    override suspend fun setLastTab(route: String) {
        context.dataStore.edit { it[LAST_TAB_KEY] = route }
    }

    private val expandPlayerPref = bool("expand_player_on_play", false)
    override val expandPlayerOnPlay = expandPlayerPref.first
    override suspend fun setExpandPlayerOnPlay(enabled: Boolean) = expandPlayerPref.second(enabled)

    private val swipeToClosePref = bool("swipe_to_close_mini_player", true)
    override val swipeToCloseMiniPlayer = swipeToClosePref.first
    override suspend fun setSwipeToCloseMiniPlayer(enabled: Boolean) = swipeToClosePref.second(enabled)

    private val carClockPref = bool("car_clock", true)
    override val carClock = carClockPref.first
    override suspend fun setCarClock(enabled: Boolean) = carClockPref.second(enabled)

    private val animationPref = choice("animation_mode", AnimationMode.AUTO)
    override val animationMode = animationPref.first
    override suspend fun setAnimationMode(value: AnimationMode) = animationPref.second(value)

    private val normalizePref = bool("normalize_volume", false)
    override val normalizeVolume = normalizePref.first
    override suspend fun setNormalizeVolume(enabled: Boolean) = normalizePref.second(enabled)

    private val bluetoothPref = bool("bluetooth_autoplay", false)
    override val bluetoothAutoplay = bluetoothPref.first
    override suspend fun setBluetoothAutoplay(enabled: Boolean) = bluetoothPref.second(enabled)

    private val explicitPref = bool("skip_explicit", false)
    override val skipExplicit = explicitPref.first
    override suspend fun setSkipExplicit(enabled: Boolean) = explicitPref.second(enabled)

    private val sdCardPref = bool("downloads_on_sd_card", false)
    override val downloadsOnSdCard = sdCardPref.first
    override suspend fun setDownloadsOnSdCard(enabled: Boolean) = sdCardPref.second(enabled)

    // Retired switches, stored values simply never read again: "separate_audio_sessions" (D45, by D70);
    // "announce_equalizer", "keep_audio_streams", "keep_both_players_running", "fixed_output_format" (2026-11-18:
    // always announce, never park or idle on silence, always the mixer rate)

    private val duckAnnouncementsPref = bool("duck_for_announcements", false)
    override val duckForAnnouncements = duckAnnouncementsPref.first
    override suspend fun setDuckForAnnouncements(enabled: Boolean) = duckAnnouncementsPref.second(enabled)

    private val resumeWhenQuietPref = bool("resume_when_quiet", false)
    override val resumeWhenQuiet = resumeWhenQuietPref.first
    override suspend fun setResumeWhenQuiet(enabled: Boolean) = resumeWhenQuietPref.second(enabled)

    private val fadeThenRisePref = bool("transition_fade_then_rise", false)
    override val transitionFadeThenRise = fadeThenRisePref.first
    override suspend fun setTransitionFadeThenRise(enabled: Boolean) = fadeThenRisePref.second(enabled)

    private val playerCarLayoutPref = bool("player_car_layout", false)
    override val playerCarLayout = playerCarLayoutPref.first
    override suspend fun setPlayerCarLayout(enabled: Boolean) = playerCarLayoutPref.second(enabled)

    private val playerNextThreePref = bool("player_next_three", false)
    override val playerNextThree = playerNextThreePref.first
    override suspend fun setPlayerNextThree(enabled: Boolean) = playerNextThreePref.second(enabled)

    private val likeGesturesPref = bool("like_gestures", false)
    override val likeGestures = likeGesturesPref.first
    override suspend fun setLikeGestures(enabled: Boolean) = likeGesturesPref.second(enabled)

    private val groupQueuesPref = bool("group_queues_by_account", true)
    override val groupQueuesByAccount = groupQueuesPref.first
    override suspend fun setGroupQueuesByAccount(enabled: Boolean) = groupQueuesPref.second(enabled)

    private val diagnosticLogPref = bool("diagnostic_log", org.tegro.yamp.BuildConfig.DEBUG)
    override val diagnosticLog = diagnosticLogPref.first
    override suspend fun setDiagnosticLog(enabled: Boolean) = diagnosticLogPref.second(enabled)

    private val historyPref = bool("listening_history", true)
    override val listeningHistory = historyPref.first
    override suspend fun setListeningHistory(enabled: Boolean) = historyPref.second(enabled)
}
