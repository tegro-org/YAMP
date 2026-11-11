package org.tegro.yamp.data.cache

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every schema change ships a migration and a test (ARCHITECTURE F1). v1 → v2: the queues of Phase 1.
 * A v1 database is built from the exported `schemas/…/1.json` exactly as Room created it; Room then opens it
 * with [CacheDatabase.MIGRATION_1_2] and validates the result against the entities — a wrong CREATE in the
 * migration fails here, not on a user's device. (Robolectric does not see test assets, hence no MigrationTestHelper.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CacheDatabaseMigrationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun aV1DatabaseKeepsItsTracksAndGainsTheQueueTables() = runBlocking {
        val file = File(context.filesDir, "migration-test.db").apply { delete() }
        createV1(file)

        val db = Room.databaseBuilder(context, CacheDatabase::class.java, file.absolutePath)
            .addMigrations(CacheDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening runs the migration and validates the schema; the queries prove the old data survived
            assertEquals(listOf("42"), db.dao().tracksById(listOf("42")).map { it.id })
            assertEquals(listOf("42"), db.dao().loadIds("u", "liked"))
            assertEquals(0, db.queueDao().count())
            assertEquals(2, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** The tables of schema version 1, from the SQL Room exported for it. */
    private fun createV1(file: File) {
        val schema = JSONObject(File("schemas/org.tegro.yamp.data.cache.CacheDatabase/1.json").readText()).getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            val indices = entity.optJSONArray("indices")
            if (indices != null) for (j in 0 until indices.length()) {
                val index = indices.getJSONObject(j)
                db.execSQL(index.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
        }
        // room_master_table with the v1 identity hash, as Room wrote it
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL("INSERT INTO tracks (id, json) VALUES ('42', '{\"id\":42}')")
        db.execSQL("INSERT INTO list_items (owner, list, position, trackId) VALUES ('u', 'liked', 0, '42')")
        db.version = 1
        db.close()
    }
}
