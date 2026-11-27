package org.tegro.yamp.data.provider.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The local provider's own database `local.db` (docs/phase-4d-local-files.md §2, D94), in app-internal storage, never
 * on a volume. Two kinds of tables:
 * - **index** (rebuildable, a full rescan may rewrite it): [VolumeEntity], [DirEntity], [FileEntity], [TrackEntity];
 * - **user data** (never wiped by a rescan): [SourceEntity], [ExcludeEntity], [FavouriteEntity], [MoveEntity].
 *
 * F1 rules: the schema is exported (`app/schemas/…LocalDatabase/`), every change ships a migration (added in [build])
 * and a test (LocalDatabaseMigrationTest), never a destructive fallback.
 */
@Database(
    entities = [
        VolumeEntity::class, SourceEntity::class, ExcludeEntity::class, DirEntity::class, FileEntity::class,
        TrackEntity::class, FavouriteEntity::class, MoveEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun sources(): LocalSourceDao
    abstract fun index(): LocalIndexDao
    abstract fun library(): LocalLibraryDao

    companion object {
        const val FILE_NAME = "local.db"

        /** v1 is the first schema: each later version adds `.addMigrations(MIGRATION_N_N+1)` here, with its test. */
        fun build(context: Context): LocalDatabase = Room.databaseBuilder(context, LocalDatabase::class.java, FILE_NAME).build()
    }
}
