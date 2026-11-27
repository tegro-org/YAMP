package org.tegro.yamp.di

import android.content.Context
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.data.network.AuthInterceptor
import org.tegro.yamp.data.network.YandexClients
import org.tegro.yamp.data.network.YandexClientsImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The client for GitHub: everything that isn't Yandex must not see the Yandex token. */
@Qualifier
annotation class UpdateClient

/**
 * The networking base every provider builds its per-account clients on (C6, phase 3 step 10): the shared pool,
 * dispatcher, timeouts and logging, but no credentials and no cache — each provider adds its own interceptor,
 * scoped to its own hosts, and its own cache directory.
 */
@Qualifier
annotation class ProviderBaseClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        @ApplicationContext context: Context
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
            // Never print the OAuth token, even in debug logcat
            redactHeader("Authorization")
            redactHeader("Cookie")
        }

        val cacheSize = 20L * 1024 * 1024 // 20 MB
        val cache = Cache(File(context.cacheDir, "http_cache"), cacheSize)

        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .cache(cache)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** The shared client with its cache, pool and timeouts, minus the Yandex auth headers. */
    @Provides
    @Singleton
    @UpdateClient
    fun provideUpdateClient(client: OkHttpClient): OkHttpClient =
        client.newBuilder()
            .apply { interceptors().removeAll { it is AuthInterceptor } }
            .build()

    @Provides
    @Singleton
    @ProviderBaseClient
    fun provideProviderBaseClient(client: OkHttpClient): OkHttpClient =
        client.newBuilder()
            .apply { interceptors().removeAll { it is AuthInterceptor } }
            .cache(null)
            .build()

    /** Retrofit for the Yandex API over [client]; one per account (`YandexClients`). */
    fun yandexRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.music.yandex.net/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideYandexClients(clients: YandexClientsImpl): YandexClients = clients
}
