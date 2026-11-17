package org.tegro.yamp.domain.account

import org.tegro.yamp.domain.model.UserAccount

// The Yandex account record (an :app type, serialised in the Keystore store) mapped to the neutral account of
// :core:domain. Kept in :app so the domain module stays free of the app's storage models.

fun UserAccount.accountId(): AccountId = AccountId.yandex(uid)

fun UserAccount.toProviderAccount() = ProviderAccount(
    provider = Provider.YANDEX,
    id = accountId(),
    displayName = displayName.ifBlank { login }.ifBlank { uid },
    avatarUrl = avatarUrl,
    hasPlus = hasPlus
)
