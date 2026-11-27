package org.tegro.yamp.presentation.main

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.ProviderAccount
import org.tegro.yamp.domain.model.StartMusic

class StartMusicPlanTest {
    private fun yandex(uid: String) = ProviderAccount(Provider.YANDEX, AccountId.yandex(uid), uid, null, false)
    private val jellyfin = ProviderAccount(Provider.JELLYFIN, AccountId.jellyfin("srv", "u1"), "Jellyfin", null, false)

    @Test
    fun theWaveIsTheFirstYandexAccountInTheAccountOrder() {
        val accounts = listOf(jellyfin, yandex("2"), yandex("1"))
        assertEquals(StartMusicPlan.Action.PlayWave(AccountId.yandex("2")), StartMusicPlan.action(StartMusic.WAVE, accounts))
    }

    @Test
    fun withoutYandexTheWaveChoiceResumesTheLastQueue() {
        assertEquals(StartMusicPlan.Action.Resume, StartMusicPlan.action(StartMusic.WAVE, listOf(jellyfin)))
        assertEquals(StartMusicPlan.Action.Resume, StartMusicPlan.action(StartMusic.WAVE, emptyList()))
    }

    @Test
    fun offDoesNothingAndContinueResumes() {
        assertEquals(StartMusicPlan.Action.Nothing, StartMusicPlan.action(StartMusic.OFF, listOf(yandex("1"))))
        assertEquals(StartMusicPlan.Action.Resume, StartMusicPlan.action(StartMusic.CONTINUE, listOf(yandex("1"))))
    }

    @Test
    fun theWaveOptionIsOfferedOnlyWithYandexAndAStoredWaveReadsAsContinueWithout() {
        assertEquals(listOf(StartMusic.OFF, StartMusic.CONTINUE, StartMusic.WAVE), StartMusicPlan.options(hasYandex = true))
        val without = StartMusicPlan.options(hasYandex = false)
        assertEquals(listOf(StartMusic.OFF, StartMusic.CONTINUE), without)
        assertEquals(1, without.indexOf(StartMusicPlan.shown(StartMusic.WAVE, hasYandex = false)))
        assertEquals(StartMusic.WAVE, StartMusicPlan.shown(StartMusic.WAVE, hasYandex = true))
    }
}
