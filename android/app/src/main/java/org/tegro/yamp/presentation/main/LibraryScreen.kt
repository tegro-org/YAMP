package org.tegro.yamp.presentation.main

import org.tegro.yamp.presentation.main.components.SettingsUiState
import org.tegro.yamp.presentation.main.components.SettingsSheet
import org.tegro.yamp.presentation.main.components.SettingsActions
import org.tegro.yamp.presentation.main.components.landscapeColumns
import org.tegro.yamp.presentation.main.components.DockClearance
import androidx.compose.foundation.layout.navigationBars
import org.tegro.yamp.presentation.hifi.rememberHeaderCollapse
import org.tegro.yamp.presentation.hifi.fadeUnderHeader
import org.tegro.yamp.presentation.hifi.HiFiWellButton
import org.tegro.yamp.presentation.hifi.HiFiCollapsingTitle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import org.tegro.yamp.utils.cover
import org.tegro.yamp.utils.CoverSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.FavoriteBorder
import org.tegro.yamp.presentation.hifi.hifiSoftShadow
import org.tegro.yamp.presentation.hifi.hifiPress
import org.tegro.yamp.presentation.hifi.appearOnce
import org.tegro.yamp.presentation.hifi.rememberAppearance
import org.tegro.yamp.presentation.hifi.predictiveBack
import org.tegro.yamp.presentation.hifi.rememberPredictiveBack
import org.tegro.yamp.presentation.hifi.HiFiActivityIndicator
import org.tegro.yamp.presentation.hifi.HiFiEmptyState
import org.tegro.yamp.presentation.hifi.HiFiErrorState
import org.tegro.yamp.presentation.hifi.HiFiSkeletonRows
import org.tegro.yamp.presentation.hifi.LoadState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.tegro.yamp.R
import org.tegro.yamp.data.download.DownloadQueueState
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.common.coverUri
import org.tegro.yamp.presentation.common.id
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.data.network.DownloadProgressInfo
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.GlassPillButton
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiLargeTitle
import org.tegro.yamp.presentation.hifi.HiFiMotion
import org.tegro.yamp.presentation.hifi.HiFiSectionHeader
import org.tegro.yamp.presentation.hifi.HiFiType
import org.tegro.yamp.presentation.hifi.IconPlaque
import org.tegro.yamp.presentation.hifi.MetalPillButton
import org.tegro.yamp.presentation.main.components.DownloadProgressModal
import org.tegro.yamp.presentation.main.components.PlaylistNameDialog
import org.tegro.yamp.presentation.main.components.TrackItem
import org.tegro.yamp.utils.russianQuantityString

private enum class LibrarySection { LIKED, DOWNLOADED, HISTORY, PLAYLISTS }

/** The list starts to show under the header; below it, nothing is cut by a hard line. */
private val LibraryFadeStart = 56.dp
private val LibraryFadeLength = 22.dp

/**
 * "Моя музыка": play/shuffle, the three library sections (each opens its own page) and recently
 * added covers. Settings — the only entry to the account — sit behind the button in the header.
 */
