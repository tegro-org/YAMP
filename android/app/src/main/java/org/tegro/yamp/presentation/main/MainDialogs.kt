package org.tegro.yamp.presentation.main

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.tegro.yamp.R
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.dto.UserPlaylistDto
import org.tegro.yamp.data.updater.UpdateCheckResult
import org.tegro.yamp.presentation.account.AccountManagementDialog
import org.tegro.yamp.presentation.account.AccountViewModel
import org.tegro.yamp.presentation.account.StartupAccountPickerDialog
import org.tegro.yamp.presentation.catalog.CatalogViewModel
import org.tegro.yamp.presentation.crash.CrashLogDialog
import org.tegro.yamp.presentation.crash.CrashLogViewModel
import org.tegro.yamp.presentation.main.components.TrackContextMenu
import org.tegro.yamp.presentation.queues.LocalQueueSwitcher
import org.tegro.yamp.presentation.queues.QueueSwitcherRequest
import org.tegro.yamp.presentation.update.UpdateDialog
import org.tegro.yamp.presentation.update.UpdateViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** What the track menu was opened for; [pickPlaylist] goes straight to the playlist picker. */
internal data class TrackMenuRequest(
    val track: TrackDetailsDto,
    val playlist: UserPlaylistDto?,
    val pickPlaylist: Boolean
)

/** "⋮" on any track, or the player's "add to playlist". */
@Composable
internal fun TrackMenuHost(
    request: TrackMenuRequest,
    viewModel: MainViewModel,
    playlistsVm: PlaylistsViewModel,
    catalogVm: CatalogViewModel,
    likedTrackIds: Set<String>,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
    onLeavePlayer: () -> Unit
) {
    val playlists by playlistsVm.playlists.collectAsStateWithLifecycle()
    val playNextDone = stringResource(R.string.track_menu_play_next_done)
    val scope = rememberCoroutineScope()
    val track = request.track
    val queueSwitcher = LocalQueueSwitcher.current
    val trackLabel = listOfNotNull(track.title, track.artists.joinToString { it.name.orEmpty() }.takeIf { it.isNotBlank() }).joinToString(" · ")
    TrackContextMenu(
        track = track,
        inPlaylist = request.playlist,
        playlists = playlists,
        isLiked = track.idString in likedTrackIds,
        startWithPlaylists = request.pickPlaylist,
        onDismiss = onDismiss,
        onPlayNext = {
            viewModel.enqueueNext(track)
            scope.launch { snackbar.showSnackbar(playNextDone) }
        },
        onToggleLike = { viewModel.toggleLike(track) },
        onAddTo = { playlistsVm.addTrack(it, track) },
        onCreatePlaylist = { playlistsVm.create(it, thenAdd = track) },
        onRemoveFromPlaylist = { request.playlist?.let { playlistsVm.removeTrack(it, track.idString) } },
        onTrackWave = { viewModel.waveOrRadio(track) { queueSwitcher?.open(QueueSwitcherRequest.StartRadio.of(track)) } },
        waveActive = viewModel.waveActive.collectAsStateWithLifecycle().value,
        onAddToEnd = { viewModel.addToEnd(listOf(track)) },
        onAddToQueue = { queueSwitcher?.open(QueueSwitcherRequest.AddTo(listOf(track), trackLabel)) },
        onOpenArtist = track.artists.firstNotNullOfOrNull { artist -> artist.idString?.let { id -> id to artist.name.orEmpty() } }
            ?.let { (id, name) ->
                {
                    onLeavePlayer()
                    catalogVm.openArtist(id, name)
                }
            },
        onOpenAlbum = track.albums.firstOrNull()?.let { album ->
            val albumId = (album.id as? JsonPrimitive)?.content ?: return@let null
            {
                onLeavePlayer()
                catalogVm.openAlbum(albumId, album.title.orEmpty())
            }
        }
    )
}

/** The in-app update offer, while one is available. */
@Composable
internal fun UpdateHost(updateVm: UpdateViewModel) {
    val result by updateVm.checkResult.collectAsStateWithLifecycle()
    val progress by updateVm.downloadProgress.collectAsStateWithLifecycle()
    val status by updateVm.statusMessage.collectAsStateWithLifecycle()
    val mismatch by updateVm.isSignatureMismatch.collectAsStateWithLifecycle()
    val update = result as? UpdateCheckResult.UpdateAvailable ?: return
    UpdateDialog(
        updateInfo = update,
        downloadProgress = progress,
        statusMessage = status,
        hasInstallPermission = updateVm.canRequestPackageInstalls,
        isSignatureMismatch = mismatch,
        onRequestPermission = { updateVm.openInstallPermissionSettings() },
        onStartDownload = { updateVm.startDownload(update.apkDownloadUrl) },
        onInstall = { updateVm.installDownloaded(forceSystemInstall = true) },
        onShareOrSave = { updateVm.shareOrSaveDownloadedApk() },
        onDismiss = { updateVm.dismiss() },
        onSkip = { updateVm.skip() }
    )
}

/** Account management (from Settings) and the profile picker shown at start when there are several accounts. */
@Composable
internal fun AccountHost(
    accountVm: AccountViewModel,
    showManagement: Boolean,
    onCloseManagement: () -> Unit,
    startupPickerHandled: Boolean,
    onStartupPickerHandled: () -> Unit,
    onAddAccount: () -> Unit
) {
    val accounts by accountVm.allAccounts.collectAsStateWithLifecycle()
    val active by accountVm.activeAccount.collectAsStateWithLifecycle()
    val defaultUid by accountVm.defaultAccountUid.collectAsStateWithLifecycle()
    if (showManagement) {
        AccountManagementDialog(
            accounts = accounts,
            activeAccount = active,
            defaultAccountUid = defaultUid,
            onSetDefaultAccount = { accountVm.setDefaultAccount(it) },
            onSwitchAccount = { accountVm.switchAccount(it) },
            onRemoveAccount = { accountVm.removeAccount(it) },
            onAddNewAccount = onAddAccount,
            onLogoutCurrent = { accountVm.logoutCurrentAccount() },
            onLogoutAll = { accountVm.logoutAllAccounts() },
            onDismiss = onCloseManagement
        )
    }
    if (!startupPickerHandled && accounts.size > 1 && defaultUid == null) {
        StartupAccountPickerDialog(
            accounts = accounts,
            activeAccount = active,
            onSelectAccount = { uid, rememberAsDefault ->
                accountVm.switchAccount(uid)
                if (rememberAsDefault) accountVm.setDefaultAccount(uid)
                onStartupPickerHandled()
            },
            onAddNewAccount = {
                onStartupPickerHandled()
                onAddAccount()
            }
        )
    }
}

@Composable
internal fun CrashLogHost(crashVm: CrashLogViewModel, onDismiss: () -> Unit) {
    val reports by crashVm.reports.collectAsStateWithLifecycle()
    CrashLogDialog(reports = reports, onSend = { crashVm.send() }, onClear = { crashVm.clear() }, onDismiss = onDismiss)
}
