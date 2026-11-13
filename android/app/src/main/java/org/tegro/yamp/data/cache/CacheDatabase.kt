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
 * [owner] is the account's cache folder, or [CacheDatabase.SHARED] for lists common to all accounts.
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
    version = 4
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
    }
}
