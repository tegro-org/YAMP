package org.tegro.yamp.data.folders

import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.provider.jellyfin.JellyfinRefs
import org.tegro.yamp.provider.local.LocalRefs

/**
 * The ref of folder [folderId] (a ref's id, as pins keep it) of [account]'s tree: `jellyfin@<server>:folder:<id>` (the
 * account id starts with the server's, D78), `local:folder:<volume>/<path>` for "This device" (D92).
 */
fun folderRefOf(account: AccountId, folderId: String): MediaRef = when (account.provider) {
    Provider.LOCAL -> MediaRef(LocalRefs.PROVIDER, LocalRefs.KIND_FOLDER, folderId)
    else -> MediaRef(JellyfinRefs.PROVIDER + MediaRef.INSTANCE_SEPARATOR + account.id.substringBefore('/'), JellyfinRefs.KIND_FOLDER, folderId)
}
