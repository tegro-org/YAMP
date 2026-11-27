package org.tegro.yamp.domain.queue

import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider

/**
 * Whether a track may go into a queue of [account] (docs/phase-3-providers.md D84): a queue holds tracks of its own
 * account only. A Yandex track fits any Yandex account's queue (the catalog is shared, the queue's account plays it,
 * D57); a Jellyfin track fits only an account on its own server (item ids are per server, D78); a local file fits only
 * the "This device" account (D92). A queue without an account (from before Phase 2a) is a Yandex one.
 */
fun TrackRef.fitsQueueOf(account: AccountId?): Boolean = MediaRef.parse(value)?.fitsQueueOf(account) ?: false

/** The same rule for an album or an artist: whether its tracks may go into a queue of [account] (D84). */
fun MediaRef.fitsQueueOf(account: AccountId?): Boolean {
    val ref = this
    return when (ref.providerType) {
        MediaRef.PROVIDER_YANDEX -> account == null || account.provider == Provider.YANDEX
        Provider.JELLYFIN.key -> account?.provider == Provider.JELLYFIN && ref.instance != null &&
            account.id.substringBefore('/') == ref.instance
        // The device's own files: one pseudo-account "This device" (docs/phase-4d-local-files.md §8, D92)
        Provider.LOCAL.key -> account?.provider == Provider.LOCAL
        else -> false
    }
}

/** The service a track comes from, for its provider badge (D21); null for the built-in radio. */
val TrackRef.service: Provider?
    get() = MediaRef.parse(value)?.service

/** The service an item comes from, for its provider badge (D21); null for the built-in radio. */
val MediaRef.service: Provider?
    get() = Provider.entries.firstOrNull { it.key == providerType }

/** Whether [this] is Yandex: likes, dislikes, My Wave and radio are Yandex's only (Jellyfin and local files have none yet). */
val Provider?.isYandex: Boolean get() = this == Provider.YANDEX
