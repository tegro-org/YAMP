package org.tegro.yamp.data.provider.jellyfin

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.provider.jellyfin.cache.JellyfinDatabases
import org.tegro.yamp.domain.account.AccountId

/** Each Jellyfin account keeps the last visit in a database of its own, gone with the account (D72, D78, D62). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class JellyfinDatabasesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = JellyfinDatabases(context)
    private val home = AccountId.jellyfin("srv1", "u1")
    private val other = AccountId.jellyfin("srv2", "u1")

    @Test
    fun theFileNamesServerAndUser() {
        assertEquals("jellyfin-srv1-u1.db", JellyfinDatabases.fileName(home))
    }

    @Test
    fun aListIsKeptPerAccountAndGoesWithIt() = runBlocking {
        databases.cache(home).put("albums@0", "{\"Items\":[]}")
        assertEquals("{\"Items\":[]}", databases.cache(home).get("albums@0"))
        assertNull("another account's database knows nothing of it", databases.cache(other).get("albums@0"))

        databases.delete(home)
        assertNull("deleted on sign-out", databases.cache(home).get("albums@0"))
        databases.delete(home)
        databases.delete(other)
    }
}
