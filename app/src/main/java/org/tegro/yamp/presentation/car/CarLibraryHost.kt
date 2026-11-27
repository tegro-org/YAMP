package org.tegro.yamp.presentation.car

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.tegro.yamp.R
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.RadioSeed
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.catalog.FolderPage
import org.tegro.yamp.presentation.hifi.PanelSide
import org.tegro.yamp.presentation.media.LocalMediaMenu
import org.tegro.yamp.presentation.media.MediaAction
import org.tegro.yamp.presentation.media.MediaActionsViewModel
import org.tegro.yamp.presentation.media.MediaMenuRow
import org.tegro.yamp.presentation.media.MediaTarget
import org.tegro.yamp.presentation.media.action
import org.tegro.yamp.presentation.media.carMediaMenuRows
import org.tegro.yamp.presentation.queues.LocalQueueSwitcher
import org.tegro.yamp.presentation.queues.QueueRowState
import org.tegro.yamp.presentation.queues.QueueSwitcherRequest

/**
 * The car Library wired to its view models (gap (f)): over the player, opened by ⋮ → «Музыка»; Back (or the capsule)
 * returns to the player. A tap on a folder or an album opens it (the album page, canvas `ca-album-*`); on a playlist or
 * a track it plays and returns; a hold opens
 * the menu on the wheel side, whose "Add to queue…" turns into the car picker in the same panel.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun CarLibraryHost(
    accountKey: String?,
    controlsOnLeft: Boolean,
    accountTags: List<AccountTag>,
    nowPlaying: CarNowPlaying?,
    queueRows: List<QueueRowState>,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val vm: CarLibraryViewModel = hiltViewModel()
    val media: MediaActionsViewModel = hiltViewModel()
    LaunchedEffect(accountKey) { vm.open(accountKey) }
    val state by vm.state.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf<CarPanel?>(null) }
    var pressed by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<List<Track>?>(null) }
    var pinTick by remember { mutableIntStateOf(0) }
    val albumWord = stringResource(R.string.media_album_word)
    val folderWord = stringResource(R.string.folder_word)
    val foldersTitle = stringResource(R.string.folders_title)
    val loadFailed = stringResource(R.string.media_load_failed)
    val resources = androidx.compose.ui.platform.LocalResources.current
    val switcher = LocalQueueSwitcher.current
    val phoneMenu = LocalMediaMenu.current
    val side = if (controlsOnLeft) PanelSide.LEFT else PanelSide.RIGHT
    val say: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
    val closePanel = { panel = null; pressed = null; adding = null }

    BackHandler {
        when {
            panel != null -> closePanel()
            vm.back() -> Unit
            else -> onClose()
        }
    }

    // "Add to queue…": the tracks load while the picker is already up
    val startAdding: (MediaTarget, Boolean) -> Unit = { target, fromMenu ->
        adding = null
        panel = CarPanel.AddTo(target, null, fromMenu)
        media.albumAction(
            target, MediaAction.ADD_TO_QUEUE,
            onAddTo = { tracks, _ -> adding = tracks; if ((panel as? CarPanel.AddTo)?.target == target) panel = CarPanel.AddTo(target, tracks.size, fromMenu) },
            onDone = {},
            onFailed = { say(loadFailed); closePanel() }
        )
    }
    val folderPage: (CarTile.OfFolder) -> FolderPage? = { tile ->
        (state.folder ?: state.account?.let { FolderPage(null, foldersTitle, it) })?.child(tile.folder.ref.id, tile.folder.title, tile.folder.cover?.key)
    }
    // A menu row or an album page button on [target]; [fromMenu]: the picker's back returns to the menu
    val runRow: (MediaTarget, MediaMenuRow, Boolean) -> Unit = { target, row, fromMenu ->
        when (row) {
            MediaMenuRow.ADD_TO_QUEUE -> startAdding(target, fromMenu)
            MediaMenuRow.START_RADIO -> {
                closePanel()
                switcher?.open(QueueSwitcherRequest.StartRadio(RadioSeed(target.ref, target.title), car = true, side = side))
            }
            MediaMenuRow.ADD_TO_PLAYLIST -> {
                closePanel()
                phoneMenu?.open(target)
            }
            MediaMenuRow.PIN, MediaMenuRow.UNPIN -> {
                closePanel()
                vm.togglePin(Folder(target.ref, target.title, target.cover?.let { org.tegro.yamp.presentation.folders.KeyCover(it) }))
            }
            else -> {
                closePanel()
                media.albumAction(
                    target, row.action(), onAddTo = { _, _ -> }, onFailed = { say(loadFailed) },
                    onDone = { n -> say(resources.getString(R.string.media_added, resources.tracksCount(n))) }
                )
                if (row == MediaMenuRow.PLAY_NOW || row == MediaMenuRow.SHUFFLE) onClose()
            }
        }
    }
    val albumTarget = state.album?.let { MediaTarget.of(it.album, albumWord) }
    val openFolder = state.folder?.takeIf { it.folderId != null }
    val folderPinned = remember(openFolder, pinTick) { openFolder?.folderId?.let(vm::isPinned) == true }

    CarLibraryScreen(
        state = state,
        controlsOnLeft = controlsOnLeft,
        account = accountTags.firstOrNull { it.key == state.account?.value },
        accounts = accountTags,
        nowPlaying = nowPlaying,
        panel = panel,
        queues = queueRows.filter { it.account == state.account?.value && it.kind == QueueKind.STATIC },
        pressed = pressed,
        folderPinned = folderPinned,
        albumFits = albumTarget?.let(media::fitsActiveQueue) ?: true,
        callbacks = CarLibraryCallbacks(
            onBack = { if (!vm.back()) onClose() },
            onTab = vm::select,
            onAccount = { vm.open(it.key) },
            onFilter = vm::filter,
            onTile = { tile ->
                when (carTap(tile)) {
                    CarTap.OPEN_FOLDER -> (tile as? CarTile.OfFolder)?.let(folderPage)?.let(vm::openFolder)
                    CarTap.OPEN_ALBUM -> (tile as? CarTile.OfAlbum)?.let { vm.openAlbum(it.album) }
                    CarTap.PLAY -> {
                        vm.play(tile)
                        onClose()
                    }
                }
            },
            onHold = { tile ->
                tile.target(albumWord, folderWord, state.account)?.let { target ->
                    pressed = tile.key
                    panel = CarPanel.Menu(target, carMediaMenuRows(target, media.fitsActiveQueue(target)))
                }
            },
            onPlayFolder = { folder, shuffle ->
                val target = MediaTarget(folder.ref, folder.title, folderWord, folder.cover?.key, isArtist = false, account = state.account)
                media.albumAction(target, if (shuffle) MediaAction.SHUFFLE else MediaAction.PLAY_NOW, onAddTo = { _, _ -> }, onDone = {}, onFailed = { say(loadFailed) })
                onClose()
            },
            onJump = vm::jumpTo,
            onFolderAddTo = {
                openFolder?.let { page ->
                    startAdding(MediaTarget(page.refOf(page.folderId.orEmpty()), page.title, folderWord, null, isArtist = false, account = state.account), true)
                }
            },
            onFolderPin = {
                openFolder?.let { page -> vm.togglePin(Folder(page.refOf(page.folderId.orEmpty()), page.title, null)); pinTick++ }
            },
            onNowPlaying = onClose,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onMenuRow = { row -> panel?.target?.let { runRow(it, row, true) } },
            onPanelBack = {
                val now = panel
                if (now is CarPanel.AddTo && !now.fromMenu) {
                    closePanel()
                } else {
                    panel = now?.target?.let { CarPanel.Menu(it, carMediaMenuRows(it, media.fitsActiveQueue(it))) }
                    adding = null
                }
            },
            onPanelClose = closePanel,
            onAddTo = { row ->
                adding?.let { tracks ->
                    vm.addToQueue(row.id, tracks)
                    say(resources.getString(R.string.car_added, row.name))
                    closePanel()
                }
            },
            onNewQueue = {
                val target = panel?.target
                adding?.let { tracks ->
                    if (target != null) {
                        vm.newQueue(target.title, tracks)
                        say(resources.getString(R.string.car_added, target.title))
                    }
                    closePanel()
                }
            },
            onAlbumButton = { row -> albumTarget?.let { runRow(it, row, false) } },
            onAlbumTrack = { index ->
                vm.playAlbumFrom(index)
                onClose()
            }
        )
    )
}

/** "12 tracks" in the app's wording. */
private fun android.content.res.Resources.tracksCount(count: Int): String {
    val res = listOf(R.string.tracks_count_one, R.string.tracks_count_few, R.string.tracks_count_many)[org.tegro.yamp.utils.russianPluralIndex(count)]
    return getString(res, count)
}
