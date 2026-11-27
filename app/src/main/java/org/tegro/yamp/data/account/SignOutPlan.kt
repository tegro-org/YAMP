package org.tegro.yamp.data.account

import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.model.UserAccount

/** The decisions of a sign-out (D62), apart from the work, so they can be checked on their own. */
internal object SignOutPlan {
    /**
     * Where playback goes when the active queue belonged to a signing-out account: the default account if it stays,
     * else the most recently used one that stays; null when none stays.
     */
    fun fallback(staying: List<UserAccount>, defaultUid: String?): AccountId? =
        (staying.firstOrNull { it.uid == defaultUid } ?: staying.maxByOrNull { it.lastActiveTimestamp })?.accountId()

    /**
     * [fallback] for every provider: a staying Yandex account first (the default one, else the most recently used),
     * else the first staying Jellyfin account — with Jellyfin only, its default queue takes over.
     */
    fun fallbackAny(staying: List<UserAccount>, defaultUid: String?, jellyfinStaying: List<AccountId>): AccountId? =
        fallback(staying, defaultUid) ?: jellyfinStaying.firstOrNull()

    /** The leaving account's liked tracks to delete from the device: those no staying account likes too. */
    fun downloadsToRemove(leavingLikes: List<String>, stayingLikes: Set<String>): List<String> =
        leavingLikes.filterNot { it in stayingLikes }.distinct()
}
