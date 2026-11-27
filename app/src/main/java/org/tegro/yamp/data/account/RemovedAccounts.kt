package org.tegro.yamp.data.account

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The names of signed-out accounts whose queues are kept hidden (D62), by account key (`AccountId.value`), so the
 * "Removed accounts" group can still show whose a queue is. Only the display name: no token, nothing secret — a plain
 * preferences file. A name is forgotten when its account signs in again. Owned by the auth store
 * ([org.tegro.yamp.data.repository.AuthRepositoryImpl]), which lists the accounts it stands beside.
 */
class RemovedAccounts(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _names = MutableStateFlow(read())

    /** Account key → the name it had when it was signed out. */
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    fun remember(key: String, name: String) {
        prefs.edit { putString(key, name) }
        _names.value = read()
    }

    fun forget(key: String) {
        if (!prefs.contains(key)) return
        prefs.edit { remove(key) }
        _names.value = read()
    }

    private fun read(): Map<String, String> = prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    private companion object {
        const val FILE = "removed_accounts"
    }
}
