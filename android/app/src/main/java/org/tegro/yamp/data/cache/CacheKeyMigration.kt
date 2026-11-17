package org.tegro.yamp.data.cache

import org.tegro.yamp.data.provider.yandex.cache.PlaylistCacheManager

/**
 * A one-time rewrite of the account keys the cache rows are stored under, run before the cache is first read
 * (`PlaylistCacheManager`). Phase 2a's is `AccountKeyMigration`: folder names → `yandex:<uid>`.
 */
fun interface CacheKeyMigration {
    suspend fun ensure()

    /** The key an old session snapshot's account folder stands for now (the legacy session import). */
    fun keyForLegacyFolder(folder: String): String = folder

    /** A provisional account (`temp_…`, `me`) has got its real [uid]: what was keyed by the provisional id follows. */
    suspend fun adoptRealUid(provisional: org.tegro.yamp.domain.model.UserAccount, uid: String) = Unit

    companion object {
        val None = CacheKeyMigration { }
    }
}
