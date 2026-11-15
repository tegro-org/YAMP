package org.tegro.yamp.domain.repository

import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.model.UserAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    fun getToken(): String?
    fun saveToken(token: String)
    fun isAuthorized(): Flow<Boolean>
    fun clearToken()

    fun getActiveAccount(): UserAccount?
    fun getAllAccounts(): List<UserAccount>
    fun saveOrUpdateAccount(account: UserAccount)
    fun switchAccount(uid: String): Boolean
    fun removeAccount(uid: String): UserAccount?
    fun clearAllAccounts()

    val activeAccount: StateFlow<UserAccount?>
    val allAccounts: StateFlow<List<UserAccount>>
    val defaultAccountUid: StateFlow<String?>

    fun getDefaultAccountUid(): String?
    fun setDefaultAccountUid(uid: String?)

    /**
     * Signed-out accounts whose queues are kept hidden (D62): account key (`AccountId.value`) → the name it had, so
     * the "Removed accounts" group still shows whose a queue is. Remembered at sign-out, forgotten at a sign-in again.
     */
    val removedAccountNames: StateFlow<Map<String, String>> get() = NoRemovedAccounts
    fun rememberRemovedAccount(key: String, name: String) = Unit
    fun forgetRemovedAccount(key: String) = Unit

    // Debug: test sign-out without losing credentials
    /**
     * Accounts hidden by the debug "Hide (test sign-out)": signed out for queues and UI exactly as a real sign-out
     * (D62), but their record — token included — is kept here, so "Unhide" can sign the same uid in again.
     */
    val hiddenAccounts: StateFlow<List<UserAccount>> get() = NoHiddenAccounts
    fun stashHiddenAccount(account: UserAccount) = Unit
    fun takeHiddenAccount(uid: String): UserAccount? = null

    /**
     * The token of [account], read at request time (Phase 2a, D60). The active account's comes from [getToken],
     * exactly as before per-account clients existed; another signed-in account's from its stored record.
     */
    fun token(account: AccountId): String? {
        if (getActiveAccount()?.accountId() == account) return getToken()
        return getAllAccounts().firstOrNull { it.accountId() == account }?.token
    }
}

private val NoRemovedAccounts: StateFlow<Map<String, String>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
private val NoHiddenAccounts: StateFlow<List<UserAccount>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
