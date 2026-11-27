package org.tegro.yamp.data.provider.jellyfin

import kotlinx.serialization.Serializable
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.ProviderAccount

/**
 * A signed-in Jellyfin account: one server + one user (docs/phase-3-providers.md D78). Stored encrypted with its
 * access token ([JellyfinAccountStore]); the password is never kept.
 */
@Serializable
data class JellyfinAccount(
    val serverUrl: String,
    val serverId: String,
    val serverName: String,
    val serverVersion: String = "",
    val userId: String,
    val userName: String,
    val token: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    val id: AccountId get() = AccountId.jellyfin(serverId, userId)

    /** "alex · Home Media" — who on which server, as the accounts list and the markers name it. */
    val label: String get() = listOf(userName, serverName).filter { it.isNotBlank() }.joinToString(" · ")

    fun toProviderAccount() = ProviderAccount(Provider.JELLYFIN, id, label, avatarUrl = null, hasPlus = false)
}
