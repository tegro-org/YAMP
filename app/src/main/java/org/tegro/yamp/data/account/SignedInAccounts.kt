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
 * markers, History's chips, the car panel, the Library sections and car columns, Android Auto, Home's groups,
 * Settings → Accounts) and for "is this queue's account signed in" (D62). **In the user's order (D90)**: one flat
 * list set in Settings → Accounts; until it is changed, "This device" (D92) first, then Jellyfin's in sign-in order, then
 * the Yandex accounts (the default one first, then sign-in order; D102); a new account goes to the end.
 * What only Yandex has — tokens, likes, My Wave, the context account — still goes through [AuthRepository].
 */
@Singleton
class SignedInAccounts @Inject constructor(
    private val authRepository: AuthRepository,
    private val jellyfin: JellyfinAccountStore,
    private val order: AccountOrderStore,
    /** "This device" (D92); null in tests without local files. */
    private val local: org.tegro.yamp.data.provider.local.LocalAccountStore? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val accounts: StateFlow<List<ProviderAccount>> =
        combine(authRepository.allAccounts, jellyfin.accounts, order.keys, local?.present ?: kotlinx.coroutines.flow.flowOf(false)) { _, _, _, _ -> current() }
            .stateIn(scope, SharingStarted.Eagerly, current())

    /** The signed-in accounts in the user's order; brings the stored order up to date (new ones at the end). */
    fun current(): List<ProviderAccount> {
        val all = byService()
        val keys = synchronized(order) {
            AccountOrdering.reconcile(order.keys.value, all.map { it.id.value }).also(order::set)
        }
        val byKey = all.associateBy { it.id.value }
        return keys.mapNotNull(byKey::get)
    }

    /** Moves [key] to [index] of the list (Settings → Accounts: a drop after a drag, «Выше» / «Ниже»). */
    fun move(key: String, index: Int) {
        synchronized(order) { order.set(AccountOrdering.move(current().map { it.id.value }, key, index)) }
    }

    /** [key] (`AccountId.value`) belongs to a signed-in account of any provider. */
    fun isSignedIn(key: String?): Boolean = key != null && byService().any { it.id.value == key }

    /** The default order: "This device", then Jellyfin in sign-in order, then Yandex (the default account first). */
    private fun byService(): List<ProviderAccount> =
        listOfNotNull(local?.account()) + jellyfin.accounts.value.map { it.toProviderAccount() } +
            authRepository.getAllAccounts().map { it.toProviderAccount() }
}
