package org.tegro.yamp

import okhttp3.OkHttpClient
import org.tegro.yamp.data.network.YandexClients
import org.tegro.yamp.data.network.YandexMusicApi
import org.tegro.yamp.domain.account.AccountId

/** Every account's calls go to one [api] — the test's fake server answers them all. */
class OneApiClients(
    private val api: YandexMusicApi,
    private val http: OkHttpClient = OkHttpClient()
) : YandexClients {
    override fun api(account: AccountId?): YandexMusicApi = api
    override fun current(): YandexMusicApi = api
    override fun http(account: AccountId?): OkHttpClient = http
    override fun evictCaches() = Unit
}
