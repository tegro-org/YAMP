package org.tegro.yamp.data.provider.jellyfin.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.provider.jellyfin.JellyfinListCache
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One cached answer of a Jellyfin list or page: the provider module's JSON, read only by it. */
@Entity(tableName = "cached_lists")
data class CachedListEntity(
    @PrimaryKey val key: String,
    val json: String,
    val savedAt: Long
)

@Dao
interface CachedListDao {
    @Query("SELECT json FROM cached_lists WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: CachedListEntity)
}

/**
 * A Jellyfin account's own database (docs/phase-3-providers.md D72, D78): `jellyfin-<server>-<user>.db`, holding
 * the last answer of every list and page so the library shows the last visit while the server is away. Deleted on
 * sign-out with the account's cache (D62 hides its queues).
 */
@Database(entities = [CachedListEntity::class], version = 1, exportSchema = true)
abstract class JellyfinCacheDatabase : RoomDatabase() {
    abstract fun lists(): CachedListDao
}

/** The open databases, one per signed-in Jellyfin account. */
@Singleton
class JellyfinDatabases @Inject constructor(@ApplicationContext private val context: Context) {
    private val open = ConcurrentHashMap<String, JellyfinCacheDatabase>()

    /** [account]'s list cache, opened on first use. */
    fun cache(account: AccountId): JellyfinListCache {
        val db = open.computeIfAbsent(account.value) {
            Room.databaseBuilder(context, JellyfinCacheDatabase::class.java, fileName(account)).build()
        }
        return object : JellyfinListCache {
            override suspend fun get(key: String) = db.lists().get(key)
            override suspend fun put(key: String, json: String) =
                db.lists().put(CachedListEntity(key, json, System.currentTimeMillis()))
        }
    }

    /** Closes and deletes [account]'s database (sign-out). */
    fun delete(account: AccountId) {
        open.remove(account.value)?.close()
        context.deleteDatabase(fileName(account))
    }

    companion object {
        /** `jellyfin-<server>-<user>.db`; ids are hex, anything else is replaced so the name is always a file name. */
        fun fileName(account: AccountId): String =
            "jellyfin-" + account.value.substringAfter(':').replace('/', '-').replace(Regex("[^A-Za-z0-9-]"), "_") + ".db"
    }
}
