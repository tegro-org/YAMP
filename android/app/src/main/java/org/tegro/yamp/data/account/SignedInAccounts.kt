package org.tegro.yamp.data.account

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore
import org.tegro.yamp.domain.account.ProviderAccount
import org.tegro.yamp.domain.account.toProviderAccount
import org.tegro.yamp.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every signed-in account of every provider, for what lists or marks accounts (the queue switcher's groups and
 * markers, History's chips, the car panel, Android Auto, Settings → Accounts) and for "is this queue's account
 * signed in" (D62). Yandex accounts first, in their order (the default one first), then Jellyfin's in sign-in order.
 * What only Yandex has — tokens, likes, My Wave, the context account — still goes through [AuthRepository].
 */
@Singleton
class SignedInAccounts @Inject constructor(
    private val authRepository: AuthRepository,
    private val jellyfin: JellyfinAccountStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val accounts: StateFlow<List<ProviderAccount>> = combine(authRepository.allAccounts, jellyfin.accounts) { _, _ -> current() }
        .stateIn(scope, SharingStarted.Eagerly, current())

    fun current(): List<ProviderAccount> =
        authRepository.getAllAccounts().map { it.toProviderAccount() } + jellyfin.accounts.value.map { it.toProviderAccount() }

    /** [key] (`AccountId.value`) belongs to a signed-in account of any provider. */
    fun isSignedIn(key: String?): Boolean = key != null && current().any { it.id.value == key }
}

