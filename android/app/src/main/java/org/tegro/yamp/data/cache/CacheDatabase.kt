package org.tegro.yamp.data.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/** Track metadata, stored once however many lists contain the track. [json] is the serialized DTO. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val json: String
)

/**
 * One position of a track list: likes, a playlist, the wave queue, charts, history.
 * [owner] is the account key (`yandex:<uid>`, Phase 2a; a folder name before), or [CacheDatabase.SHARED] for lists
 * common to all accounts.
 */
@Entity(
    tableName = "list_items",
    primaryKeys = ["owner", "list", "position"],
    indices = [Index("trackId")]
)
data class ListItemEntity(
    val owner: String,
    val list: String,
    val position: Int,
    val trackId: String
)

/** Small per-owner values: revisions, the playlist list. */
@Entity(tableName = "cache_values", primaryKeys = ["owner", "key"])
data class ValueEntity(
    val owner: String,
    val key: String,
    val value: String
)

@Dao
interface CacheDao {
    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Insert
    suspend fun insertItems(items: List<ListItemEntity>)

    @Query("DELETE FROM list_items WHERE owner = :owner AND list = :list")
    suspend fun deleteList(owner: String, list: String)

    /** Replaces a list in one transaction: a crash never leaves it half written. */
    @Transaction
    suspend fun replaceList(owner: String, list: String, tracks: List<TrackEntity>, ids: List<String>) {
        upsertTracks(tracks)
        deleteList(owner, list)
        insertItems(ids.mapIndexed { i, id -> ListItemEntity(owner, list, i, id) })
        deleteOrphanTracks() // e.g. tracks that left the wave queue
    }

    @Query(
        "SELECT t.json FROM list_items i JOIN tracks t ON t.id = i.trackId " +
            "WHERE i.owner = :owner AND i.list = :list ORDER BY i.position"
    )
    suspend fun loadTracks(owner: String, list: String): List<String>

    @Query("SELECT trackId FROM list_items WHERE owner = :owner AND list = :list ORDER BY position")
    suspend fun loadIds(owner: String, list: String): List<String>

    /** The cached metadata of the given tracks, in no particular order (Phase 1 bridge for queue entries). */
    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun tracksById(ids: List<String>): List<TrackEntity>

    @Upsert
    suspend fun putValue(value: ValueEntity)

    @Query("SELECT value FROM cache_values WHERE owner = :owner AND `key` = :key")
    suspend fun getValue(owner: String, key: String): String?

    @Query("DELETE FROM cache_values WHERE owner = :owner AND `key` = :key")
    suspend fun deleteValue(owner: String, key: String)

    @Query("SELECT value FROM cache_values WHERE owner = :owner AND `key` = :key")
    fun valueFlow(owner: String, key: String): kotlinx.coroutines.flow.Flow<String?>

    @Query("DELETE FROM list_items WHERE owner = :owner")
    suspend fun deleteOwnerItems(owner: String)

    @Query("DELETE FROM cache_values WHERE owner = :owner")
    suspend fun deleteOwnerValues(owner: String)

    @Transaction
    suspend fun deleteOwner(owner: String) {
        deleteOwnerItems(owner)
        deleteOwnerValues(owner)
        deleteOrphanTracks()
    }

    /** Every account key that owns rows ([SHARED] included). */
    @Query("SELECT owner FROM list_items UNION SELECT owner FROM cache_values")
    suspend fun owners(): List<String>

    @Query("UPDATE list_items SET owner = :to WHERE owner = :from AND list NOT IN (SELECT list FROM list_items WHERE owner = :to)")
    suspend fun moveOwnerItems(from: String, to: String)

    @Query("UPDATE cache_values SET owner = :to WHERE owner = :from AND `key` NOT IN (SELECT `key` FROM cache_values WHERE owner = :to)")
    suspend fun moveOwnerValues(from: String, to: String)

    /**
     * Re-keys an account's rows (Phase 2a). A list or value [to] already has is kept and the old one dropped:
     * the newer key was written by the app after the change, the old one before it.
     */
    @Transaction
    suspend fun renameOwner(from: String, to: String) {
        moveOwnerItems(from, to)
        moveOwnerValues(from, to)
        deleteOwner(from)
    }

    @Query("DELETE FROM list_items")
    suspend fun deleteAllItems()

    @Query("DELETE FROM cache_values")
    suspend fun deleteAllValues()

    @Query("DELETE FROM tracks")
    suspend fun deleteAllTracks()

    @Transaction
    suspend fun deleteAll() {
        deleteAllItems()
        deleteAllValues()
        deleteAllTracks()
    }

    /** Metadata no list, no queue and no history row refers to any more. */
    @Query(
        "DELETE FROM tracks WHERE id NOT IN (SELECT trackId FROM list_items) AND id NOT IN (SELECT trackId FROM queue_entries) " +
            "AND id NOT IN (SELECT trackId FROM history)"
    )
    suspend fun deleteOrphanTracks()
}

@Database(
    entities = [
        TrackEntity::class, ListItemEntity::class, ValueEntity::class,
        org.tegro.yamp.data.queue.QueueEntity::class, org.tegro.yamp.data.queue.QueueEntryEntity::class,
        org.tegro.yamp.data.history.HistoryEntity::class
    ],
    version = 5
)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun dao(): CacheDao
    abstract fun queueDao(): org.tegro.yamp.data.queue.QueueDao
    abstract fun historyDao(): org.tegro.yamp.data.history.HistoryDao

    companion object {
        /** Owner of the lists every account shares (charts). Never a sanitized folder name. */
        const val SHARED = "~shared"

        @Volatile
        private var instance: CacheDatabase? = null

        /** One database per process, also for tests that build their own cache manager. */
        fun get(context: Context): CacheDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CacheDatabase::class.java, "music_cache.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { instance = it }
        }

        /** v1 → v2: the queues of Phase 1 (docs/phase-1-queues.md §2). Every schema change ships a migration (F1). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `queues` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `origin` TEXT, `seed` TEXT, `account` TEXT, `cursorEntryId` INTEGER, " +
                        "`positionMs` INTEGER NOT NULL, `shuffle` INTEGER NOT NULL, `repeat` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `queue_entries` (`entryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`queueId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `originalPosition` INTEGER NOT NULL, " +
                        "`ref` TEXT NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                        "`coverUrl` TEXT, `durationMs` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`queueId`) REFERENCES `queues`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_entries_queueId_position` ON `queue_entries` (`queueId`, `position`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_entries_ref` ON `queue_entries` (`ref`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_entries_trackId` ON `queue_entries` (`trackId`)")
            }
        }

        /** v2 → v3: the listening history (docs/phase-1b-history.md). The SQL is Room's own export (`schemas/…/3.json`). */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ref` TEXT NOT NULL, " +
                        "`trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `coverUrl` TEXT, " +
                        "`durationMs` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `playedMs` INTEGER NOT NULL, " +
                        "`queueId` INTEGER, `queueName` TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_startedAt` ON `history` (`startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_queueId` ON `history` (`queueId`)")
            }
        }

        /** v3 → v4: a radio's Keep flag (docs/phase-2-radio.md §2). */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `queues` ADD COLUMN `keepHistory` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 → v5: accounts (docs/phase-2a-accounts.md §6) — an index on `queues.account` and the account a history
         * row played under. The account *values* are rewritten from folder names to `yandex:<uid>` in code
         * (`AccountKeyMigration`): a Room migration cannot read the account list from the Keystore.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_queues_account` ON `queues` (`account`)")
                db.execSQL("ALTER TABLE `history` ADD COLUMN `account` TEXT")
            }
        }
    }
}
