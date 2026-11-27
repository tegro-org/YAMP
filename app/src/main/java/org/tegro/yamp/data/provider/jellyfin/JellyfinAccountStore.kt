package org.tegro.yamp.data.provider.jellyfin

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.tegro.yamp.data.security.KeystoreStringPreferences
import org.tegro.yamp.domain.account.AccountId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in Jellyfin accounts, tokens included, in an encrypted store of their own (§5: like the Yandex
 * tokens, `KeystoreStringPreferences`). One server + user is one account: a second sign-in of the same pair is
 * refused (D78).
 */
open class JellyfinAccountStore internal constructor(private val prefs: () -> SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(JellyfinAccount.serializer())
    private val _accounts = MutableStateFlow(read())

    /** In the order they were added. */
    val accounts: StateFlow<List<JellyfinAccount>> = _accounts.asStateFlow()

    fun account(id: AccountId): JellyfinAccount? = _accounts.value.firstOrNull { it.id == id }

    fun token(id: AccountId): String? = account(id)?.token

    /** Adds [account]; false (nothing stored) when the same server + user is already signed in. */
    @Synchronized
    fun add(account: JellyfinAccount): Boolean {
        if (_accounts.value.any { it.id == account.id }) return false
        write(_accounts.value + account)
        return true
    }

    /** Removes [id] with its token; the removed record, or null when it was not there. */
    @Synchronized
    fun remove(id: AccountId): JellyfinAccount? {
        val gone = account(id) ?: return null
        write(_accounts.value.filterNot { it.id == id })
        return gone
    }

    private fun read(): List<JellyfinAccount> =
        prefs().getString(KEY, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun write(accounts: List<JellyfinAccount>) {
        prefs().edit { if (accounts.isEmpty()) remove(KEY) else putString(KEY, json.encodeToString(serializer, accounts)) }
        _accounts.value = accounts
    }

    private companion object {
        const val KEY = "jellyfin_accounts_json"
    }
}

/** The app's store: the Keystore-encrypted preferences; plain ones only where the Keystore is broken (as for Yandex). */
@Singleton
class KeystoreJellyfinAccountStore @Inject constructor(@ApplicationContext context: Context) :
    JellyfinAccountStore(lazyPrefs(context)) {

    private companion object {
        const val FILE = "jellyfin_secure"
        const val FALLBACK_FILE = "jellyfin_prefs_fallback"
        const val ALIAS = "yamp_jellyfin_accounts"

        fun lazyPrefs(context: Context): () -> SharedPreferences {
            val prefs by lazy {
                runCatching { KeystoreStringPreferences(context, FILE, ALIAS) }.getOrNull()
                    ?: context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
            }
            return { prefs }
        }
    }
}
