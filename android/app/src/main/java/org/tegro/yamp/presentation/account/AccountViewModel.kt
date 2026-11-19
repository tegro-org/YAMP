package org.tegro.yamp.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tegro.yamp.data.account.AccountSessionManager
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Saved accounts: who is signed in, the default one, signing out (no switching any more — D56). */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val session: AccountSessionManager,
    accountContext: org.tegro.yamp.data.account.AccountContext,
    jellyfinStore: org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore,
    private val jellyfinSession: org.tegro.yamp.data.provider.jellyfin.JellyfinAccountSession,
    signedInAccounts: org.tegro.yamp.data.account.SignedInAccounts,
    private val qualityStore: org.tegro.yamp.data.player.AccountQualityStore,
    private val soundMode: org.tegro.yamp.data.player.SoundMode,
    private val playbackController: org.tegro.yamp.data.player.PlaybackController,
    private val accountContextRef: org.tegro.yamp.data.account.AccountContext,
    private val settings: org.tegro.yamp.domain.repository.SettingsRepository,
    private val downloadScheduler: org.tegro.yamp.data.download.DownloadScheduler
) : ViewModel() {

    /** Auto-download per account; an account without its own choice keeps the former global switch. */
    val autoDownloads: StateFlow<Map<String, Boolean>> =
        settings.accountAutoDownloads.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val globalAutoDownload: StateFlow<Boolean> =
        settings.autoDownloadLikedTracks.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Saves [account]'s auto-download; turned on, its liked tracks not on the device yet start downloading. */
    fun setAutoDownload(account: String, enabled: Boolean) {
        viewModelScope.launch {
            settings.setAccountAutoDownload(account, enabled)
            if (enabled) downloadScheduler.downloadAllLiked(auto = true)
        }
    }

    /** Quality per account (canvas `qa-*`): every stored choice; an account without one follows [globalCompatible]. */
    val qualityChoices: StateFlow<Map<String, Boolean>> = qualityStore.choices
    val globalCompatible: StateFlow<Boolean> = soundMode.compatible

    /** The network in use is metered: the "metered" choices apply now. */
    val nowMetered: StateFlow<Boolean> = qualityStore.metered

    /**
     * Saves [account]'s choice for [metered] networks. When it changes what the playing account gets now, the
     * playback follows as with the old global switch (links dropped; a lossless track playing in "compatible"
     * restarts from its position).
     */
    fun setQuality(account: String, metered: Boolean, compatible: Boolean) {
        viewModelScope.launch {
            qualityStore.set(account, metered, compatible)
            val key = org.tegro.yamp.domain.repository.SettingsRepository.accountQualityKey(account, metered)
            kotlinx.coroutines.withTimeoutOrNull(QUALITY_SAVE_WAIT_MS) { qualityStore.choices.first { it[key] == compatible } }
            playbackController.onSoundModeChanged(soundMode.compatibleFor(accountContextRef.current()))
        }
    }

    /** The Jellyfin accounts for Settings → Accounts, with the markers they have among all accounts (D78). */
    val jellyfinRows: StateFlow<List<JellyfinAccountRow>> =
        kotlinx.coroutines.flow.combine(jellyfinStore.accounts, signedInAccounts.accounts) { jellyfin, all ->
            val tags = providerAccountTags(all).associateBy { it.key }
            jellyfin.mapNotNull { account ->
                tags[account.id.value]?.let { JellyfinAccountRow(it, listOf(account.serverVersion.takeIf { v -> v.isNotBlank() }?.let { v -> "Jellyfin $v" }, account.serverUrl.substringAfter("://")).filterNotNull().joinToString(" · ")) }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun removeJellyfin(key: String) = jellyfinSession.signOut(org.tegro.yamp.domain.account.AccountId(key))

    val allAccounts: StateFlow<List<UserAccount>> = authRepository.allAccounts
    val activeAccount: StateFlow<UserAccount?> = authRepository.activeAccount
    val defaultAccountUid: StateFlow<String?> = authRepository.defaultAccountUid

    /** The account the Settings profile shows: the context — the active queue's account (D56). */
    val contextAccount: StateFlow<UserAccount?> = accountContext.user
        .stateIn(viewModelScope, SharingStarted.Eagerly, accountContext.currentUser())

    val accountName: StateFlow<String> = contextAccount
        .map { it?.displayLabel.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, accountContext.currentUser()?.displayLabel.orEmpty())

    val accountAvatarUrl: StateFlow<String?> = contextAccount
        .map { it?.avatarUrl }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)


    fun setDefaultAccount(uid: String?) = authRepository.setDefaultAccountUid(uid)

    /**
     * Takes the user into [uid]'s account (its My Wave queue becomes active). No screen offers this since D56 —
     * a queue of the account is the way in; the instrumented tests use it to re-enter a seeded account.
     */
    fun enterAccount(uid: String) {
        session.launch { switchAccount(uid) }
    }

    fun logoutCurrentAccount() {
        session.launch { logoutCurrent() }
    }

    fun logoutAllAccounts() {
        session.launch { logoutAll() }
    }

    fun removeAccount(uid: String) {
        session.launch { removeAccount(uid) }
    }

    // Debug: test sign-out without losing credentials
    val hiddenAccounts: StateFlow<List<UserAccount>> = authRepository.hiddenAccounts

    fun hideAccount(uid: String) {
        session.launch { hideAccount(uid) }
    }

    fun unhideAccount(uid: String) {
        session.launch { unhideAccount(uid) }
    }

    private companion object {
        const val QUALITY_SAVE_WAIT_MS = 2_000L
    }

    private val UserAccount.displayLabel: String
        get() = displayName.ifBlank { login }.ifBlank { "UID: $uid" }
}
