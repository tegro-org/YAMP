package org.tegro.yamp.data.account

import org.tegro.yamp.data.queue.QueueManager
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account a call without an account of its own is for — Home, Library, Search, My Wave
 * (docs/phase-2a-accounts.md §3, D56): the active queue's account while that account is signed in, otherwise the
 * active account. Switching queues therefore switches the context; with one account both are the same one.
 */
@Singleton
class AccountContext @Inject constructor(
    private val authRepository: AuthRepository,
    /** Null where only the active account matters (tests of the network layer). */
    private val queues: QueueManager? = null,
    /** The other providers' accounts (Jellyfin, D78); null in tests that only know Yandex. */
    private val jellyfin: org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore? = null
) {
    fun current(): AccountId? = signedIn(queues?.active?.value?.queue?.account) ?: authRepository.getActiveAccount()?.accountId()

    /**
     * The context account as a live value, for what shows "the current account" (the Settings profile): it follows
     * the active queue, so a sign-in or an "Unhide" that sets the store's active account can't leave it stale.
     */
    val user: Flow<UserAccount?> = combine(
        queues?.active ?: flowOf(null),
        authRepository.allAccounts,
        authRepository.activeAccount
    ) { active, accounts, fallback -> contextUser(active?.queue?.account, accounts, fallback) }

    fun currentUser(): UserAccount? =
        contextUser(queues?.active?.value?.queue?.account, authRepository.getAllAccounts(), authRepository.getActiveAccount())

    /**
     * [account] (`AccountId.value`) is signed in with any provider — what decides whether a queue is hidden as a
     * removed account's (D62). [signedIn] stays Yandex-only: it picks the context for Yandex's Home, likes and wave.
     */
    fun signedInAnyProvider(account: String?): Boolean =
        signedIn(account) != null || (account != null && jellyfin?.accounts?.value?.any { it.id.value == account } == true)

    /**
     * The signed-in account a track belongs to (D84): for a Jellyfin track the first signed-in account on its server
     * (refs name the server, D78), for a Yandex one null — the context account plays it (D56).
     */
    fun accountOf(track: org.tegro.yamp.domain.queue.TrackRef): AccountId? {
        val ref = org.tegro.yamp.domain.queue.MediaRef.parse(track.value) ?: return null
        if (ref.providerType != org.tegro.yamp.domain.account.Provider.JELLYFIN.key) return null
        return jellyfin?.accounts?.value?.firstOrNull { it.serverId == ref.instance }?.id
    }

    /** The signed-in Jellyfin accounts in sign-in order, with the name their default queue gets (D83). */
    fun jellyfinAccounts(): List<Pair<AccountId, String>> =
        jellyfin?.accounts?.value.orEmpty().map { it.id to it.serverName.ifBlank { "Jellyfin" } }

    /** [account] when it is a signed-in account; a queue of a signed-out one gives no context (D62). */
    fun signedIn(account: String?): AccountId? =
        account?.let { key -> authRepository.getAllAccounts().firstOrNull { it.accountId().value == key }?.accountId() }
}

/** The context rule on plain values: the active queue's account while it is signed in, else the active account. */
internal fun contextUser(queueAccount: String?, accounts: List<UserAccount>, active: UserAccount?): UserAccount? =
    queueAccount?.let { key -> accounts.firstOrNull { it.accountId().value == key } } ?: active
