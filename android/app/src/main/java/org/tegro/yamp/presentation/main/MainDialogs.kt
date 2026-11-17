package org.tegro.yamp.presentation.main

import org.tegro.yamp.domain.queue.fitsQueueOf
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.tegro.yamp.R
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.common.id
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.data.updater.UpdateCheckResult
import org.tegro.yamp.presentation.account.AccountManagementDialog
import org.tegro.yamp.presentation.account.AccountViewModel
import org.tegro.yamp.presentation.catalog.CatalogViewModel
import org.tegro.yamp.presentation.crash.CrashLogDialog
import org.tegro.yamp.presentation.crash.CrashLogViewModel
import org.tegro.yamp.presentation.main.components.TrackContextMenu
import org.tegro.yamp.presentation.queues.LocalQueueSwitcher
import org.tegro.yamp.presentation.queues.QueueSwitcherRequest
import org.tegro.yamp.presentation.update.UpdateDialog
import org.tegro.yamp.presentation.update.UpdateViewModel
import kotlinx.coroutines.launch

/** What the track menu was opened for; [pickPlaylist] goes straight to the playlist picker. */
internal data class TrackMenuRequest(
    val track: Track,
    val playlist: Playlist?,
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
    val trackLabel = listOfNotNull(track.title.ifEmpty { null }, track.artists.joinToString { it.name }.takeIf { it.isNotBlank() }).joinToString(" · ")
    TrackContextMenu(
        track = track,
        inPlaylist = request.playlist,
        playlists = playlists,
        isLiked = track.id in likedTrackIds,
        startWithPlaylists = request.pickPlaylist,
        onDismiss = onDismiss,
        onPlayNext = {
            viewModel.enqueueNext(track)
            scope.launch { snackbar.showSnackbar(playNextDone) }
        },
        onToggleLike = { viewModel.toggleLike(track) },
        onAddTo = { playlistsVm.addTrack(it, track) },
        onCreatePlaylist = { playlistsVm.create(it, thenAdd = track) },
        onRemoveFromPlaylist = { request.playlist?.let { playlistsVm.removeTrack(it, track.id) } },
        onTrackWave = { viewModel.waveOrRadio(track) { queueSwitcher?.open(QueueSwitcherRequest.StartRadio.of(track)) } },
        waveActive = viewModel.waveActive.collectAsStateWithLifecycle().value,
        onStartRadio = queueSwitcher?.let { switcher -> { switcher.open(QueueSwitcherRequest.StartRadio.of(track)) } },
        onAddToEnd = { viewModel.addToEnd(listOf(track)) },
        onAddToQueue = { queueSwitcher?.open(QueueSwitcherRequest.AddTo(listOf(track), trackLabel)) },
        fitsActiveQueue = viewModel.activeQueue.collectAsStateWithLifecycle().value
            ?.let { active -> track.ref.fitsQueueOf(active.queue.account?.let { org.tegro.yamp.domain.account.AccountId(it) }) } ?: true,
        onOpenArtist = track.artists.firstNotNullOfOrNull { artist -> artist.ref?.id?.let { id -> id to artist.name } }
            ?.let { (id, name) ->
                {
                    onLeavePlayer()
                    catalogVm.openArtist(id, name)
                }
            },
        onOpenAlbum = track.album?.let { album ->
            val albumId = album.ref?.id ?: return@let null
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

/**
 * Account management (from Settings): add, remove, default. No switcher and no startup picker any more — every
 * signed-in account is live at once and the active queue's account is the context (D56).
 */
@Composable
internal fun AccountHost(
    accountVm: AccountViewModel,
    showManagement: Boolean,
    onCloseManagement: () -> Unit,
    onAddAccount: () -> Unit,
    showChooser: Boolean = false,
    onChooser: (Boolean) -> Unit = {}
) {
    val accounts by accountVm.allAccounts.collectAsStateWithLifecycle()
    val active by accountVm.activeAccount.collectAsStateWithLifecycle()
    val defaultUid by accountVm.defaultAccountUid.collectAsStateWithLifecycle()
    val hidden by accountVm.hiddenAccounts.collectAsStateWithLifecycle()
    val jellyfin by accountVm.jellyfinRows.collectAsStateWithLifecycle()
    var jellyfinSignIn by remember { mutableStateOf(false) }
    if (showChooser) {
        org.tegro.yamp.presentation.account.AddAccountChooser(
            onYandex = { onChooser(false); onAddAccount() },
            onJellyfin = { onChooser(false); jellyfinSignIn = true },
            onDismiss = { onChooser(false) }
        )
    }
    if (jellyfinSignIn) org.tegro.yamp.presentation.account.JellyfinSignInHost(onDismiss = { jellyfinSignIn = false })
    if (showManagement) {
        AccountManagementDialog(
            accounts = accounts,
            activeAccount = active,
            defaultAccountUid = defaultUid,
            onSetDefaultAccount = { accountVm.setDefaultAccount(it) },
            onRemoveAccount = { accountVm.removeAccount(it) },
            // "Add account" asks where the music is: Yandex or Jellyfin (step 21)
            onAddNewAccount = { onChooser(true) },
            onLogoutCurrent = { accountVm.logoutCurrentAccount() },
            onLogoutAll = { accountVm.logoutAllAccounts() },
            onDismiss = onCloseManagement,
            // Debug: test sign-out without losing credentials
            debugActions = if (org.tegro.yamp.BuildConfig.DEBUG) {
                org.tegro.yamp.presentation.account.AccountDebugActions(
                    hidden = hidden,
                    onHide = { accountVm.hideAccount(it) },
                    onUnhide = { accountVm.unhideAccount(it) }
                )
            } else null,
            jellyfinAccounts = jellyfin,
            onRemoveJellyfin = { accountVm.removeJellyfin(it) }
        )
    }
}

@Composable
internal fun CrashLogHost(crashVm: CrashLogViewModel, onDismiss: () -> Unit) {
    val reports by crashVm.reports.collectAsStateWithLifecycle()
    CrashLogDialog(reports = reports, onSend = { crashVm.send() }, onClear = { crashVm.clear() }, onDismiss = onDismiss)
}
