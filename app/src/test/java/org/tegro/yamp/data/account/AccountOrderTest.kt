package org.tegro.yamp.data.account

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.player.fakes.FakeAuthRepository
import org.tegro.yamp.data.provider.jellyfin.JellyfinAccount
import org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.account.JellyfinAccountRow
import org.tegro.yamp.presentation.account.accountEntries

/** D90: one flat account order across services, stored as keys; default by service, new accounts to the end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccountOrderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val orderPrefs = context.getSharedPreferences("account_order_test", Context.MODE_PRIVATE)
    private val jellyfinPrefs = context.getSharedPreferences("jellyfin_order_test", Context.MODE_PRIVATE)

    private val anna = UserAccount(uid = "1", login = "anna", token = "t1")
    private val rock = UserAccount(uid = "2", login = "rock", token = "t2")
    private val auth = FakeAuthRepository(anna).apply { allAccounts.value = listOf(anna, rock) }
    private val jellyfin = JellyfinAccountStore { jellyfinPrefs }

    private fun home(user: String = "u1") = JellyfinAccount(
        serverUrl = "http://10.0.2.2:8096", serverId = "srv", serverName = "Home Media", serverVersion = "10.10.3",
        userId = user, userName = "dev-$user", token = "jt"
    )

    private val a = AccountId.yandex("1").value
    private val r = AccountId.yandex("2").value
    private val h = AccountId.jellyfin("srv", "u1").value

    /** A fresh instance over the same files: what an app restart sees. */
    private fun signedIn() = SignedInAccounts(auth, jellyfin, AccountOrderStore { orderPrefs })

    private fun SignedInAccounts.keys() = current().map { it.id.value }

    @Test
    fun theDefaultIsThisDeviceThenJellyfinThenYandexAndItIsStored() {
        val localPrefs = context.getSharedPreferences("local_default_test", Context.MODE_PRIVATE)
        val local = org.tegro.yamp.data.provider.local.LocalAccountStore({ localPrefs }, { "This device" })
        local.set(true)
        jellyfin.add(home())
        val l = AccountId.local().value
        // Nothing stored yet (a fresh install, an upgrade): by service, Yandex in its order last
        assertEquals(listOf(l, h, a, r), SignedInAccounts(auth, jellyfin, AccountOrderStore { orderPrefs }, local).keys())
        // …and it is kept from now on
        assertEquals(listOf(l, h, a, r), AccountOrderStore { orderPrefs }.keys.value)
    }

    @Test
    fun aSavedOrderIsKeptWhateverTheDefault() {
        orderPrefs.edit().putString("order", "[\"$a\",\"$r\"]").commit()
        jellyfin.add(home())
        assertEquals(listOf(a, r, h), signedIn().keys())
    }

    @Test
    fun aNewAccountGoesToTheEndEvenAYandexOne() {
        auth.allAccounts.value = listOf(anna)
        jellyfin.add(home())
        assertEquals(listOf(h, a), signedIn().keys())
        auth.allAccounts.value = listOf(anna, rock)
        assertEquals(listOf(h, a, r), signedIn().keys())
    }

    @Test
    fun thisDeviceJoinsTheEndOfTheOrderAndLeavesWithItsLastSource() {
        // docs/phase-4d-local-files.md step 5: the pseudo-account is an account like the others (D90, D92)
        val localPrefs = context.getSharedPreferences("local_order_test", Context.MODE_PRIVATE)
        val local = org.tegro.yamp.data.provider.local.LocalAccountStore({ localPrefs }, { "This device" })
        val l = AccountId.local().value
        fun withLocal() = SignedInAccounts(auth, jellyfin, AccountOrderStore { orderPrefs }, local)
        assertEquals(listOf(a, r), withLocal().keys())
        local.set(true)
        assertEquals("a new account goes to the end", listOf(a, r, l), withLocal().keys())
        assertEquals("This device", withLocal().current().last().displayName)
        jellyfin.add(home())
        assertEquals("then a later one after it", listOf(a, r, l, h), withLocal().keys())
        withLocal().move(l, 0)
        assertEquals(listOf(l, a, r, h), withLocal().keys())
        local.set(false)
        assertEquals(listOf(a, r, h), withLocal().keys())
        assertTrue(!withLocal().isSignedIn(l))
    }

    @Test
    fun moveUpAndDownAndADropArePersistedAcrossARestart() {
        jellyfin.add(home())
        val accounts = signedIn()
        accounts.move(h, 1) // Move down on Home Media
        assertEquals(listOf(a, h, r), accounts.keys())
        accounts.move(a, 1) // Move down on Anna
        assertEquals(listOf(h, a, r), accounts.keys())
        accounts.move(r, 0) // a drag of Rock to the top
        assertEquals(listOf(r, h, a), accounts.keys())
        accounts.move(r, -1) // past the ends: clamped
        assertEquals(listOf(r, h, a), accounts.keys())
        assertEquals(listOf(r, h, a), signedIn().keys())
        assertEquals(listOf(r, h, a), signedIn().accounts.value.map { it.id.value })
    }

    @Test
    fun aSignedOutAccountLeavesTheOrderAndComesBackAtTheEnd() {
        jellyfin.add(home())
        val accounts = signedIn()
        accounts.move(a, 0)
        assertEquals(listOf(a, h, r), accounts.keys())
        jellyfin.remove(AccountId(h))
        assertEquals(listOf(a, r), accounts.keys())
        jellyfin.add(home())
        assertEquals(listOf(a, r, h), accounts.keys())
    }

    @Test
    fun orderingRules() {
        assertEquals(listOf("y1", "j1"), AccountOrdering.reconcile(emptyList(), listOf("y1", "j1")))
        assertEquals(listOf("j1", "y1", "y2"), AccountOrdering.reconcile(listOf("j1", "gone", "y1"), listOf("y1", "y2", "j1")))
        assertEquals(listOf("b", "a", "c"), AccountOrdering.move(listOf("a", "b", "c"), "a", 1))
        assertEquals(listOf("a", "b", "c"), AccountOrdering.move(listOf("a", "b", "c"), "x", 0))
        assertEquals(listOf("b", "c", "a"), AccountOrdering.move(listOf("a", "b", "c"), "a", 9))
    }

    @Test
    fun settingsListsBothServicesInTheOrder() {
        val tag = AccountTag(h, "dev · Home Media", "D", androidx.compose.ui.graphics.Color.Red)
        val rows = listOf(JellyfinAccountRow(tag, "Jellyfin 10.10.3"))
        assertEquals(listOf(h, a, r), accountEntries(listOf(anna, rock), rows, emptyList(), null).map { it.key })
        val mixed = accountEntries(listOf(anna, rock), rows, listOf(a, h, r), defaultUid = "2")
        assertEquals(listOf(a, h, r), mixed.map { it.key })
        assertEquals(listOf(false, false, true), mixed.map { it.isDefault })
    }
}