@Composable
fun LibraryScreen(
    tracks: List<Track>,
    /** The wheel side (Settings → Interface): left-hand puts the header's settings button at the left edge. */
    controlsOnLeft: Boolean = false,
    isLoading: Boolean,
    loadFailed: Boolean = false,
    downloadState: DownloadQueueState = DownloadQueueState.IDLE,
    currentTrackId: String?,
    isPlaying: Boolean,
    likedTrackIds: Set<String>,
    downloadedTrackIds: Set<String>,
    downloadProgress: DownloadProgressInfo?,
    onTrackClick: (String) -> Unit,
    onPlayAllClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onDownloadAllClick: () -> Unit,
    onCancelDownload: () -> Unit,
    settings: SettingsUiState = SettingsUiState(),
    settingsActions: SettingsActions = SettingsActions(),
    onToggleLike: (Track) -> Unit,
    onRefresh: () -> Unit = {},
    hasActiveTrack: Boolean = false,
    playlists: List<Playlist> = emptyList(),
    openedPlaylist: OpenedPlaylist? = null,
    onOpenPlaylist: (Playlist) -> Unit = {},
    onClosePlaylist: () -> Unit = {},
    onPlayPlaylist: (shuffle: Boolean) -> Unit = {},
    onPlaylistTrackClick: (String) -> Unit = {},
    onCreatePlaylist: (String) -> Unit = {},
    onRenamePlaylist: (Playlist, String) -> Unit = { _, _ -> },
    onDeletePlaylist: (Playlist) -> Unit = {},
    onReorderPlaylist: (Playlist, List<String>, List<String>) -> Unit = { _, _, _ -> },
    onSetPlaylistPublic: (Playlist, Boolean) -> Unit = { _, _ -> },
    onAddToEnd: (List<Track>) -> Unit = {},
    /** Rows of the last seven days, on the "History" row. */
    historyCount: Int = 0,
    /** The History page (D46), given the page modifier, the bottom clearance and the way back. */
    historyPage: @Composable (modifier: Modifier, contentBottom: androidx.compose.ui.unit.Dp, onBack: () -> Unit) -> Unit = { _, _, _ -> },
    /**
     * One section per signed-in account (gap (e), canvas `hj-lib`): its rows with its own counts. The Yandex section's
     * likes, playlists and downloads of the
     * account in context are this screen's own lists; the rest come with the section.
     */
    sections: List<org.tegro.yamp.presentation.catalog.AccountLibrary> = emptyList(),
    /** The accounts' markers, for the section headers. */
    accountTags: List<org.tegro.yamp.presentation.account.AccountTag> = emptyList(),
    /** A Yandex account is signed in: Play / Shuffle play its likes (`wl-jf-library`: else a Jellyfin account's favourites). */
    yandex: Boolean = true,
    /** A row of a section that opens a page of the catalog stack: a browse list or the Folders root. */
    onOpenPage: (org.tegro.yamp.presentation.catalog.CatalogPage) -> Unit = {},
    /** Play / Shuffle without Yandex: the account's favourites. */
    onPlayFavourites: (org.tegro.yamp.domain.account.AccountId, Boolean) -> Unit = { _, _ -> }
) {
    // Before the accounts are known, a signed-in Yandex account's rows show at once (its likes are already here)
    @Suppress("NAME_SHADOWING")
    val sections = sections.ifEmpty {
        if (yandex) listOf(org.tegro.yamp.presentation.catalog.AccountLibrary(org.tegro.yamp.domain.account.AccountId.yandex(""), "", "Yandex", inContext = true))
        else emptyList()
    }
    var showSettings by remember { mutableStateOf(false) }
    var showProgressModal by remember { mutableStateOf(false) }
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val section = sectionName?.let { LibrarySection.valueOf(it) }
    val back = rememberPredictiveBack(enabled = section != null && openedPlaylist == null) { sectionName = null }

    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val contentStart = 20.dp
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottom = if (isLandscape) DockClearance + navigationBar else (if (hasActiveTrack) 168.dp else 100.dp)
    val columns = if (isLandscape) landscapeColumns(org.tegro.yamp.utils.currentWindowSizeDp().widthDp) else 2
    val downloaded = remember(tracks, downloadedTrackIds) { tracks.filter { downloadedTrackIds.contains(it.id) } }

    if (openedPlaylist != null) {
        PlaylistScreen(
            opened = openedPlaylist,
            currentTrackId = currentTrackId,
            isPlaying = isPlaying,
            likedTrackIds = likedTrackIds,
            downloadedTrackIds = downloadedTrackIds,
            // The page keeps above the navigation bar itself
            contentBottom = if (isLandscape) DockClearance else contentBottom,
            onBack = onClosePlaylist,
            onPlay = onPlayPlaylist,
            onTrackClick = onPlaylistTrackClick,
            onToggleLike = onToggleLike,
            onRetry = { onOpenPlaylist(openedPlaylist.playlist) },
            onRename = { onRenamePlaylist(openedPlaylist.playlist, it) },
            onDelete = { onDeletePlaylist(openedPlaylist.playlist) },
            onReorder = { shown, new -> onReorderPlaylist(openedPlaylist.playlist, shown, new) },
            onSetPublic = { onSetPlaylistPublic(openedPlaylist.playlist, it) },
            onAddToEnd = onAddToEnd
        )
        return
    }

    AnimatedContent(
        targetState = section,
        transitionSpec = {
            (fadeIn(HiFiMotion.standard()) + slideInVertically(HiFiMotion.standard()) { it / 40 }) togetherWith fadeOut(HiFiMotion.quick())
        },
        label = "library_section"
    ) { shown ->
        val listPadding = PaddingValues(start = contentStart, end = 20.dp, bottom = contentBottom)
        when (shown) {
            null -> Box(Modifier.fillMaxSize()) {
            val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val density = LocalDensity.current
            val list = rememberLazyListState()
            val collapse by rememberHeaderCollapse(list)
            LazyColumn(
                state = list,
                modifier = Modifier
                    .fillMaxSize()
                    .fadeUnderHeader({ with(density) { (statusBar + LibraryFadeStart).toPx() } }, with(density) { LibraryFadeLength.toPx() }),
                contentPadding = PaddingValues(start = contentStart, end = 20.dp, top = statusBar + LibraryFadeStart + LibraryFadeLength + 2.dp, bottom = contentBottom)
            ) {
                val firstJellyfin = sections.firstOrNull { it.account.provider == org.tegro.yamp.domain.account.Provider.JELLYFIN }
                val play: (Boolean) -> Unit = { shuffle ->
                    when {
                        yandex -> if (shuffle) onShuffleClick() else onPlayAllClick()
                        firstJellyfin != null -> onPlayFavourites(firstJellyfin.account, shuffle)
                    }
                }
                val hasMusic = yandex || firstJellyfin != null
                if (hasMusic && !isLandscape) {
                    item(key = "buttons") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetalPillButton(stringResource(R.string.library_listen), { play(false) }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
                            GlassPillButton(stringResource(R.string.library_shuffle), { play(true) }, Modifier.weight(1f), icon = Icons.Rounded.Shuffle)
                        }
                    }
                }
                val headers = org.tegro.yamp.presentation.catalog.showsAccountHeaders(sections)
                val openRow: (org.tegro.yamp.presentation.catalog.AccountLibrary, org.tegro.yamp.presentation.catalog.LibraryRow, String) -> Unit = { section, row, title ->
                    openLibraryRow(section, row, title, onOpenPage) { sectionName = it.name }
                }
                val ownCounts: (org.tegro.yamp.presentation.catalog.AccountLibrary) -> Map<org.tegro.yamp.presentation.catalog.LibraryRow, Int> = { section ->
                    if (!section.inContext) section.counts
                    else section.counts + mapOf(
                        org.tegro.yamp.presentation.catalog.LibraryRow.LIKED to tracks.size,
                        org.tegro.yamp.presentation.catalog.LibraryRow.PLAYLISTS to playlists.size,
                        org.tegro.yamp.presentation.catalog.LibraryRow.DOWNLOADED to downloaded.size
                    )
                }
                if (isLandscape && sections.size > 1) {
                    // Phone on its side (canvas `hj-lib-land`): the accounts side by side, one column each
                    item(key = "sections") {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            sections.forEach { section ->
                                AccountLibrarySection(
                                    section, accountTags.firstOrNull { it.key == section.account.value }, headers, ownCounts(section),
                                    Modifier.weight(1f)
                                ) { row, title -> openRow(section, row, title) }
                            }
                        }
                    }
                } else {
                    sections.forEachIndexed { index, section ->
                        item(key = "section:" + section.account.value) {
                            AccountLibrarySection(
                                section, accountTags.firstOrNull { it.key == section.account.value }, headers, ownCounts(section),
                                Modifier.padding(top = if (index == 0) (if (hasMusic && !isLandscape) 10.dp else 0.dp) else 14.dp)
                            ) { row, title -> openRow(section, row, title) }
                        }
                    }
                }
                item(key = "history") {
                    Column(Modifier.padding(top = if (sections.isEmpty()) 0.dp else 14.dp)) {
                        SectionRow(Icons.Rounded.History, stringResource(R.string.history_row), historyCount) { sectionName = LibrarySection.HISTORY.name }
                    }
                }
                // "Recently added" with one account only (canvas `hj-lib-a` / `hj-lib-b`): Jellyfin's newest albums
                val onlyJellyfin = sections.singleOrNull()?.takeIf { it.account.provider == org.tegro.yamp.domain.account.Provider.JELLYFIN }
                if (onlyJellyfin != null && onlyJellyfin.recent.isNotEmpty()) {
                    item(key = "recent_title_jf") {
                        HiFiSectionHeader(stringResource(R.string.home_recently_added), Modifier.padding(top = 26.dp, bottom = 12.dp))
                    }
                    albumGrid(onlyJellyfin.recent.take(if (isLandscape) columns * 2 else 6), columns) { album ->
                        onOpenPage(org.tegro.yamp.presentation.catalog.CatalogPage.Album(album.ref.id, album.title, onlyJellyfin.account))
                    }
                }
                // The likes' states are Yandex's: with Jellyfin only there are none to load or to miss
                if (yandex && sections.size <= 1) when (LoadState.of(tracks, tracks.isEmpty(), isLoading, loadFailed)) {
                    LoadState.Loading -> item(key = "loading") { HiFiSkeletonRows(Modifier.padding(top = 28.dp), count = 4) }
                    LoadState.Failed -> item(key = "failed") { HiFiErrorState(onRetry = onRefresh) }
                    LoadState.Empty -> item(key = "empty") {
                        HiFiEmptyState(
                            icon = Icons.Rounded.FavoriteBorder,
                            title = stringResource(R.string.library_empty_title),
                            message = stringResource(R.string.library_empty_message),
                            actionLabel = stringResource(R.string.library_obnovit_mediateku),
                            onAction = onRefresh
                        )
                    }
                    is LoadState.Ready -> {
                        item(key = "recent_title") {
                            HiFiSectionHeader(stringResource(R.string.library_recent), Modifier.padding(top = 26.dp, bottom = 12.dp))
                        }
                        coverGrid(
                            tracks = tracks.take(if (isLandscape) columns * 2 else 6),
                            columns = columns,
                            onClick = { onTrackClick(it.id) }
                        )
                    }
                }
            }
            // The settings button at the wheel side's edge: left-hand — the gear at the left, the title to the right
            Row(
                Modifier.fillMaxWidth().padding(start = contentStart, end = 20.dp, top = statusBar + 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val title: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                    HiFiCollapsingTitle(stringResource(R.string.library_title), collapse = { collapse }, modifier = Modifier.weight(1f), alignEnd = controlsOnLeft)
                }
                val downloads: @Composable () -> Unit = {
                    if (downloadState == DownloadQueueState.RUNNING) {
                        HiFiWellButton(onClick = { showProgressModal = true }, contentDescription = stringResource(R.string.download_progress_modal_zagruzka_kollektsii)) {
                            HiFiActivityIndicator(size = 18.dp)
                        }
                    }
                }
                val gear: @Composable () -> Unit = {
                    HiFiWellButton(onClick = { showSettings = true }, contentDescription = stringResource(R.string.library_settings)) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, tint = Color.White.copy(alpha = 0.86f), modifier = Modifier.size(20.dp))
                    }
                }
                val running = downloadState == DownloadQueueState.RUNNING
                // Landscape (canvas `hj-lib-land`): Play / Shuffle sit in the title row
                val landButtons: @Composable () -> Unit = {
                    val firstJellyfin = sections.firstOrNull { it.account.provider == org.tegro.yamp.domain.account.Provider.JELLYFIN }
                    if (isLandscape && (yandex || firstJellyfin != null)) {
                        val play: (Boolean) -> Unit = { shuffle ->
                            if (yandex) { if (shuffle) onShuffleClick() else onPlayAllClick() } else firstJellyfin?.let { onPlayFavourites(it.account, shuffle) }
                        }
                        MetalPillButton(stringResource(R.string.library_listen), { play(false) }, Modifier.width(150.dp), icon = Icons.Rounded.PlayArrow, height = 44.dp)
                        Spacer(Modifier.width(10.dp))
                        GlassPillButton(stringResource(R.string.library_shuffle), { play(true) }, Modifier.width(170.dp), icon = Icons.Rounded.Shuffle, height = 44.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                }
                if (controlsOnLeft) {
                    gear()
                    Spacer(Modifier.width(10.dp))
                    landButtons()
                    if (running) { Spacer(Modifier.width(10.dp)); downloads() }
                    Spacer(Modifier.width(12.dp))
                    title()
                } else {
                    title()
                    landButtons()
                    if (running) { downloads(); Spacer(Modifier.width(10.dp)) }
                    gear()
                }
            }
            }
            LibrarySection.LIKED, LibrarySection.DOWNLOADED -> {
                val list = if (shown == LibrarySection.LIKED) tracks else downloaded
                val appearance = rememberAppearance(shown)
                LazyColumn(modifier = Modifier.fillMaxSize().predictiveBack(back).statusBarsPadding(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = contentBottom)) {
                    item(key = "header") {
                        SectionHeader(
                            title = stringResource(if (shown == LibrarySection.LIKED) R.string.library_liked else R.string.library_downloaded),
                            count = list.size,
                            onBack = { sectionName = null }
                        )
                    }
                    if (shown == LibrarySection.LIKED && list.isNotEmpty()) {
                        item(key = "buttons") {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MetalPillButton(stringResource(R.string.library_listen), onPlayAllClick, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
                                GlassPillButton(stringResource(R.string.library_shuffle), onShuffleClick, Modifier.weight(1f), icon = Icons.Rounded.Shuffle)
                            }
                        }
                    }
                    if (list.isEmpty()) {
                        item(key = "empty") {
                            HiFiEmptyState(
                                icon = if (shown == LibrarySection.DOWNLOADED) Icons.Rounded.DownloadDone else Icons.Rounded.FavoriteBorder,
                                title = stringResource(if (shown == LibrarySection.DOWNLOADED) R.string.library_downloaded_empty else R.string.library_mne_nravitsya)
                            )
                        }
                    } else if (isLandscape) {
                        items(list.chunked(2), key = { row -> row.joinToString("|") { it.id } }, contentType = { "track_row" }) { rowItems ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                rowItems.forEach { track ->
                                    Box(Modifier.weight(1f)) { LibraryTrack(track, currentTrackId, isPlaying, likedTrackIds, downloadedTrackIds, onToggleLike, onTrackClick) }
                                }
                                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        itemsIndexed(list, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { index, track ->
                            Box(Modifier.appearOnce(appearance, track.id, index)) {
                                LibraryTrack(track, currentTrackId, isPlaying, likedTrackIds, downloadedTrackIds, onToggleLike, onTrackClick)
                            }
                        }
                    }
                }
            }
            LibrarySection.HISTORY -> historyPage(Modifier.predictiveBack(back), contentBottom) { sectionName = null }
            LibrarySection.PLAYLISTS -> PlaylistsPage(
                modifier = Modifier.predictiveBack(back),
                playlists = playlists,
                columns = columns,
                contentPadding = listPadding,
                onBack = { sectionName = null },
                onOpen = onOpenPlaylist,
                onCreate = onCreatePlaylist
            )
        }
    }

    if (showSettings) {
        SettingsSheet(
            state = settings,
            actions = settingsActions.copy(onDownloadAll = {
                onDownloadAllClick()
                showProgressModal = true
            }),
            onDismiss = { showSettings = false }
        )
    }

    if (showProgressModal && downloadState != DownloadQueueState.IDLE) {
        DownloadProgressModal(
            state = downloadState,
            progressInfo = downloadProgress,
            onDismiss = { showProgressModal = false },
            onCancel = {
                onCancelDownload()
                showProgressModal = false
            }
        )
    }
}

