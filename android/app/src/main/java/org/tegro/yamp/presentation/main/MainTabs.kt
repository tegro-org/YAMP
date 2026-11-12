package org.tegro.yamp.presentation.main

import org.tegro.yamp.data.player.PlaybackQueue
import org.tegro.yamp.presentation.main.components.SettingsActions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.player.PlaylistSource
import org.tegro.yamp.domain.model.RadioCategory
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.presentation.account.AccountViewModel
import org.tegro.yamp.presentation.car.CarScreen
import org.tegro.yamp.presentation.hifi.PanelSide
import org.tegro.yamp.presentation.queues.LocalQueueSwitcher
import org.tegro.yamp.presentation.queues.QueueSwitcherRequest
import org.tegro.yamp.presentation.catalog.CatalogViewModel
import org.tegro.yamp.presentation.crash.CrashLogViewModel
import org.tegro.yamp.presentation.radio.RadioScreen
import org.tegro.yamp.presentation.radio.RadioViewModel
import org.tegro.yamp.presentation.update.UpdateViewModel

/**
 * What every tab needs to know about playback. Each tab collects the rest of its state itself, so a
 * change on one tab (search results, a download's progress) recomposes only that tab, not the app.
 */
@Immutable
data class NowPlaying(
    val track: TrackDetailsDto?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val source: PlaylistSource,
    val likedTrackIds: Set<String>
) {
    val trackId: String? get() = track?.idString
    val hasTrack: Boolean get() = track != null
    /** The wave is what plays (or is paused) right now. */
    val waveActive: Boolean get() = source == PlaylistSource.VIBE && track != null
}

/** The tracks after the current one (at most [limit]). Pure; unit-tested. */
fun upcomingOf(queue: List<TrackDetailsDto>, currentId: String?, limit: Int = 10): List<TrackDetailsDto> {
    val index = queue.indexOfFirst { it.idString == currentId }
    return if (index < 0) emptyList() else queue.drop(index + 1).take(limit)
}

/**
 * The first tab shows what the player holds (D49): the wave on the wave's page; on a queue's page, the queue's
 * track — a live station plays beside the queues (D41) and is not it. Then the page is the player: the
 * mini-player stays away and the knob toggles. Pure; unit-tested.
 */
fun homePagePlays(mode: HomeMode, now: NowPlaying): Boolean = when (mode) {
    HomeMode.Wave -> now.waveActive
    is HomeMode.Queue -> now.track != null && !now.track.isRadio
}

