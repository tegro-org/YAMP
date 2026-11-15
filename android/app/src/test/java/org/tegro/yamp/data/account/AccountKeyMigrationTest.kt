package org.tegro.yamp.data.account

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.cache.CacheDatabase
import org.tegro.yamp.data.cache.ListItemEntity
import org.tegro.yamp.data.cache.TrackEntity
import org.tegro.yamp.data.cache.ValueEntity
import org.tegro.yamp.data.player.fakes.FakeAuthRepository
import org.tegro.yamp.data.queue.QueueEntity
import org.tegro.yamp.data.queue.RoomQueueStore
import org.tegro.yamp.data.repository.SearchHistoryRepository
import org.tegro.yamp.domain.model.UserAccount

/** Phase 2a step 3 (D59): per-account data moves from the login-based folder name to `yandex:<uid>`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccountKeyMigrationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val anna = UserAccount(uid = "1", login = "anna", token = "a")
    private val boris = UserAccount(uid = "2", login = "boris", token = "b")
    private val auth = FakeAuthRepository(anna)

    private fun migration() = AccountKeyMigration(context, auth, SearchHistoryRepository(context, auth)) { db }

    @After
    fun close() = db.close()

    private suspend fun rows(owner: String) {
        db.dao().upsertTracks(listOf(TrackEntity("42", "{}")))
        db.dao().insertItems(listOf(ListItemEntity(owner, "liked", 0, "42")))
        db.dao().putValue(ValueEntity(owner, "likes_revision", "7"))
    }

    private suspend fun queue(kind: String, account: String?, name: String = kind) =
        db.queueDao().insertQueue(QueueEntity(name = name, kind = kind, account = account))

    /** Account owners only: the migration's own done flag lives under [CacheDatabase.SHARED]. */
    private suspend fun owners() = db.dao().owners().toSet() - CacheDatabase.SHARED
    private suspend fun waves(account: String) = db.queueDao().queues().filter { it.kind == "WAVE" && it.account == account }

    @Test
    fun foldersBecomeUidKeysAndEveryAccountGetsItsWave() = runBlocking {
        auth.allAccounts.value = listOf(anna, boris)
        rows("anna"); rows("boris"); rows(CacheDatabase.SHARED)
        val annaWave = queue("WAVE", "anna")
        val mine = queue("STATIC", null, name = "Mine")

        migration().ensure()

        assertEquals(setOf("yandex:1", "yandex:2"), owners())
        assertEquals("the shared charts stay shared", "7", db.dao().getValue(CacheDatabase.SHARED, "likes_revision"))
        assertEquals("7", db.dao().getValue("yandex:1", "likes_revision"))
        assertEquals("yandex:1", db.queueDao().queue(annaWave)!!.account)
        assertEquals("an unbound queue goes to the default account", "yandex:1", db.queueDao().queue(mine)!!.account)
        assertEquals("boris had no My Wave: it exists now (D63)", 1, waves("yandex:2").size)
        assertEquals(3, db.queueDao().queues().size)
    }

    @Test
    fun twoAccountsWithTheSameFolderShareTheDefaultAccountsKey() = runBlocking {
        val twin = UserAccount(uid = "2", login = "anna", token = "b")
        auth.allAccounts.value = listOf(anna, twin)
        auth.activeAccount.value = twin
        rows("anna")

        migration().ensure()

        assertEquals(setOf("yandex:2"), owners())
    }

    @Test
    fun aSignedOutAccountsFolderGoesAndItsWaveToo_unlessItIsPlaying() = runBlocking {
        rows("ghost")
        val playing = queue("WAVE", "ghost")
        val idle = queue("WAVE", "ghost2")
        db.dao().putValue(ValueEntity(CacheDatabase.SHARED, RoomQueueStore.ACTIVE_QUEUE, playing.toString()))

        migration().ensure()

        assertTrue("ghost" !in owners())
        assertEquals(null, db.queueDao().queue(idle))
        assertEquals("yandex:1", db.queueDao().queue(playing)!!.account)
        assertEquals("anna keeps the playing queue as her one My Wave", listOf(playing), waves("yandex:1").map { it.id })
    }

    @Test
    fun aProvisionalAccountGetsNoWaveUntilItsUidIsKnown_thenItsDataFollows() = runBlocking {
        val provisional = UserAccount(uid = "temp_9", displayName = "Яндекс Аккаунт", token = "t")
        auth.allAccounts.value = listOf(provisional)
        auth.activeAccount.value = provisional
        rows(provisional.legacyFolderName)
        val radio = queue("RADIO", null)
        val migration = migration()

        migration.ensure()
        assertEquals(setOf("yandex:temp_9"), owners())
        assertTrue(waves("yandex:temp_9").isEmpty())

        migration.adoptRealUid(provisional, "5")
        assertEquals(setOf("yandex:5"), owners())
        assertEquals("yandex:5", db.queueDao().queue(radio)!!.account)
    }

    @Test
    fun signingBackInKeepsTheAccountsOwnWave() = runBlocking {
        val old = queue("WAVE", "yandex:5")
        val fresh = queue("WAVE", "yandex:temp_9")
        db.dao().putValue(ValueEntity(CacheDatabase.SHARED, "account_keys_v1", "1"))

        migration().adoptRealUid(UserAccount(uid = "temp_9", token = "t"), "5")

        assertEquals(listOf(old), waves("yandex:5").map { it.id })
        assertEquals(null, db.queueDao().queue(fresh))
    }

    @Test
    fun itRunsOnce() = runBlocking {
        migration().ensure()
        rows("anna")

        migration().ensure()

        assertEquals("a later folder row is not touched again", setOf("anna"), owners())
    }

    @Test
    fun withoutAnyAccountNothingIsTouchedAndItTriesAgainLater() = runBlocking {
        auth.activeAccount.value = null
        auth.allAccounts.value = emptyList()
        rows("anna")

        migration().ensure()
        assertEquals(setOf("anna"), owners())

        auth.activeAccount.value = anna
        auth.allAccounts.value = listOf(anna)
        migration().ensure()
        assertEquals(setOf("yandex:1"), owners())
    }

    @Test
    fun theLegacySnapshotFolderMapsToItsKey() {
        auth.allAccounts.value = listOf(anna, boris)
        val migration = migration()
        assertEquals("yandex:2", migration.keyForLegacyFolder("boris"))
        assertEquals("unknown", migration.keyForLegacyFolder("unknown"))
    }
}