@Composable
private fun LibraryTrack(
    track: Track,
    currentTrackId: String?,
    isPlaying: Boolean,
    likedTrackIds: Set<String>,
    downloadedTrackIds: Set<String>,
    onToggleLike: (Track) -> Unit,
    onTrackClick: (String) -> Unit
) {
    TrackItem(
        track = track,
        isPlaying = isPlaying && track.id == currentTrackId,
        isLiked = likedTrackIds.contains(track.id),
        isDownloaded = downloadedTrackIds.contains(track.id),
        onToggleLike = { onToggleLike(track) },
        onClick = { onTrackClick(track.id) }
    )
}

/** One library section row: metal plaque, title, count, chevron, hairline below. */
@Composable
private fun SectionRow(icon: ImageVector, title: String, count: Int?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .hifiPress(onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconPlaque(icon, size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Text(title, style = HiFiType.RowTitle, modifier = Modifier.weight(1f))
        count?.let { Text(it.toString(), color = HiFi.TextTertiary, fontSize = 15.sp) }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
    }
    Box(Modifier.fillMaxWidth().padding(start = 50.dp).height(1.dp).background(HiFi.Hairline))
}

/** What a row of an account's section opens: a page of the Library, or one of the catalog stack. */
private fun openLibraryRow(
    section: org.tegro.yamp.presentation.catalog.AccountLibrary,
    row: org.tegro.yamp.presentation.catalog.LibraryRow,
    title: String,
    onOpenPage: (org.tegro.yamp.presentation.catalog.CatalogPage) -> Unit,
    onSection: (LibrarySection) -> Unit
) {
    val account = section.account
    // Only the Yandex account in context has the Library's own pages; any other account's open on the catalog stack
    val onStack = !section.inContext
    fun browse(kind: org.tegro.yamp.presentation.catalog.BrowseKind) = onOpenPage(org.tegro.yamp.presentation.catalog.CatalogPage.Browse(kind, title, account))
    when (row) {
        org.tegro.yamp.presentation.catalog.LibraryRow.LIKED ->
            if (onStack) browse(org.tegro.yamp.presentation.catalog.BrowseKind.FAVOURITES) else onSection(LibrarySection.LIKED)
        org.tegro.yamp.presentation.catalog.LibraryRow.PLAYLISTS ->
            if (onStack) browse(org.tegro.yamp.presentation.catalog.BrowseKind.PLAYLISTS) else onSection(LibrarySection.PLAYLISTS)
        org.tegro.yamp.presentation.catalog.LibraryRow.ALBUMS -> browse(org.tegro.yamp.presentation.catalog.BrowseKind.ALBUMS)
        org.tegro.yamp.presentation.catalog.LibraryRow.ARTISTS -> browse(org.tegro.yamp.presentation.catalog.BrowseKind.ARTISTS)
        org.tegro.yamp.presentation.catalog.LibraryRow.DOWNLOADED ->
            if (onStack) browse(org.tegro.yamp.presentation.catalog.BrowseKind.DOWNLOADED) else onSection(LibrarySection.DOWNLOADED)
        org.tegro.yamp.presentation.catalog.LibraryRow.FOLDERS -> onOpenPage(org.tegro.yamp.presentation.folders.rootFolderPage(account, title))
    }
}

/**
 * One account's section (canvas `hj-lib`): marker, name and service over a hairline — only with more than one
 * account — then its rows with their own counts.
 */
@Composable
internal fun AccountLibrarySection(
    section: org.tegro.yamp.presentation.catalog.AccountLibrary,
    tag: org.tegro.yamp.presentation.account.AccountTag?,
    header: Boolean,
    counts: Map<org.tegro.yamp.presentation.catalog.LibraryRow, Int>,
    modifier: Modifier = Modifier,
    onRow: (org.tegro.yamp.presentation.catalog.LibraryRow, String) -> Unit
) {
    val jellyfin = section.account.provider == org.tegro.yamp.domain.account.Provider.JELLYFIN
    Column(modifier) {
        if (header) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                tag?.let { org.tegro.yamp.presentation.account.AccountMark(it, 22.dp) }
                Spacer(Modifier.width(10.dp))
                Text(section.name, color = Color.White, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(" · " + section.service, style = HiFiType.Caption, color = HiFi.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f).height(1.dp).background(HiFi.Hairline))
            }
        }
        section.rows.forEach { row ->
            val (icon, title) = when (row) {
                org.tegro.yamp.presentation.catalog.LibraryRow.LIKED ->
                    Icons.Rounded.Favorite to stringResource(if (jellyfin) R.string.library_row_favourites else R.string.library_row_liked)
                org.tegro.yamp.presentation.catalog.LibraryRow.PLAYLISTS -> Icons.AutoMirrored.Rounded.PlaylistPlay to stringResource(R.string.jellyfin_lib_playlists)
                org.tegro.yamp.presentation.catalog.LibraryRow.ALBUMS -> Icons.Rounded.Album to stringResource(R.string.jellyfin_lib_albums)
                org.tegro.yamp.presentation.catalog.LibraryRow.ARTISTS -> Icons.Rounded.Person to stringResource(R.string.jellyfin_lib_artists)
                org.tegro.yamp.presentation.catalog.LibraryRow.DOWNLOADED -> Icons.Rounded.DownloadDone to stringResource(R.string.library_row_downloaded)
                org.tegro.yamp.presentation.catalog.LibraryRow.FOLDERS -> Icons.Rounded.Folder to stringResource(R.string.library_row_folders)
            }
            SectionRow(icon, title, counts[row]) { onRow(row, title) }
        }
    }
}

