package org.tegro.yamp.data.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.repository.SettingsRepository

/** Quality per account (canvas `qa-*`): network kind × the account's own choice, else the global one. */
class AccountQualityTest {

    private class Source(var metered: Boolean = false) : AccountQualitySource {
        val choices = mutableMapOf<String, Boolean>()
        fun set(account: AccountId, metered: Boolean, compatible: Boolean) {
            choices[SettingsRepository.accountQualityKey(account.value, metered)] = compatible
        }
        override fun chosen(account: String, metered: Boolean): Boolean? = choices[SettingsRepository.accountQualityKey(account, metered)]
        override fun economy(account: String, metered: Boolean): Boolean = choices[SettingsRepository.accountEconomyKey(account, metered)] == true
        override fun isMetered(): Boolean = metered
    }

    private val yandex = AccountId.yandex("42")
    private val jellyfin = AccountId.jellyfin("srv", "user")
    private val lines = mutableListOf<String>()

    private fun mode(source: Source, global: Boolean?): SoundMode =
        SoundMode(MutableStateFlow(global), {}, { true }, source) { lines += it }

    private fun SoundMode.ready(global: Boolean) = runBlocking { withTimeout(2_000) { compatible.first { it == global } } }

    @Test
    fun `the account's own choice follows the kind of network`() {
        val source = Source()
        source.set(yandex, metered = false, compatible = false)
        source.set(yandex, metered = true, compatible = true)
        val mode = mode(source, global = false)

        assertFalse("home Wi-Fi: lossless", mode.compatibleFor(yandex))
        source.metered = true
        assertTrue("the car's metered Wi-Fi: compatible", mode.compatibleFor(yandex))
    }

    @Test
    fun `economy is compatible and applies on its own network only`() {
        val source = Source()
        source.set(yandex, metered = true, compatible = true)
        source.choices[SettingsRepository.accountEconomyKey(yandex.value, metered = true)] = true
        val mode = mode(source, global = false)

        assertFalse("home Wi-Fi: not economy", mode.economyFor(yandex))
        source.metered = true
        assertTrue(mode.compatibleFor(yandex))
        assertTrue(mode.economyFor(yandex))
        assertFalse("another account has no economy of its own", mode.economyFor(jellyfin))
        assertFalse("no account: no economy", mode.economyFor(null))
        assertEquals("yandex:42 metered → economy (account)", lines.last())
    }

    @Test
    fun `an account without a choice of its own follows the global one`() {
        val source = Source(metered = true)
        val mode = mode(source, global = true)
        mode.ready(true)

        assertTrue(mode.compatibleFor(jellyfin))
        assertTrue(runBlocking { mode.isCompatible(jellyfin) })
    }

    @Test
    fun `two accounts keep their own choices`() {
        val source = Source(metered = true)
        source.set(yandex, metered = true, compatible = true)
        source.set(jellyfin, metered = true, compatible = false)
        val mode = mode(source, global = false)

        assertTrue(mode.compatibleFor(yandex))
        assertFalse("Jellyfin keeps the original file on the same network", mode.compatibleFor(jellyfin))
    }

    @Test
    fun `the journal gets one line when the effective choice changes, not on every call`() {
        val source = Source()
        source.set(yandex, metered = true, compatible = true)
        val mode = mode(source, global = false)

        mode.compatibleFor(yandex)
        mode.compatibleFor(yandex)
        source.metered = true
        mode.compatibleFor(yandex)

        assertEquals(
            listOf("yandex:42 unmetered → best (global)", "yandex:42 metered → compatible (account)"),
            lines
        )
    }

    @Test
    fun `the setting keys name the account and the network kind`() {
        assertEquals("quality|jellyfin:srv/user|metered", SettingsRepository.accountQualityKey(jellyfin.value, true))
        assertEquals("quality|yandex:42|unmetered", SettingsRepository.accountQualityKey(yandex.value, false))
    }
}
