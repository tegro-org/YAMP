package org.tegro.yamp.data.player.fakes

import org.tegro.yamp.data.network.YandexClients
import org.tegro.yamp.data.network.YandexMusicApi
import org.tegro.yamp.domain.account.AccountId

/** Every account's calls go to one [api] — a test server behind it answers them all. */
class FixedYandexClients(
    private val api: YandexMusicApi,
    private val http: okhttp3.OkHttpClient = okhttp3.OkHttpClient()
) : YandexClients {
    override fun api(account: AccountId?): YandexMusicApi = api
    override fun current(): YandexMusicApi = api
    override fun http(account: AccountId?): okhttp3.OkHttpClient = http
    override fun evictCaches() = Unit
}
