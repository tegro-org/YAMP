package org.tegro.yamp.domain.queue

import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider

/**
 * Whether a track may go into a queue of [account] (docs/phase-3-providers.md D84): a queue holds tracks of its own
 * account only. A Yandex track fits any Yandex account's queue (the catalog is shared, the queue's account plays it,
 * D57); a Jellyfin track fits only an account on its own server (item ids are per server, D78). A queue without an
 * account (from before Phase 2a) is a Yandex one.
 */
fun TrackRef.fitsQueueOf(account: AccountId?): Boolean {
    val ref = MediaRef.parse(value) ?: return false
    return when (ref.providerType) {
        MediaRef.PROVIDER_YANDEX -> account == null || account.provider == Provider.YANDEX
        Provider.JELLYFIN.key -> account?.provider == Provider.JELLYFIN && ref.instance != null &&
            account.id.substringBefore('/') == ref.instance
        else -> false
    }
}

/** The service a track comes from, for its provider badge (D21); null for the built-in radio. */
val TrackRef.service: Provider?
    get() = MediaRef.parse(value)?.service

/** The service an item comes from, for its provider badge (D21); null for the built-in radio. */
val MediaRef.service: Provider?
    get() = Provider.entries.firstOrNull { it.key == providerType }
