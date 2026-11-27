package org.tegro.yamp.presentation.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tegro.yamp.domain.account.Provider

/** D100: the first start offers This device, then Jellyfin, and never Yandex. */
class WelcomeChoicesTest {
    @Test
    fun theFirstStartOffersThisDeviceThenJellyfinOnly() {
        assertEquals(listOf(Provider.LOCAL, Provider.JELLYFIN), welcomeChoices())
    }
}