@Composable
internal fun HomeTab(
    viewModel: MainViewModel,
    now: NowPlaying,
    progress: () -> Long,
    isLandscape: Boolean,
    contentBottom: Dp,
    onOpenPlayer: (() -> Unit)?
) {
    val queue by viewModel.currentPlaylist.collectAsStateWithLifecycle()
    val wavePreview by viewModel.wavePreview.collectAsStateWithLifecycle()
    val isFetchingWave by viewModel.isFetchingMoreQueue.collectAsStateWithLifecycle()
    val active by viewModel.activeQueue.collectAsStateWithLifecycle()
    val mode = remember(active) { homeModeOf(active) }
    val wavePage = mode is HomeMode.Wave
    val pagePlays = homePagePlays(mode, now)
    val upcoming = remember(queue, now.trackId) { upcomingOf(queue, now.trackId) }
    val shelf = remember(mode, active, pagePlays, upcoming, wavePreview) {
        val queuePage = active?.takeIf { !wavePage }
        if (queuePage == null) waveShelfOf(pagePlays, upcoming, wavePreview) else queueShelfOf(queuePage, pagePlays)
    }
    val tuning by viewModel.waveTuning.collectAsStateWithLifecycle()
    val seed by viewModel.waveSeed.collectAsStateWithLifecycle()
    val queues by viewModel.queues.collectAsStateWithLifecycle()
    var tuneOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (tuneOpen) {
        org.tegro.yamp.presentation.main.components.WaveTuneSheet(tuning, onChange = viewModel::setWaveTuning, onDismiss = { tuneOpen = false })
    }
    HomeScreen(
        waveTrack = if (pagePlays) now.track else null,
        isPlaying = now.isPlaying,
        // The ring loads the page's own music: the wave, or the queue's track (never a station's stream)
        isLoading = now.isBuffering && if (wavePage) now.source == PlaylistSource.VIBE else now.source != PlaylistSource.RADIO,
        isLiked = now.trackId?.let { it in now.likedTrackIds } == true,
        progress = progress,
        shelf = shelf,
        shimmer = isFetchingWave || now.isBuffering,
        // Like Yandex Music: the knob controls what the page shows; from any other context it starts it
        onPlayPause = { viewModel.homeKnob(wavePage, pagePlays) },
        onLike = { now.track?.let { viewModel.toggleLike(it) } },
        onDislike = { now.track?.let { viewModel.dislikeTrack(it) } },
        onOpenPlayer = onOpenPlayer,
        onShelfClick = { track ->
            val queuePage = active?.takeIf { !wavePage }
            when {
                queuePage != null -> shelfEntryOf(queuePage, track.idString)?.let { viewModel.playEntry(it.entryId) }
                pagePlays -> viewModel.playTrack(track.idString, queue, PlaylistSource.VIBE)
                else -> viewModel.playWaveFrom(track.idString)
            }
        },
        contentBottom = if (pagePlays && !isLandscape) 100.dp else contentBottom,
        tuning = tuning,
        trackWaveSeed = seed?.takeIf { wavePage && pagePlays },
        onTune = { tuneOpen = true },
        onBackToMyWave = viewModel::returnToMyWave,
        queueCount = queues.size,
        mode = mode
    )
}

@Composable
internal fun SearchTab(
    viewModel: MainViewModel,
    searchVm: SearchViewModel,
    catalogVm: CatalogViewModel,
    now: NowPlaying
) {
    val query by searchVm.query.collectAsStateWithLifecycle()
    val results by searchVm.results.collectAsStateWithLifecycle()
    val isSearching by searchVm.isSearching.collectAsStateWithLifecycle()
    val found by searchVm.found.collectAsStateWithLifecycle()
    val history by searchVm.history.collectAsStateWithLifecycle()
    val weeklyHits by searchVm.weeklyHits.collectAsStateWithLifecycle()
    val trendTracks by searchVm.trendTracks.collectAsStateWithLifecycle()
    SearchScreen(
        query = query,
        onQueryChange = { searchVm.onQueryChange(it) },
        results = results,
        weeklyHits = weeklyHits,
        trendTracks = trendTracks,
        isSearching = isSearching,
        currentTrackId = now.trackId,
        isPlaying = now.isPlaying,
        likedTrackIds = now.likedTrackIds,
        onTrackClick = { id, playlist ->
            searchVm.rememberQuery()
            viewModel.playTrack(id, playlist, PlaylistSource.SEARCH)
        },
        onToggleLike = { track -> viewModel.toggleLike(track) },
        onLoadMoreHits = { searchVm.loadMoreHits() },
        onLoadMoreTrends = { searchVm.loadMoreTrends() },
        hasActiveTrack = now.hasTrack,
        artists = found.artists,
        albums = found.albums,
        history = history,
        onArtistClick = { artist ->
            val id = artist.idString ?: return@SearchScreen
            searchVm.rememberQuery()
            catalogVm.openArtist(id, artist.name.orEmpty())
        },
        onAlbumClick = { album ->
            val id = album.idString ?: return@SearchScreen
            searchVm.rememberQuery()
            catalogVm.openAlbum(id, album.title.orEmpty())
        },
        onRemoveFromHistory = { searchVm.removeFromHistory(it) },
        onClearHistory = { searchVm.clearHistory() },
        onSubmit = { searchVm.rememberQuery() }
    )
}

