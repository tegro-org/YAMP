package org.tegro.yamp.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class UserAccount(
    val uid: String,
    val login: String = "",
    val displayName: String = "",
    val token: String,
    val avatarUrl: String? = null,
    val lastActiveTimestamp: Long = System.currentTimeMillis(),
    val hasPlus: Boolean = false,
    /** When the server last said whether the account has Plus; 0 — never (an old account, no answer yet). */
    val plusCheckedAt: Long = 0L
) {
    /**
     * The server has said there is no Plus. Never true without a clear answer — no network or an answer without the
     * "plus" part keeps what was known, so a subscriber is not locked out by a failure.
     */
    val plusMissing: Boolean
        get() = plusCheckedAt > 0L && !hasPlus

    /**
     * The Plus gate (D101): this Yandex account's music is refused — the server has said there is no Plus and the
     * developer option ([allowWithoutPlus]) is off.
     */
    fun blocked(allowWithoutPlus: Boolean): Boolean = plusMissing && !allowWithoutPlus

    /**
     * False until the server has told who this is: right after sign-in the account carries a provisional
     * "temp_..." id, and an account from the old single-token storage carries "me". The server does not
     * accept either in the address of a playlist.
     */
    val hasRealUid: Boolean
        get() = uid.isNotBlank() && uid != "me" && !uid.startsWith("temp_")

    /**
     * The key per-account data was stored under before Phase 2a (login → display name → uid, sanitized). It can
     * collide and changes when a provisional account gets its uid, so data is keyed by `accountId()` now
     * (`yandex:<uid>`, D59); this stays only for `AccountKeyMigration` to find the old rows.
     */
    val legacyFolderName: String
        get() {
            val baseName = login.ifBlank { displayName }.ifBlank { uid }
            val sanitized = baseName.replace(Regex("[^a-zA-Z0-9а-яА-Я_-]"), "_").trim('_')
            return sanitized.ifBlank { "user_$uid" }.take(40)
        }
}
