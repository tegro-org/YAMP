package org.tegro.yamp.data.player.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.repository.AuthRepository

/** One signed-in account (more can be added to [allAccounts]); its `accountId()` (`yandex:<uid>`) is the key the controller saves and restores under. */
class FakeAuthRepository(
    account: UserAccount? = UserAccount(uid = "1001", login = "driver", token = "token")
) : AuthRepository {
    override val activeAccount = MutableStateFlow(account)
    override val allAccounts = MutableStateFlow(listOfNotNull(account))
    override val defaultAccountUid = MutableStateFlow<String?>(null)

    override fun getToken(): String? = activeAccount.value?.token
    override fun saveToken(token: String) = Unit
    override fun isAuthorized(): Flow<Boolean> = activeAccount.map { it != null }
    override fun clearToken() = Unit
    override fun getActiveAccount(): UserAccount? = activeAccount.value
    override fun getAllAccounts(): List<UserAccount> = allAccounts.value
    override fun saveOrUpdateAccount(account: UserAccount) {
        activeAccount.value = account
    }
    override fun updatePlus(uid: String, hasPlus: Boolean, checkedAt: Long) {
        allAccounts.value = allAccounts.value.map { if (it.uid == uid) it.copy(hasPlus = hasPlus, plusCheckedAt = checkedAt) else it }
        activeAccount.value = activeAccount.value?.let { active -> allAccounts.value.firstOrNull { it.uid == active.uid } ?: active }
    }
    override fun updateProfile(uid: String, login: String, displayName: String, avatarUrl: String?): Boolean {
        if (allAccounts.value.none { it.uid == uid }) return false
        allAccounts.value = allAccounts.value.map {
            if (it.uid == uid) it.copy(login = login.ifBlank { it.login }, displayName = displayName.ifBlank { it.displayName }, avatarUrl = avatarUrl ?: it.avatarUrl) else it
        }
        activeAccount.value = activeAccount.value?.let { active -> allAccounts.value.firstOrNull { it.uid == active.uid } ?: active }
        return true
    }
    /** Makes another of [allAccounts] the active one, as the real store does. */
    override fun switchAccount(uid: String): Boolean {
        val target = allAccounts.value.firstOrNull { it.uid == uid } ?: return false
        activeAccount.value = target
        return true
    }
    override fun removeAccount(uid: String): UserAccount? = null
    override fun clearAllAccounts() = Unit
    override fun getDefaultAccountUid(): String? = null
    override fun setDefaultAccountUid(uid: String?) = Unit
}