@Composable
internal fun LibraryTab(
    viewModel: MainViewModel,
    accountVm: AccountViewModel,
    playlistsVm: PlaylistsViewModel,
    updateVm: UpdateViewModel,
    crashVm: CrashLogViewModel,
    now: NowPlaying,
    onOpenAccounts: () -> Unit,
    onOpenCrashLog: () -> Unit,
    onAddAccount: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
    historyVm: org.tegro.yamp.presentation.history.HistoryViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val historyState by historyVm.state.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadFailed by viewModel.libraryLoadFailed.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadAllState.collectAsStateWithLifecycle()
    val downloadedTrackIds by viewModel.downloadedTrackIds.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val settingsState by settingsVm.state.collectAsStateWithLifecycle()
    val activeAccount by accountVm.activeAccount.collectAsStateWithLifecycle()
    val accountName by accountVm.accountName.collectAsStateWithLifecycle()
    val accountAvatarUrl by accountVm.accountAvatarUrl.collectAsStateWithLifecycle()
    val defaultAccountName by accountVm.defaultAccountName.collectAsStateWithLifecycle()
    val updateStatusMessage by updateVm.statusMessage.collectAsStateWithLifecycle()
    val crashReports by crashVm.reports.collectAsStateWithLifecycle()
    val userPlaylists by playlistsVm.playlists.collectAsStateWithLifecycle()
    val openedPlaylist by playlistsVm.opened.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (viewModel.tracks.value.isEmpty()) viewModel.loadLikedTracks()
    }

    val settings = settingsState.copy(
        accountName = accountName,
        avatarUrl = accountAvatarUrl,
        hasPlus = activeAccount?.hasPlus == true,
        defaultAccountName = defaultAccountName,
        updateStatus = updateStatusMessage,
        crashReports = crashReports.size
    )
    val settingsActions = remember(settingsVm, accountVm, updateVm, viewModel) {
        SettingsActions(
            onAccountManagement = onOpenAccounts,
            onAddAccount = onAddAccount,
            onLogout = { accountVm.logoutCurrentAccount() },
            onCheckForUpdates = { updateVm.checkForUpdates(silent = false) },
            onOpenCrashLog = onOpenCrashLog,
            onShareJournal = settingsVm::shareJournal,
            onClearJournal = settingsVm::clearJournal,
            onDiagnosticLogChange = settingsVm::setDiagnosticLog,
            onShareDiagnosticLog = settingsVm::shareDiagnosticLog,
            onClearDiagnosticLog = settingsVm::clearDiagnosticLog,
            onCompatibleSoundChange = settingsVm::setCompatibleSound,
            onCrossfadeChange = settingsVm::setCrossfade,
            onSeparateAudioSessionsChange = settingsVm::setSeparateAudioSessions,
            onAnnounceEqualizerChange = settingsVm::setAnnounceEqualizer,
            onNormalizeVolumeChange = settingsVm::setNormalizeVolume,
            onOpenEqualizer = settingsVm::openEqualizer,
            onStartMusicChange = settingsVm::setStartMusic,
            onBluetoothAutoplayChange = settingsVm::setBluetoothAutoplay,
            onSkipExplicitChange = settingsVm::setSkipExplicit,
            onListeningHistoryChange = settingsVm::setListeningHistory,
            onReserveChange = settingsVm::setReserveMb,
            onClearReserve = settingsVm::clearReserve,
            onAutoDownloadChange = settingsVm::setAutoDownload,
            onDownloadAll = { viewModel.downloadAllLikedTracks() },
            onUpgradeDownloads = settingsVm::upgradeDownloads,
            onDownloadsOnCardChange = settingsVm::setDownloadsOnCard,
            onClearDownloads = { viewModel.clearMusicCache() },
            onStartPageChange = settingsVm::setStartPage,
            onExpandPlayerChange = settingsVm::setExpandPlayer,
            onSwipeToCloseChange = settingsVm::setSwipeToClose,
            onSteeringWheelChange = settingsVm::setSteeringWheelOnLeft,
            onCarClockChange = settingsVm::setCarClock,
            onUiScaleAutoChange = settingsVm::setUiScaleAuto,
            onUiScaleManualChange = settingsVm::setUiScaleManual,
            onAnimationModeChange = settingsVm::setAnimationMode
        )
    }

    LibraryScreen(
        tracks = tracks,
        isLoading = isLoading,
        loadFailed = loadFailed,
        downloadState = downloadState,
        currentTrackId = now.trackId,
        isPlaying = now.isPlaying,
        likedTrackIds = now.likedTrackIds,
        downloadedTrackIds = downloadedTrackIds,
        downloadProgress = downloadProgress,
        onTrackClick = { id -> viewModel.playTrack(id, tracks, PlaylistSource.LIBRARY) },
        onPlayAllClick = { viewModel.playAllLikedTracks() },
        onShuffleClick = { viewModel.shuffleLikedTracks() },
        onDownloadAllClick = { viewModel.downloadAllLikedTracks() },
        onCancelDownload = { viewModel.cancelDownloadAll() },
        settings = settings,
        settingsActions = settingsActions,
        onToggleLike = { track -> viewModel.toggleLike(track) },
        onRefresh = { viewModel.loadLikedTracks() },
        hasActiveTrack = now.hasTrack,
        playlists = userPlaylists,
        openedPlaylist = openedPlaylist,
        onOpenPlaylist = { playlistsVm.open(it) },
        onClosePlaylist = { playlistsVm.close() },
        onPlayPlaylist = { playlistsVm.play(it) },
        onPlaylistTrackClick = { playlistsVm.playTrack(it) },
        onCreatePlaylist = { playlistsVm.create(it) },
        onRenamePlaylist = { playlist, title -> playlistsVm.rename(playlist, title) },
        onDeletePlaylist = { playlistsVm.delete(it) },
        onReorderPlaylist = { playlist, shown, new -> playlistsVm.reorder(playlist, shown, new) },
        onSetPlaylistPublic = { playlist, public -> playlistsVm.setPublic(playlist, public) },
        onAddToEnd = viewModel::addToEnd,
        historyCount = historyState.weekCount,
        historyPage = { modifier, contentBottom, onBack ->
            org.tegro.yamp.presentation.history.HistoryScreen(
                state = historyState,
                currentTrackId = now.trackId,
                isPlaying = now.isPlaying,
                likedTrackIds = now.likedTrackIds,
                downloadedTrackIds = downloadedTrackIds,
                contentBottom = contentBottom,
                onBack = onBack,
                onOpened = historyVm::opened,
                onSelectFilter = historyVm::select,
                onTrackClick = viewModel::playNowKeepQueue,
                onToggleLike = viewModel::toggleLike,
                onClear = historyVm::clear,
                modifier = modifier
            )
        }
    )
}

