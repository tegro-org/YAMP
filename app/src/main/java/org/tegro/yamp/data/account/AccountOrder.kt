package org.tegro.yamp.data.account

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The order of the signed-in accounts (D90, canvas `ac-order`): one flat list across services, set in Settings →
 * Accounts and used by everything that lists accounts. Pure rules; [AccountOrderStore] keeps the keys.
 */
object AccountOrdering {

    /**
     * The stored order brought up to date with the signed-in [current] keys (in their default order: "This device",
     * then Jellyfin, then Yandex, each in sign-in order): keys no longer signed in drop out, new ones go to the end. With nothing
     * stored yet (an upgrade, a fresh install) that is the default order itself.
     */
    fun reconcile(stored: List<String>, current: List<String>): List<String> {
        val present = current.toSet()
        val kept = stored.filter { it in present }.distinct()
        return kept + current.filter { it !in kept }
    }

    /** [order] with [key] moved to [index] (clamped); unchanged when [key] is not in it. */
    fun move(order: List<String>, key: String, index: Int): List<String> {
        val from = order.indexOf(key)
        if (from < 0) return order
        val rest = order.toMutableList().apply { removeAt(from) }
        rest.add(index.coerceIn(0, rest.size), key)
        return rest
    }
}

/**
 * The account order's keys (`AccountId.value`) in a plain preferences file: keys only, nothing secret (as
 * [RemovedAccounts]). Written only when the order really changes.
 */
open class AccountOrderStore internal constructor(private val prefs: () -> SharedPreferences) {

    private val serializer = ListSerializer(String.serializer())
    private val _keys = MutableStateFlow(read())
    val keys: StateFlow<List<String>> = _keys.asStateFlow()

    @Synchronized
    fun set(keys: List<String>) {
        if (keys == _keys.value) return
        prefs().edit { if (keys.isEmpty()) remove(KEY) else putString(KEY, Json.encodeToString(serializer, keys)) }
        _keys.value = keys
    }

    private fun read(): List<String> =
        prefs().getString(KEY, null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val KEY = "order"
    }
}

/** The app's store: the `account_order` preferences file. */
@Singleton
class PlainAccountOrderStore @Inject constructor(@ApplicationContext context: Context) :
    AccountOrderStore({ context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }) {

    private companion object {
        const val FILE = "account_order"
    }
}
