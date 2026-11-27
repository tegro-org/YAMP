package org.tegro.yamp.data.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.StateFlow

/**
 * The engine's players as the media session needs them (docs/phase-3-providers.md step 17): the framework types
 * stay out of the domain's [org.tegro.yamp.domain.repository.PlayerRepository]; only the service reads them.
 */
interface SessionPlayers {
    /** The player that sounds now; changes on every handover between the two players. */
    val activePlayerState: StateFlow<ExoPlayer>

    fun getPlayer(): Player
}
