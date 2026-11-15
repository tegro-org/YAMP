package org.tegro.yamp.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tegro.yamp.data.account.AccountSessionManager
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Saved accounts: who is signed in, the default one, signing out (no switching any more — D56). */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val session: AccountSessionManager,
    accountContext: org.tegro.yamp.data.account.AccountContext
) : ViewModel() {

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

    private val UserAccount.displayLabel: String
        get() = displayName.ifBlank { login }.ifBlank { "UID: $uid" }
}
