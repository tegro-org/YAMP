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
    private val signedInAccounts: org.tegro.yamp.data.account.SignedInAccounts,
    private val qualityStore: org.tegro.yamp.data.player.AccountQualityStore,
    private val soundMode: org.tegro.yamp.data.player.SoundMode,
    private val playbackController: org.tegro.yamp.data.player.PlaybackController,
    private val accountContextRef: org.tegro.yamp.data.account.AccountContext,
    private val settings: org.tegro.yamp.domain.repository.SettingsRepository,
    private val downloadScheduler: org.tegro.yamp.data.download.DownloadScheduler,
    localOverview: org.tegro.yamp.data.provider.local.LocalOverviewModel,
    plusGate: org.tegro.yamp.data.account.PlusGate
) : ViewModel() {

    /** The Yandex accounts without Plus (D101) and the developer option that lifts the rule. */
    val plusBlocked: StateFlow<Set<String>> = plusGate.blocked
    val allowWithoutPlus: StateFlow<Boolean> =
        settings.allowYandexWithoutPlus.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** "This device" while it has a source (D92): its card in Settings → Accounts. */
    val localAccount: StateFlow<org.tegro.yamp.domain.account.ProviderAccount?> = signedInAccounts.accounts
        .map { list -> list.firstOrNull { it.provider == org.tegro.yamp.domain.account.Provider.LOCAL } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, signedInAccounts.current().firstOrNull { it.provider == org.tegro.yamp.domain.account.Provider.LOCAL })

    /** Its sources, tracks and scan, for the card's lines. */
    val localOverview: StateFlow<org.tegro.yamp.data.provider.local.LocalOverview> = localOverview.overview

    /** The volumes that are not connected (id → name): what lists dim (D96). */
    val absentVolumes: StateFlow<Map<String, String>> = localOverview.overview
        .map { o -> o.sources.filter { it.state == org.tegro.yamp.data.provider.local.SourceState.ABSENT }.associate { it.volumeId to it.volumeName } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
    fun setQuality(account: String, metered: Boolean, choice: SoundChoice) {
        val compatible = choice != SoundChoice.BEST
        val economy = choice == SoundChoice.ECONOMY
        viewModelScope.launch {
            qualityStore.set(account, metered, compatible, economy)
            val key = org.tegro.yamp.domain.repository.SettingsRepository.accountQualityKey(account, metered)
            val economyKey = org.tegro.yamp.domain.repository.SettingsRepository.accountEconomyKey(account, metered)
            kotlinx.coroutines.withTimeoutOrNull(QUALITY_SAVE_WAIT_MS) {
                qualityStore.choices.first { it[key] == compatible && (it[economyKey] == true) == economy }
            }
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

    /** The accounts' keys in the user's order (D90): Settings → Accounts lists them so. */
    val accountOrder: StateFlow<List<String>> = signedInAccounts.accounts
        .map { list -> list.map { it.id.value } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, signedInAccounts.current().map { it.id.value })

    /** A drop after a drag, or «Выше» / «Ниже»: [key] to [index] of the list. */
    fun moveAccount(key: String, index: Int) = signedInAccounts.move(key, index)

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
