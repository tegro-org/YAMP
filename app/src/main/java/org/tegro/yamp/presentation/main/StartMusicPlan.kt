package org.tegro.yamp.presentation.main

import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.ProviderAccount
import org.tegro.yamp.domain.model.StartMusic

/**
 * What "Music on launch" does (D35, revised): "My Wave" starts the wave of the first signed-in Yandex account in the
 * account order (Settings → Accounts; the user reorders to choose). With no Yandex account there is no wave: the last
 * queue resumes, and the option is not offered. The stored choice is kept for when a Yandex account is added again.
 */
internal object StartMusicPlan {
    sealed interface Action {
        data object Nothing : Action
        data object Resume : Action
        data class PlayWave(val account: AccountId) : Action
    }

    fun firstYandex(accounts: List<ProviderAccount>): AccountId? = accounts.firstOrNull { it.provider == Provider.YANDEX }?.id

    fun action(mode: StartMusic, accounts: List<ProviderAccount>): Action = when (mode) {
        StartMusic.OFF -> Action.Nothing
        StartMusic.CONTINUE -> Action.Resume
        StartMusic.WAVE -> firstYandex(accounts)?.let(Action::PlayWave) ?: Action.Resume
    }

    /** The options the setting offers, in the order shown. */
    fun options(hasYandex: Boolean): List<StartMusic> =
        if (hasYandex) StartMusic.entries.toList() else StartMusic.entries - StartMusic.WAVE

    /** The option that shows as chosen: a stored "My Wave" that cannot start one resumes, so it reads "Continue". */
    fun shown(mode: StartMusic, hasYandex: Boolean): StartMusic =
        if (mode == StartMusic.WAVE && !hasYandex) StartMusic.CONTINUE else mode
}