@Composable
internal fun RadioTab(viewModel: MainViewModel, radioVm: RadioViewModel, now: NowPlaying) {
    val stations by radioVm.stations.collectAsStateWithLifecycle()
    val favoriteStationIds by radioVm.favoriteStationIds.collectAsStateWithLifecycle()
    var selectedCategory by rememberSaveable { mutableStateOf(RadioCategory.ALL) }
    val filtered = remember(stations, favoriteStationIds, selectedCategory) {
        stations.filter {
            selectedCategory == RadioCategory.ALL ||
                (selectedCategory == RadioCategory.FAVORITES && it.id in favoriteStationIds) ||
                it.category == selectedCategory
        }
    }
    RadioScreen(
        stations = filtered,
        allStations = stations,
        selectedCategory = selectedCategory,
        onCategorySelect = { selectedCategory = it },
        currentStationId = now.track?.takeIf { it.isRadio }?.idString?.removePrefix("radio_"),
        isPlaying = now.isPlaying,
        favoriteStationIds = favoriteStationIds,
        onStationClick = { radioVm.play(it) },
        onToggleFavorite = { radioVm.toggleFavorite(it) },
        onPlayPause = { viewModel.togglePlayPause() },
        hasActiveTrack = now.hasTrack
    )
}

@Composable
internal fun CarTab(
    viewModel: MainViewModel,
    radioVm: RadioViewModel,
    now: NowPlaying,
    progress: () -> Long,
    isLandscape: Boolean,
    onExit: () -> Unit
) {
    val queue by viewModel.currentPlaylist.collectAsStateWithLifecycle()
    val steeringWheelOnLeft by viewModel.steeringWheelOnLeft.collectAsStateWithLifecycle()
    val showClock by viewModel.carClock.collectAsStateWithLifecycle()
    val waveTuning by viewModel.waveTuning.collectAsStateWithLifecycle()
    val waveSeed by viewModel.waveSeed.collectAsStateWithLifecycle()
    val skipExplicit by viewModel.skipExplicit.collectAsStateWithLifecycle()
    val activeQueue by viewModel.activeQueue.collectAsStateWithLifecycle()
    val queueSwitcher = LocalQueueSwitcher.current
    val track = now.track
    // What will really play next, by the player's own rule (explicit passed over, a playlist goes on from its start)
    val upcoming = remember(queue, now.trackId, now.source, skipExplicit, track?.isRadio) {
        if (track == null || track.isRadio) emptyList()
        else PlaybackQueue.upcoming(queue, queue.indexOfFirst { it.idString == now.trackId }, now.source, 3) { skipExplicit && it.isExplicit }
    }
    CarScreen(
        currentTrack = track,
        isPlaying = now.isPlaying,
        likedTrackIds = now.likedTrackIds,
        onPlayPauseClick = { viewModel.togglePlayPause() },
        onNextClick = { viewModel.playNext() },
        onPrevClick = { viewModel.playPrev() },
        onToggleLike = { viewModel.toggleLike(it) },
        onDislikeClick = { viewModel.dislikeTrack(it) },
        onPlayMyVibe = { viewModel.playMyVibe() },
        onPlayFavorites = { viewModel.playAllLikedTracks() },
        onPlayHits = { viewModel.playHits(shuffle = true) },
        onPlayDownloaded = { viewModel.playDownloaded() },
        onPlayRadio = { radioVm.playFirst() },
        onExit = onExit,
        isLandscape = isLandscape,
        steeringWheelOnLeft = steeringWheelOnLeft,
        progress = progress,
        durationMs = track?.durationMs ?: 0L,
        onSeek = { viewModel.seekTo(it) },
        upcoming = upcoming,
        // The wave with nothing fetched yet ahead: placeholders keep the row's place
        upcomingLoading = now.source == PlaylistSource.VIBE && track != null && !track.isRadio && upcoming.isEmpty(),
        onUpcomingClick = { viewModel.playTrack(it.idString, queue, now.source, byUser = false) },
        source = now.source,
        isBuffering = now.isBuffering,
        showClock = showClock,
        waveTuning = waveTuning,
        trackWaveSeed = waveSeed,
        onWaveTuningChange = viewModel::setWaveTuning,
        onTrackWave = viewModel::startTrackWave,
        onBackToMyWave = viewModel::returnToMyWave,
        activeQueueName = activeQueue?.queue?.name,
        // The wave's pills only while the wave queue is active (D49); the queue pill is always there
        waveActive = activeQueue?.queue?.kind == QueueKind.WAVE,
        // The panel opens on the driver's side, as the tune panel does
        onQueues = { queueSwitcher?.open(QueueSwitcherRequest.Switch(car = true, side = if (steeringWheelOnLeft) PanelSide.LEFT else PanelSide.RIGHT)) }
    )
}
