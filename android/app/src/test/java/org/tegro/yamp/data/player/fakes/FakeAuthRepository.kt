package org.tegro.yamp.data.player.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.repository.AuthRepository

/** One signed-in account; its [UserAccount.folderName] is the user folder the controller saves and restores under. */
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
    override fun switchAccount(uid: String): Boolean = false
    override fun removeAccount(uid: String): UserAccount? = null
    override fun clearAllAccounts() = Unit
    override fun getDefaultAccountUid(): String? = null
    override fun setDefaultAccountUid(uid: String?) = Unit
}
