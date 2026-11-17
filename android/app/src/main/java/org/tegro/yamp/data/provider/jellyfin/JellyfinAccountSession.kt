package org.tegro.yamp.data.provider.jellyfin

import org.tegro.yamp.data.provider.ProviderRegistry
import org.tegro.yamp.data.provider.jellyfin.cache.JellyfinDatabases
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.repository.AuthRepository
import org.tegro.yamp.provider.jellyfin.SignedIn
import javax.inject.Inject
import javax.inject.Singleton

/** What a completed Jellyfin sign-in led to. */
sealed interface JellyfinAdded {
    data class Added(val account: JellyfinAccount) : JellyfinAdded

    /** The same server + user is already signed in: nothing stored, nothing created twice (D78). */
    data class Duplicate(val existing: JellyfinAccount) : JellyfinAdded
}

/**
 * The Jellyfin account lifecycle (docs/phase-3-providers.md §5): a completed sign-in is stored and gets its default
 * queue (D83); a sign-out hides its queues until the same server + user signs in again (D62).
 */
@Singleton
class JellyfinAccountSession @Inject constructor(
    private val store: JellyfinAccountStore,
    private val clients: JellyfinClients,
    private val authRepository: AuthRepository,
    private val playbackController: PlaybackController,
    private val registry: ProviderRegistry,
    private val databases: JellyfinDatabases
) {
    fun add(signedIn: SignedIn): JellyfinAdded {
        val account = JellyfinAccount(
            serverUrl = signedIn.serverUrl,
            serverId = signedIn.serverId,
            serverName = signedIn.serverName,
            serverVersion = signedIn.serverVersion,
            userId = signedIn.userId,
            userName = signedIn.userName,
            token = signedIn.token
        )
        store.account(account.id)?.let { return JellyfinAdded.Duplicate(it) }
        if (!store.add(account)) return JellyfinAdded.Duplicate(store.account(account.id) ?: account)
        // Its queues come back if it was signed out before (D62); else its empty default queue is made (D83)
        authRepository.forgetRemovedAccount(account.id.value)
        playbackController.ensureDefaultQueue(account.id, account.serverName.ifBlank { "Jellyfin" })
        return JellyfinAdded.Added(account)
    }

    /**
     * Sign-out (D62): the account's queues stay, hidden under "Removed accounts" with its name; when one of them is
     * active, playback leaves it for a staying account (Yandex first, else another Jellyfin one). The token, the HTTP
     * cache and its database go.
     */
    fun signOut(id: AccountId) {
        val account = store.account(id) ?: return
        playbackController.leaveAccount(id, playbackController.fallbackAccount(setOf(id)))
        authRepository.rememberRemovedAccount(id.value, account.label)
        store.remove(id)
        registry.forget(id)
        clients.forget(id)
        databases.delete(id)
    }
}
