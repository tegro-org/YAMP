package org.tegro.yamp.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tegro.yamp.data.provider.jellyfin.JellyfinAccountSession
import org.tegro.yamp.data.provider.jellyfin.JellyfinAdded
import org.tegro.yamp.data.provider.jellyfin.JellyfinClients
import org.tegro.yamp.provider.jellyfin.JellyfinSignIn
import org.tegro.yamp.provider.jellyfin.ServerCheck
import org.tegro.yamp.provider.jellyfin.SignInError
import org.tegro.yamp.provider.jellyfin.SignInFailed
import org.tegro.yamp.provider.jellyfin.SignedIn
import javax.inject.Inject

/** The Jellyfin sign-in form (docs/phase-3-providers.md §5, the step-20 canvas, section 9). */
data class JellyfinSignInUiState(
    val address: String = "",
    val check: AddressCheck = AddressCheck.Idle,
    val way: Way = Way.PASSWORD,
    val user: String = "",
    val password: String = "",
    /** The Quick Connect code on screen, while it waits for the confirmation in Jellyfin. */
    val code: String? = null,
    val approved: Boolean = false,
    val busy: Boolean = false,
    val error: Failure? = null,
    /** The same server + user is already signed in (D78): its label, for the "Already added" dialog. */
    val duplicate: String? = null,
    val done: Boolean = false
) {
    enum class Way { QUICK_CONNECT, PASSWORD }
    enum class Failure { WRONG_CREDENTIALS, CODE_EXPIRED, UNREACHABLE }

    sealed interface AddressCheck {
        data object Idle : AddressCheck
        data object Checking : AddressCheck
        data class Found(val name: String, val version: String, val quickConnect: Boolean) : AddressCheck
        data class Bad(val reason: ServerCheck.BadAddress.Reason) : AddressCheck
        data object Unreachable : AddressCheck
        data object NotJellyfin : AddressCheck
    }

    val found: AddressCheck.Found? get() = check as? AddressCheck.Found
    val canSignIn: Boolean get() = found != null && !busy && user.isNotBlank() && password.isNotEmpty()
}

@HiltViewModel
class JellyfinSignInViewModel @Inject constructor(
    clients: JellyfinClients,
    private val session: JellyfinAccountSession
) : ViewModel() {

    private val signIn: JellyfinSignIn = clients.signIn()
    private val _state = MutableStateFlow(JellyfinSignInUiState())
    val state: StateFlow<JellyfinSignInUiState> = _state.asStateFlow()

    private var server: ServerCheck.Found? = null
    private var job: Job? = null

    /** A fresh form each time the sheet opens: the view model outlives one sign-in (activity scope). */
    fun reset() {
        job?.cancel()
        server = null
        _state.value = JellyfinSignInUiState()
    }

    fun setAddress(text: String) {
        job?.cancel()
        server = null
        _state.update { it.copy(address = text, check = JellyfinSignInUiState.AddressCheck.Idle, code = null, approved = false, busy = false, error = null) }
    }

    fun check() {
        job?.cancel()
        _state.update { it.copy(check = JellyfinSignInUiState.AddressCheck.Checking, error = null, code = null) }
        job = viewModelScope.launch {
            val result = signIn.check(_state.value.address)
            server = result as? ServerCheck.Found
            _state.update {
                it.copy(
                    check = when (result) {
                        is ServerCheck.Found -> JellyfinSignInUiState.AddressCheck.Found(result.name, result.version, result.quickConnect)
                        is ServerCheck.BadAddress -> JellyfinSignInUiState.AddressCheck.Bad(result.reason)
                        ServerCheck.Unreachable -> JellyfinSignInUiState.AddressCheck.Unreachable
                        ServerCheck.NotJellyfin -> JellyfinSignInUiState.AddressCheck.NotJellyfin
                    },
                    way = if (result is ServerCheck.Found && result.quickConnect) JellyfinSignInUiState.Way.QUICK_CONNECT else JellyfinSignInUiState.Way.PASSWORD
                )
            }
        }
    }

    fun setWay(way: JellyfinSignInUiState.Way) {
        job?.cancel()
        _state.update { it.copy(way = way, code = null, approved = false, busy = false, error = null) }
    }

    fun setUser(text: String) = _state.update { it.copy(user = text, error = null) }

    fun setPassword(text: String) = _state.update { it.copy(password = text, error = null) }

    fun signInWithPassword() {
        val found = server ?: return
        val now = _state.value
        if (!now.canSignIn) return
        run { signIn.withPassword(found, now.user, now.password) }
    }

    fun startQuickConnect() {
        val found = server ?: return
        job?.cancel()
        _state.update { it.copy(busy = true, error = null, code = null, approved = false) }
        job = viewModelScope.launch {
            try {
                val started = signIn.startQuickConnect(found)
                _state.update { it.copy(code = started.code, busy = false) }
                val signed = signIn.awaitQuickConnect(found, started.secret)
                _state.update { it.copy(approved = true, busy = true) }
                finish(signed)
            } catch (e: SignInFailed) {
                fail(e.error)
            }
        }
    }

    fun cancelQuickConnect() {
        job?.cancel()
        _state.update { it.copy(code = null, approved = false, busy = false) }
    }

    fun dismissDuplicate() = _state.update { it.copy(duplicate = null) }

    private fun run(block: suspend () -> SignedIn) {
        job?.cancel()
        _state.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try {
                finish(block())
            } catch (e: SignInFailed) {
                fail(e.error)
            }
        }
    }

    private fun finish(signed: SignedIn) {
        when (val added = session.add(signed)) {
            is JellyfinAdded.Added -> _state.update { it.copy(busy = false, password = "", done = true) }
            is JellyfinAdded.Duplicate -> _state.update { it.copy(busy = false, password = "", code = null, approved = false, duplicate = added.existing.label) }
        }
    }

    private fun fail(error: SignInError) {
        val failure = when (error) {
            SignInError.WrongCredentials -> JellyfinSignInUiState.Failure.WRONG_CREDENTIALS
            SignInError.QuickConnectExpired -> JellyfinSignInUiState.Failure.CODE_EXPIRED
            SignInError.Unreachable -> JellyfinSignInUiState.Failure.UNREACHABLE
        }
        _state.update { it.copy(busy = false, code = null, approved = false, error = failure) }
    }
}
