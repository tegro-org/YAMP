package org.tegro.yamp.data.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.tegro.yamp.data.network.NetworkMonitor
import org.tegro.yamp.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where [SoundMode] finds an account's own quality: "compatible" chosen per account for unmetered and for metered
 * networks (Settings → Accounts → the account), and which of the two applies now. Answers from memory, so a
 * stream link can be keyed by it on any thread.
 */
interface AccountQualitySource {
    /** [account]'s choice for [metered] networks; null when it has none (the global choice applies). */
    fun chosen(account: String, metered: Boolean): Boolean?

    /** Whether the network now in use is metered. */
    fun isMetered(): Boolean

    /** No per-account choices: every account follows the global one. */
    object None : AccountQualitySource {
        override fun chosen(account: String, metered: Boolean): Boolean? = null
        override fun isMetered(): Boolean = false
    }
}

/** The per-account choices from the settings, and the network's metered flag, kept warm. */
@Singleton
class AccountQualityStore @Inject constructor(
    private val settings: SettingsRepository,
    network: NetworkMonitor
) : AccountQualitySource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Every stored choice, keyed by [SettingsRepository.accountQualityKey]. */
    val choices: StateFlow<Map<String, Boolean>> = settings.accountQualities.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The network in use is metered: the "metered" choices apply (a change applies from the next track). */
    val metered: StateFlow<Boolean> = network.meteredFlow.stateIn(scope, SharingStarted.Eagerly, network.isMetered())

    override fun chosen(account: String, metered: Boolean): Boolean? = choices.value[SettingsRepository.accountQualityKey(account, metered)]

    override fun isMetered(): Boolean = metered.value

    suspend fun set(account: String, metered: Boolean, compatible: Boolean) = settings.setAccountQuality(account, metered, compatible)
}
