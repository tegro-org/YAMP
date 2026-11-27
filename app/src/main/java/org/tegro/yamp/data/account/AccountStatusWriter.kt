package org.tegro.yamp.data.account

import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.repository.AuthRepository

/** What an account-status answer says, apart from its wire format: [plus] is null unless the answer is clear about Plus (D101). */
internal class StatusAnswer(val uid: String, val login: String?, val displayName: String, val plus: Boolean?)

/**
 * Writes a server's account-status answer into the store (D101). The answer comes back from the network after the
 * request was made, so the account may have been switched away from, or signed out, in between: only a fresh sign-in
 * (still carrying its provisional id) is saved as the active account; every other answer is written without
 * activating anything, and dropped if the account is gone.
 */
internal object AccountStatusWriter {

    /** The uid the answer was written under, or null when it was dropped (the account is no longer stored). */
    fun apply(auth: AuthRepository, asked: UserAccount, status: StatusAnswer, avatar: String?, nowMs: Long): String? {
        if (auth.getAllAccounts().none { it.uid == asked.uid }) return null
        val uid = status.uid.takeIf { !asked.hasRealUid && it.isNotEmpty() } ?: asked.uid
        val login = status.login?.ifBlank { asked.login } ?: asked.login
        val name = status.displayName.ifBlank { asked.displayName }
        if (!asked.hasRealUid) {
            auth.saveOrUpdateAccount(
                asked.copy(
                    uid = uid, displayName = name, login = login, avatarUrl = avatar ?: asked.avatarUrl,
                    // Only a clear answer changes what is known about Plus
                    hasPlus = status.plus ?: asked.hasPlus,
                    plusCheckedAt = if (status.plus != null) nowMs else asked.plusCheckedAt
                )
            )
        } else {
            if (!auth.updateProfile(uid, login, name, avatar)) return null
            status.plus?.let { auth.updatePlus(uid, it, nowMs) }
        }
        return uid
    }
}

/** Plus is asked again when the app comes to the screen or the service starts, but at most once per [gapMs] (D101). */
internal class PlusAskThrottle(private val gapMs: Long) {
    private var askedAt: Long? = null

    fun due(nowMs: Long): Boolean = askedAt.let { it == null || nowMs - it >= gapMs }

    fun asked(nowMs: Long) {
        askedAt = nowMs
    }
}
