package org.tegro.yamp.presentation.update

import kotlinx.coroutines.flow.first
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tegro.yamp.data.network.NetworkMonitor
import org.tegro.yamp.data.updater.ApkInstallPreCheck
import org.tegro.yamp.data.updater.GitHubUpdateManager
import org.tegro.yamp.data.updater.UpdateCheckResult
import org.tegro.yamp.data.updater.UpdateCheckWorker
import org.tegro.yamp.data.updater.UpdateSchedule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/** In-app updates from GitHub releases: check, download (with the SHA-256 check) and install. */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateManager: GitHubUpdateManager,
    private val settingsRepository: org.tegro.yamp.domain.repository.SettingsRepository,
    private val networkMonitor: NetworkMonitor,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _checkResult = MutableStateFlow<UpdateCheckResult?>(null)
    val checkResult: StateFlow<UpdateCheckResult?> = _checkResult.asStateFlow()

    private val _downloadProgress = MutableStateFlow(-1f)
    /** -1 idle, 0..1 downloading, 2 downloaded and ready to install. */
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isSignatureMismatch = MutableStateFlow(false)
    val isSignatureMismatch: StateFlow<Boolean> = _isSignatureMismatch.asStateFlow()

    private var downloadedApkFile: java.io.File? = null

    init {
        // Checked in the background twice a day, also while the app stays closed (update → notification)
        UpdateCheckWorker.plan(context)
        // Silent checks are off in debug builds: a release APK cannot be installed over a debug one
        // (different signing key), so the offer would only get in the way.
        if (!BuildConfig.DEBUG) {
            // Silent check on launch, after a delay for network readiness
            viewModelScope.launch {
                delay(5000L)
                checkForUpdates(silent = true)
            }
            // A check that failed (no network at that second: in a car it comes later) is made again once the
            // network is back, not only at the next start, which on a head unit may be weeks away
            networkMonitor.isConnectedFlow
                .drop(1)
                .filter { it }
                .onEach {
                    if (UpdateSchedule.lastFailed(context)) {
                        delay(NETWORK_SETTLE_MS)
                        checkForUpdates(silent = true)
                    }
                }
                .launchIn(viewModelScope)
        }
        // Leftover update files from an earlier session
        viewModelScope.launch(Dispatchers.IO) { updateManager.cleanupUpdateFiles() }
    }

    /**
     * Open for long (a head unit's app runs for days): looked for again, quietly, every few hours. Called from the
     * main screen on ON_RESUME.
     */
    fun onAppResumed() {
        val busy = downloadJob?.isActive == true || _downloadProgress.value in 0f..2f
        if (!BuildConfig.DEBUG && !busy && UpdateSchedule.dueNow(context)) checkForUpdates(silent = true)
    }

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _statusMessage.value = context.getString(R.string.update_status_checking)
            }
            val result = updateManager.checkForUpdates()
            UpdateSchedule.remember(context, ok = result !is UpdateCheckResult.Error)
            val skipped = settingsRepository.skippedUpdateVersion.first()
            val available = result as? UpdateCheckResult.UpdateAvailable
            when {
                // The launch check keeps quiet about a skipped version; Settings say it is there
                available != null && !UpdatePolicy.offerOnLaunch(available.versionName, skipped, silent) ->
                    _statusMessage.value = context.getString(R.string.update_status_skipped, available.versionName)
                !silent || available != null -> _checkResult.value = result
            }
            if (result is UpdateCheckResult.UpToDate && !silent) {
                _statusMessage.value = context.getString(R.string.update_status_latest)
            }
        }
    }

    private var downloadJob: kotlinx.coroutines.Job? = null

    fun startDownload(apkUrl: String) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _downloadProgress.value = 0f
            _statusMessage.value = null
            _isSignatureMismatch.value = false
            val expectedSha256 = (_checkResult.value as? UpdateCheckResult.UpdateAvailable)
                ?.takeIf { it.apkDownloadUrl == apkUrl }
                ?.apkSha256
            val result = updateManager.downloadApk(apkUrl, expectedSha256) { progress ->
                _downloadProgress.value = progress
            }
            result.onSuccess { file ->
                downloadedApkFile = file
                _downloadProgress.value = 2f
                installDownloaded()
            }.onFailure { err ->
                _downloadProgress.value = -1f
                _statusMessage.value = context.getString(R.string.update_status_download_failed, err.localizedMessage.orEmpty())
            }
        }
    }

    fun installDownloaded(forceSystemInstall: Boolean = false) {
        val file = downloadedApkFile ?: return

        // A broken, foreign or older package is never installed; forceSystemInstall only lets the
        // system installer handle a signing-key change (it will ask the user to reinstall)
        val preCheck = updateManager.checkApkCompatibility(file)
        if (preCheck is ApkInstallPreCheck.CorruptedApk) {
            _statusMessage.value = context.getString(R.string.update_status_package_failed, preCheck.reason)
            return
        }
        if (!forceSystemInstall && preCheck is ApkInstallPreCheck.SignatureMismatch) {
            _isSignatureMismatch.value = true
            _statusMessage.value = context.getString(R.string.update_status_reinstall)
            return
        }

        updateManager.installApk(file).onFailure { err ->
            _statusMessage.value = err.localizedMessage
        }
    }

    fun shareOrSaveDownloadedApk() {
        downloadedApkFile?.let { updateManager.shareOrSaveApk(it) }
    }

    val canRequestPackageInstalls: Boolean
        get() = updateManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        updateManager.openInstallPermissionSettings()
    }

    /** "Пропустить эту версию": no more offers of it at launch; a newer version is offered again. */
    fun skip() {
        val version = (_checkResult.value as? UpdateCheckResult.UpdateAvailable)?.versionName ?: return
        viewModelScope.launch { settingsRepository.setSkippedUpdateVersion(version) }
        dismiss()
        _statusMessage.value = context.getString(R.string.update_status_skipped, version)
    }

    fun dismiss() {
        // Closing the window stops a download in flight: it used to run on to the end unseen
        downloadJob?.cancel()
        downloadJob = null
        _checkResult.value = null
        _downloadProgress.value = -1f
        _statusMessage.value = null
        _isSignatureMismatch.value = false
    }

    private companion object {
        /** The network that came back is given this long before the check goes out. */
        private const val NETWORK_SETTLE_MS = 10_000L
    }
}
