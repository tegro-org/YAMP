package org.tegro.yamp.data.folders

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.tegro.yamp.domain.account.AccountId
import javax.inject.Inject
import javax.inject.Singleton

/** A pinned folder as Home and the Folders root show it without asking the server: its id, name and cover. */
@Serializable
data class PinnedFolder(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    /** The cover's cache key ([org.tegro.yamp.domain.media.Cover.key]); null = the drawn placeholder. */
    @SerialName("cover") val cover: String? = null
)

/**
 * Pinned folders per account (Phase 4b: "pinned folders on top", Home's "Pinned folders"), in pinning order, and the
 * car Library's last tab per account. Plain preferences: nothing secret. They outlive a sign-out, like the account's
 * queues (D62), and come back with the same account.
 */
open class FolderPins internal constructor(private val prefs: () -> SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PinnedFolder.serializer())
    private val _pins = MutableStateFlow(readAll())

    /** Account key ([AccountId.value]) → its pinned folders. */
    val pins: StateFlow<Map<String, List<PinnedFolder>>> = _pins.asStateFlow()

    fun of(account: AccountId): List<PinnedFolder> = _pins.value[account.value].orEmpty()

    fun isPinned(account: AccountId, folderId: String): Boolean = of(account).any { it.id == folderId }

    /** Pins [folder] at the end of the account's list, or unpins it; whether it is pinned now. */
    @Synchronized
    fun toggle(account: AccountId, folder: PinnedFolder): Boolean {
        val now = of(account)
        val pinned = now.none { it.id == folder.id }
        write(account, if (pinned) now + folder else now.filterNot { it.id == folder.id })
        return pinned
    }

    fun lastTab(account: AccountId): String? = prefs().getString(TAB + account.value, null)

    fun rememberTab(account: AccountId, tab: String) = prefs().edit { putString(TAB + account.value, tab) }

    private fun write(account: AccountId, list: List<PinnedFolder>) {
        prefs().edit { if (list.isEmpty()) remove(PINS + account.value) else putString(PINS + account.value, json.encodeToString(serializer, list)) }
        _pins.value = _pins.value.toMutableMap().apply { if (list.isEmpty()) remove(account.value) else put(account.value, list) }
    }

    private fun readAll(): Map<String, List<PinnedFolder>> = prefs().all.entries
        .filter { it.key.startsWith(PINS) && it.value is String }
        .associate { (key, value) ->
            key.removePrefix(PINS) to (runCatching { json.decodeFromString(serializer, value as String) }.getOrNull().orEmpty())
        }
        .filterValues { it.isNotEmpty() }

    private companion object {
        const val PINS = "pins|"
        const val TAB = "car_tab|"
    }
}

@Singleton
class AppFolderPins @Inject constructor(@ApplicationContext context: Context) :
    FolderPins({ context.getSharedPreferences("folder_pins", Context.MODE_PRIVATE) })
