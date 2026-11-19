package org.tegro.yamp.data.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.data.player.fakes.FakeSettingsRepository
import org.tegro.yamp.domain.account.AccountId

/** Auto-download per account: an account's own choice wins; without one it keeps the former global switch. */
class AutoDownloadPerAccountTest {

    private val yandex = AccountId.yandex("42").value
    private val jellyfin = AccountId.jellyfin("srv", "user").value

    @Test
    fun `without a choice every account follows the former global switch`() = runBlocking {
        val settings = FakeSettingsRepository()
        assertFalse(settings.autoDownloadFor(yandex))
        settings.autoDownloadLikedTracks.value = true
        assertTrue(settings.autoDownloadFor(yandex))
        assertTrue(settings.autoDownloadFor(jellyfin))
    }

    @Test
    fun `an account's own choice wins and leaves the others alone`() = runBlocking {
        val settings = FakeSettingsRepository().apply { autoDownloadLikedTracks.value = true }
        settings.setAccountAutoDownload(jellyfin, false)
        assertFalse(settings.autoDownloadFor(jellyfin))
        assertTrue(settings.autoDownloadFor(yandex))

        settings.autoDownloadLikedTracks.value = false
        settings.setAccountAutoDownload(yandex, true)
        assertTrue(settings.autoDownloadFor(yandex))
        assertFalse(settings.autoDownloadFor(jellyfin))
    }
}
