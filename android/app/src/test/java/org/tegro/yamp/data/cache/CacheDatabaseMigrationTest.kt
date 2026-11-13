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
 * Every schema change ships a migration and a test (ARCHITECTURE F1). v1 → v2: the queues of Phase 1; v2 → v3:
 * the listening history (Phase 1b); v3 → v4: a radio's Keep flag (Phase 2). An old database is built from the exported `schemas/…/N.json` exactly as
 * Room created it; Room then opens it with the migrations and validates the result against the entities — a
 * wrong CREATE in a migration fails here, not on a user's device. (Robolectric does not see test assets,
 * hence no MigrationTestHelper.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CacheDatabaseMigrationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun aV1DatabaseKeepsItsTracksAndGainsTheQueueAndHistoryTables() = runBlocking {
        val file = File(context.filesDir, "migration-test.db").apply { delete() }
        createFromSchema(file, version = 1)

        val db = open(file)
        try {
            // Opening runs the migrations and validates the schema; the queries prove the old data survived
            assertEquals(listOf("42"), db.dao().tracksById(listOf("42")).map { it.id })
            assertEquals(listOf("42"), db.dao().loadIds("u", "liked"))
            assertEquals(0, db.queueDao().count())
            assertEquals(0, db.historyDao().count())
            assertEquals(4, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun aV2DatabaseKeepsItsQueuesAndGainsTheHistoryTable() = runBlocking {
        val file = File(context.filesDir, "migration-test-2.db").apply { delete() }
        createFromSchema(file, version = 2) { db ->
            db.execSQL(
                "INSERT INTO queues (id, name, kind, positionMs, shuffle, `repeat`, sortOrder, updatedAt) " +
                    "VALUES (1, 'With family', 'STATIC', 0, 0, 'ALL', 0, 0)"
            )
            db.execSQL(
                "INSERT INTO queue_entries (queueId, position, originalPosition, ref, trackId, title, artist, durationMs) " +
                    "VALUES (1, 0, 0, 'yandex:track:42', '42', 'Track', 'Band', 0)"
            )
        }

        val db = open(file)
        try {
            assertEquals(listOf("42"), db.dao().tracksById(listOf("42")).map { it.id })
            assertEquals(listOf("With family"), db.queueDao().queues().map { it.name })
            assertEquals(listOf("42"), db.queueDao().entries(1L).map { it.trackId })
            assertEquals(0, db.historyDao().count())
            // The orphan rule now reads the history table too
            db.dao().deleteOrphanTracks()
            assertEquals(listOf("42"), db.dao().tracksById(listOf("42")).map { it.id })
            assertEquals(4, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun aV3DatabaseKeepsItsQueuesAndHistoryAndGainsTheKeepFlag() = runBlocking {
        val file = File(context.filesDir, "migration-test-3.db").apply { delete() }
        createFromSchema(file, version = 3) { db ->
            db.execSQL(
                "INSERT INTO queues (id, name, kind, seed, positionMs, shuffle, `repeat`, sortOrder, updatedAt) " +
                    "VALUES (1, 'My Wave', 'WAVE', 'track:42', 0, 0, 'ALL', -1, 0)"
            )
            db.execSQL(
                "INSERT INTO history (ref, trackId, title, artist, durationMs, startedAt, playedMs, queueId, queueName) " +
                    "VALUES ('yandex:track:42', '42', 'Track', 'Band', 0, 1, 1, 1, 'My Wave')"
            )
        }

        val db = open(file)
        try {
            val wave = db.queueDao().queues().single()
            assertEquals("My Wave", wave.name)
            assertEquals("track:42", wave.seed)
            assertEquals("an old queue has Keep off", false, wave.keepHistory)
            assertEquals(1, db.historyDao().count())
            assertEquals(4, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    private fun open(file: File): CacheDatabase = Room.databaseBuilder(context, CacheDatabase::class.java, file.absolutePath)
        .addMigrations(CacheDatabase.MIGRATION_1_2, CacheDatabase.MIGRATION_2_3, CacheDatabase.MIGRATION_3_4)
        .allowMainThreadQueries()
        .build()

    /** The tables of schema [version], from the SQL Room exported for it, with one liked track in them. */
    private fun createFromSchema(file: File, version: Int, more: (SQLiteDatabase) -> Unit = {}) {
        val schema = JSONObject(File("schemas/org.tegro.yamp.data.cache.CacheDatabase/$version.json").readText()).getJSONObject("database")
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
        // room_master_table with the version's identity hash, as Room wrote it
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL("INSERT INTO tracks (id, json) VALUES ('42', '{\"id\":42}')")
        db.execSQL("INSERT INTO list_items (owner, list, position, trackId) VALUES ('u', 'liked', 0, '42')")
        more(db)
        db.version = version
        db.close()
    }
}
