package org.tegro.yamp.data.provider.local

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tegro.yamp.R
import org.tegro.yamp.data.provider.local.db.SourceEntity
import org.tegro.yamp.data.provider.local.scan.LocalScanWorker
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.ProviderAccount
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the pseudo-account "This device" exists (docs/phase-4d-local-files.md §8, D92): it does while at least one
 * source does. A plain preferences flag, read synchronously at start — the queue restore asks "is this queue's account
 * signed in" (D62) before any database is open, and a "no" would hide the local queues.
 */
open class LocalAccountStore internal constructor(private val prefs: () -> SharedPreferences, private val name: () -> String) {
    private val _present = MutableStateFlow(prefs().getBoolean(KEY, false))
    val present: StateFlow<Boolean> = _present.asStateFlow()

    fun set(present: Boolean) {
        if (present == _present.value) return
        prefs().edit { putBoolean(KEY, present) }
        _present.value = present
    }

    /** The account as the account lists show it ("This device" in the app's language), while it exists. */
    fun account(): ProviderAccount? = if (_present.value) providerAccount() else null

    fun providerAccount(): ProviderAccount = ProviderAccount(Provider.LOCAL, AccountId.local(), name(), avatarUrl = null, hasPlus = false)

    private companion object {
        const val KEY = "present"
    }
}

@Singleton
class AppLocalAccountStore @Inject constructor(@ApplicationContext context: Context) :
    LocalAccountStore({ context.getSharedPreferences("local_account", Context.MODE_PRIVATE) }, { context.getString(R.string.local_device_name) })

/** What the "This device" lifecycle does to the rest of the app: queues (D62, D83), the removed-accounts names. */
interface LocalAccountHooks {
    /** The account appeared (its first source): its queues come back, or its empty default queue is made (D83). */
    fun appeared(account: AccountId, name: String)

    /** The last source went: its queues stay, hidden, and playback leaves them (D62). */
    fun disappeared(account: AccountId, name: String)
}

/**
 * The "This device" lifecycle (docs/phase-4d-local-files.md §8): adding the first source creates the account (at the
 * end of the account order, D90) with its default queue; removing the last one hides it (D62: its queues are kept). A
 * new source is scanned at once. [reconcile] at start brings the flag in line with the sources table.
 */
@Singleton
class LocalAccountSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LocalAccountStore,
    private val sources: LocalSources,
    private val hooks: LocalAccountHooks
) {
    /** A folder from the system picker (API ≥ 30); null when the tree is not on a storage volume. */
    suspend fun addTree(treeUri: Uri): SourceEntity? = sources.add(treeUri)?.also(::added)

    /** A folder from the app's own chooser (API ≤ 29). */
    suspend fun addPath(volumeId: String, relPath: String): SourceEntity? = sources.addPath(volumeId, relPath)?.also(::added)

    suspend fun remove(sourceId: Long) {
        sources.remove(sourceId)
        if (sources.all().isEmpty()) leave()
    }

    /** At start: a flag left behind by a crash between the two writes follows the sources table. */
    suspend fun reconcile() {
        val any = sources.all().isNotEmpty()
        if (any && !store.present.value) appear() else if (!any && store.present.value) leave()
    }

    private fun added(source: SourceEntity) {
        appear()
        LocalScanWorker.enqueue(context, sourceId = source.id)
    }

    private fun appear() {
        if (store.present.value) return
        store.set(true)
        hooks.appeared(AccountId.local(), store.providerAccount().displayName)
    }

    private fun leave() {
        if (!store.present.value) return
        hooks.disappeared(AccountId.local(), store.providerAccount().displayName)
        store.set(false)
    }
}

/** [LocalAccountHooks] on the app's queues: the default queue at appearance (D83), the sign-out rules at the end (D62). */
@Singleton
class AppLocalAccountHooks @Inject constructor(
    private val playbackController: org.tegro.yamp.data.player.PlaybackController,
    private val authRepository: org.tegro.yamp.domain.repository.AuthRepository
) : LocalAccountHooks {
    override fun appeared(account: AccountId, name: String) {
        authRepository.forgetRemovedAccount(account.value)
        playbackController.ensureDefaultQueue(account, name)
    }

    override fun disappeared(account: AccountId, name: String) {
        playbackController.leaveAccount(account, playbackController.fallbackAccount(setOf(account)))
        authRepository.rememberRemovedAccount(account.value, name)
    }
}
