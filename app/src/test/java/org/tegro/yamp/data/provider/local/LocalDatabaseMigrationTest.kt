package org.tegro.yamp.data.provider.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.provider.local.db.LocalDatabase
import java.io.File

/**
 * `local.db` migrations (F1, docs/phase-4d-local-files.md §2). v1 is the first schema, so today this proves that a
 * database built exactly from the exported `schemas/…/1.json` opens with the current entities and keeps its rows —
 * the scaffold every later version extends: export N+1, add `MIGRATION_N_N+1` in [LocalDatabase.build] and here,
 * and a test that builds vN with rows and opens it. (Robolectric does not see test assets, hence no
 * MigrationTestHelper — as CacheDatabaseMigrationTest.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalDatabaseMigrationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun aV1DatabaseOpensWithTheCurrentSchemaAndKeepsItsUserData() {
        val file = File(context.filesDir, "local-migration-test.db").apply { delete() }
        createFromSchema(file, version = 1) { db ->
            db.execSQL("INSERT INTO sources (id, volumeId, rootRel, treeUri, includeHidden, addedAt) VALUES (1, '6917-1912', 'Music', NULL, 0, 5)")
            db.execSQL("INSERT INTO excludes (sourceId, relPath) VALUES (1, 'Music/Skip')")
            db.execSQL("INSERT INTO favourites (ref, addedAt) VALUES ('local:track:6917-1912/Music/a.mp3', 7)")
            db.execSQL("INSERT INTO moves (oldRef, newRef, at) VALUES ('local:track:6917-1912/a.mp3', 'local:track:6917-1912/b.mp3', 9)")
        }

        val db = Room.databaseBuilder(context, LocalDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening validates the schema against the entities; the queries prove the user data survived
            assertEquals(listOf("Music"), db.sources().all().map { it.rootRel })
            assertEquals(listOf("Music/Skip"), db.sources().excludes(1))
            assertEquals(1, db.index().moves().size)
            assertEquals(1, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    private fun createFromSchema(file: File, version: Int, more: (SQLiteDatabase) -> Unit = {}) {
        val schema = JSONObject(File("schemas/org.tegro.yamp.data.provider.local.db.LocalDatabase/$version.json").readText())
            .getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        more(db)
        db.version = version
        db.close()
    }
}
