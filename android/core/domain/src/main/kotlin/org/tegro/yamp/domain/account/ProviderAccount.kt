package org.tegro.yamp.domain.account

/** The music services an account can belong to (docs/multi-source-player.md C5). */
enum class Provider(val key: String) { YANDEX("yandex"), JELLYFIN("jellyfin") }

/**
 * Who a request is for: `"<provider>:<id>"`, e.g. `"yandex:12345"` (docs/phase-2a-accounts.md §2). The id is the
 * service's own user id — never a login or display name, which can change or collide.
 */
@JvmInline
value class AccountId(val value: String) {
    val provider: Provider? get() = Provider.entries.firstOrNull { value.startsWith(it.key + SEPARATOR) }
    val id: String get() = value.substringAfter(SEPARATOR)

    override fun toString() = value

    companion object {
        private const val SEPARATOR = ":"
        fun yandex(uid: String) = AccountId(Provider.YANDEX.key + SEPARATOR + uid)

        /**
         * A Jellyfin account is one server plus one user (docs/phase-3-providers.md D78):
         * `jellyfin:<serverId>/<userId>` — the same user name on two servers is two accounts.
         */
        fun jellyfin(serverId: String, userId: String) = AccountId(Provider.JELLYFIN.key + SEPARATOR + serverId + "/" + userId)
    }
}

/** A signed-in account as the rest of the app sees it: no token, that stays behind `AuthRepository`. */
data class ProviderAccount(
    val provider: Provider,
    val id: AccountId,
    val displayName: String,
    val avatarUrl: String?,
    val hasPlus: Boolean
)