/** Album covers in rows of [columns] (Jellyfin's "Recently added"). */
private fun LazyListScope.albumGrid(albums: List<org.tegro.yamp.domain.media.Album>, columns: Int, onClick: (org.tegro.yamp.domain.media.Album) -> Unit) {
    items(albums.chunked(columns), key = { row -> "agrid_" + row.joinToString("|") { it.ref.value } }, contentType = { "album_row" }) { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            row.forEach { album ->
                Column(Modifier.weight(1f).hifiPress({ onClick(album) })) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).cover(album.cover?.key, CoverSize.Medium).crossfade(true).build(),
                        contentDescription = album.title.ifEmpty { null },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .hifiSoftShadow(RoundedCornerShape(16.dp), 18.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(HiFi.Placeholder)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.height(9.dp))
                    Text(album.title, style = HiFiType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album.artists.joinToString { it.name }, style = HiFiType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Back button, page title and count — the header of every library sub-page. */
@Composable
private fun SectionHeader(title: String, count: Int, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassCircleButton(onClick = onBack, contentDescription = stringResource(R.string.cd_nazad)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = HiFiType.LargeTitle.copy(fontSize = 28.sp, lineHeight = 34.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                russianQuantityString(count, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many),
                style = HiFiType.Caption
            )
        }
    }
}

/** Covers in rows of [columns]; each row is one lazy item so long grids stay cheap. */
private fun LazyListScope.coverGrid(tracks: List<Track>, columns: Int, onClick: (Track) -> Unit) {
    items(tracks.chunked(columns), key = { row -> "grid_" + row.joinToString("|") { it.id } }, contentType = { "cover_row" }) { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            row.forEach { track -> CoverCard(track, Modifier.weight(1f)) { onClick(track) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun CoverCard(track: Track, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        // Only the top corners are rounded: a fully rounded clip would shave the first letter off the caption
        modifier = modifier
            .hifiPress(onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).cover(track.coverUri, CoverSize.Medium).crossfade(true).build(),
            contentDescription = track.title.ifEmpty { null },
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .hifiSoftShadow(RoundedCornerShape(16.dp), 18.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(HiFi.Placeholder)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
        )
        Spacer(Modifier.height(9.dp))
        Text(track.title.ifEmpty { stringResource(R.string.bez_nazvaniya) }, style = HiFiType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artists.joinToString { it.name }, style = HiFiType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaylistsPage(
    modifier: Modifier,
    playlists: List<Playlist>,
    columns: Int,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpen: (Playlist) -> Unit,
    onCreate: (String) -> Unit
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.playlist_create),
            onConfirm = { onCreate(it); naming = false },
            onDismiss = { naming = false }
        )
    }
    val cells: List<Playlist?> = listOf<Playlist?>(null) + playlists
    LazyColumn(modifier = modifier.fillMaxSize().statusBarsPadding(), contentPadding = contentPadding) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassCircleButton(onClick = onBack, contentDescription = stringResource(R.string.cd_nazad)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.library_playlists_row), style = HiFiType.LargeTitle.copy(fontSize = 28.sp, lineHeight = 34.sp))
            }
        }
        items(cells.chunked(columns), key = { row -> "pl_" + row.joinToString("|") { it?.ref?.value ?: "new" } }, contentType = { "playlist_row" }) { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { playlist ->
                    Column(
                        Modifier
                            .weight(1f)
                            .hifiPress({ if (playlist == null) naming = true else onOpen(playlist) })
                    ) {
                        if (playlist == null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(40.dp))
                            }
                            Spacer(Modifier.height(9.dp))
                            Text(stringResource(R.string.playlist_new), style = HiFiType.CardTitle, maxLines = 1)
                        } else {
                            PlaylistCover(playlist, Modifier.fillMaxWidth().aspectRatio(1f).hifiSoftShadow(RoundedCornerShape(16.dp), 18.dp).clip(RoundedCornerShape(16.dp)))
                            Spacer(Modifier.height(9.dp))
                            Text(playlist.title, style = HiFiType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                russianQuantityString(playlist.trackCount, R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many),
                                style = HiFiType.Caption
                            )
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
