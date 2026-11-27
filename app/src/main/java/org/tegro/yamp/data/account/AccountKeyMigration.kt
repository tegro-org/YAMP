package org.tegro.yamp.data.account

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tegro.yamp.R
import org.tegro.yamp.data.cache.CacheDatabase
import org.tegro.yamp.data.cache.CacheKeyMigration
import org.tegro.yamp.data.cache.ValueEntity
import org.tegro.yamp.data.queue.QueueEntity
import org.tegro.yamp.data.queue.RoomQueueStore
import org.tegro.yamp.data.repository.SearchHistoryRepository
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-keys per-account data from the login-based folder name to `yandex:<uid>` (D59, docs/phase-2a-accounts.md §6).
 * Runs once, before the cache is first read: Room's v4 → v5 migration cannot do it, it cannot read the account list
 * from the Keystore. Also re-keys a provisional account (`temp_…`, `me`) once the server has told its uid.
 */
@Singleton
class AccountKeyMigration internal constructor(
    private val context: Context,
    private val authRepository: AuthRepository,
    private val searchHistory: SearchHistoryRepository,
    databaseProvider: () -> CacheDatabase
) : CacheKeyMigration {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        authRepository: AuthRepository,
        searchHistory: SearchHistoryRepository
    ) : this(context, authRepository, searchHistory, { CacheDatabase.get(context) })

    private val database by lazy(databaseProvider)
    private val mutex = Mutex()

    @Volatile
    private var done = false

    override suspend fun ensure() {
        if (done) return
        mutex.withLock {
            if (done) return
            withContext(Dispatchers.IO) {
                try {
                    if (database.dao().getValue(CacheDatabase.SHARED, FLAG) == null) migrate()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // Not marked done in storage: the next start tries again; this one goes on with what it has
                    Log.e(TAG, "Account key migration failed", e)
                }
            }
            done = true
        }
    }

    /** The key an old session snapshot's folder now stands for (the legacy session import). */
    override fun keyForLegacyFolder(folder: String): String {
        val accounts = authRepository.getAllAccounts()
        return AccountKeyPlan.folderKeys(accounts, defaultKey(accounts))[folder] ?: folder
    }

    /**
     * The server has told a provisional account's real uid: its rows, queues and search history follow it. A
     * re-sign-in to an account seen before keeps that account's own My Wave queue (D62) unless the provisional one
     * is the queue playing.
     */
    override suspend fun adoptRealUid(provisional: UserAccount, uid: String): Unit = withContext(Dispatchers.IO) {
        val from = provisional.accountId().value
        val to = AccountId.yandex(uid).value
        if (from == to) return@withContext
        try {
            database.withTransaction {
                val before = waveIds(to).toSet()
                database.dao().renameOwner(from, to)
                database.queueDao().rebindAccount(from, to)
                database.historyDao().rebindAccount(from, to)
                keepOneWave(to, prefer = before)
            }
            searchHistory.renameKeys(mapOf(from to to))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Re-keying $from failed", e)
        }
    }

    private suspend fun migrate() {
        val dao = database.dao()
        val queueDao = database.queueDao()
        val accounts = authRepository.getAllAccounts()
        if (accounts.isEmpty()) {
            // A fresh install has nothing to re-key. Rows without any account to map them to are left alone: the
            // account list may simply not have been readable this time.
            if (dao.owners().all { it == CacheDatabase.SHARED } && queueDao.queues().all { it.account == null }) markDone()
            return
        }
        val defaultKey = defaultKey(accounts) ?: return
        val folderKeys = AccountKeyPlan.folderKeys(accounts, defaultKey)
        val accountKeys = accounts.map { it.accountId().value }.toSet()

        searchHistory.renameKeys(folderKeys)
        database.withTransaction {
            dao.owners().forEach { owner ->
                if (owner == CacheDatabase.SHARED || owner == NO_ACCOUNT || owner in accountKeys) return@forEach
                val to = folderKeys[owner]
                // A folder no saved account has: left by an account signed out before, its uid unknown
                if (to != null) dao.renameOwner(owner, to) else dao.deleteOwner(owner)
            }
            val active = activeQueueId()
            queueDao.queues().forEach { queue ->
                val account = queue.account ?: return@forEach
                if (account in accountKeys) return@forEach
                val to = folderKeys[account]
                when {
                    to != null -> queueDao.rebindAccount(account, to)
                    // My Wave of an account signed out before: nothing can bring it back, unless it is the queue playing
                    queue.id == active -> queueDao.rebindAccount(account, defaultKey)
                    else -> queueDao.deleteQueue(queue.id)
                }
            }
            queueDao.bindUnbound(defaultKey)
            accounts.forEach { account ->
                val key = account.accountId().value
                keepOneWave(key, prefer = emptySet())
                // Every account's My Wave exists from the start (D63); a provisional account gets it with its uid
                if (account.hasRealUid && waveIds(key).isEmpty()) createWave(key)
            }
            markDone()
        }
        Log.i(TAG, "Account keys migrated: ${folderKeys.size} folder(s) for ${accounts.size} account(s)")
    }

    /** Collisions or a re-sign-in can leave two WAVE queues for one account: the playing one, else [prefer], stays. */
    private suspend fun keepOneWave(key: String, prefer: Set<Long>) {
        val waves = waveIds(key)
        if (waves.size < 2) return
        val active = activeQueueId()
        val keep = waves.firstOrNull { it == active } ?: waves.firstOrNull { it in prefer } ?: waves.first()
        waves.filter { it != keep }.forEach { database.queueDao().deleteQueue(it) }
    }

    private suspend fun waveIds(key: String): List<Long> =
        database.queueDao().queues().filter { it.kind == QueueKind.WAVE.name && it.account == key }.map { it.id }

    private suspend fun createWave(key: String) {
        database.queueDao().insertQueue(
            QueueEntity(
                name = context.getString(R.string.queue_my_wave_name), kind = QueueKind.WAVE.name, account = key,
                sortOrder = -1, updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun activeQueueId(): Long? =
        database.dao().getValue(CacheDatabase.SHARED, RoomQueueStore.ACTIVE_QUEUE)?.toLongOrNull()

    private suspend fun markDone() = database.dao().putValue(ValueEntity(CacheDatabase.SHARED, FLAG, "1"))

    /** The account the app starts in (the default one, else the last active): unbound and colliding data goes there. */
    private fun defaultKey(accounts: List<UserAccount>): String? =
        (authRepository.getActiveAccount() ?: accounts.firstOrNull())?.accountId()?.value

    private companion object {
        const val TAG = "AccountKeys"
        const val FLAG = "account_keys_v1"
        /** The owner the app uses while no account is signed in. */
        const val NO_ACCOUNT = "default"
    }
}

/** Which old folder name becomes which account key. Pure, for the tests. */
internal object AccountKeyPlan {
    /**
     * Folder name → `yandex:<uid>`. Two accounts with the same folder name cannot be told apart after the fact: their
     * data goes to [defaultKey] (D59).
     */
    fun folderKeys(accounts: List<UserAccount>, defaultKey: String?): Map<String, String> =
        accounts.groupBy { it.legacyFolderName }.mapValues { (_, same) ->
            if (same.size == 1) same.single().accountId().value else defaultKey ?: same.first().accountId().value
        }
}
