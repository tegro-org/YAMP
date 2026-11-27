package org.tegro.yamp.data.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.repository.AuthRepository
import org.tegro.yamp.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which Yandex accounts the Plus gate refuses right now (D101): the server said "no Plus" and the developer option is
 * off ([org.tegro.yamp.domain.model.UserAccount.blocked]). Jellyfin and "This device" are never in it.
 */
interface PlusGate {
    /** The refused accounts' keys (`AccountId.value`). */
    val blocked: StateFlow<Set<String>>

    fun blocks(account: AccountId?): Boolean = account != null && account.value in blocked.value

    /** The developer option has been read: before that [blocked] assumes it is off (the strict side). */
    val isReady: Boolean get() = true

    /** Waits (a moment at most) until [isReady], so a start does not refuse a developer's account for a cold preference. */
    suspend fun awaitReady() = Unit

    object None : PlusGate {
        override val blocked: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    }
}

@Singleton
class AccountPlusGate @Inject constructor(
    authRepository: AuthRepository,
    settings: SettingsRepository
) : PlusGate {
    // Unconfined: a change of an account's answer or of the option is visible to the next check at once.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val ready = CompletableDeferred<Unit>()

    override val isReady: Boolean get() = ready.isCompleted

    // Known no-Plus accounts are refused from the first moment (the option assumed off until its preference is read;
    // starts wait for it, see awaitReady): a cold start never plays known no-Plus music. Only a clear "no" ever counts.
    private val state = MutableStateFlow(blockedKeys(authRepository.allAccounts.value, allow = false))
    override val blocked: StateFlow<Set<String>> = state

    init {
        scope.launch {
            combine(authRepository.allAccounts, settings.allowYandexWithoutPlus) { accounts, allow -> blockedKeys(accounts, allow) }
                .collect {
                    // The set first, then "ready": a start that was waiting sees the answer for the option it waited for
                    state.value = it
                    ready.complete(Unit)
                }
        }
    }

    override suspend fun awaitReady() {
        withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() }
    }

    private fun blockedKeys(accounts: List<UserAccount>, allow: Boolean): Set<String> =
        accounts.filter { it.blocked(allow) }.map { it.accountId().value }.toSet()

    private companion object {
        const val READY_TIMEOUT_MS = 2_000L
    }
}
