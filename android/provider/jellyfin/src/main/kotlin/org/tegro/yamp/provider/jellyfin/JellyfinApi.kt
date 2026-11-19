package org.tegro.yamp.provider.jellyfin

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** The Jellyfin endpoints the provider uses; grows with the library steps (22–26). */
interface JellyfinApi {
    @GET("System/Info/Public")
    suspend fun publicSystemInfo(): PublicSystemInfoDto

    @POST("Users/AuthenticateByName")
    suspend fun authenticateByName(@Body body: AuthenticateByNameDto): AuthenticationResultDto

    /** Whether Quick Connect is switched on on this server (answered without signing in). */
    @GET("QuickConnect/Enabled")
    suspend fun quickConnectEnabled(): Boolean

    @POST("QuickConnect/Initiate")
    suspend fun quickConnectInitiate(): QuickConnectResultDto

    @GET("QuickConnect/Connect")
    suspend fun quickConnectState(@Query("Secret") secret: String): QuickConnectResultDto

    @POST("Users/AuthenticateWithQuickConnect")
    suspend fun authenticateWithQuickConnect(@Body body: QuickConnectAuthDto): AuthenticationResultDto

    @GET("Users/{userId}/Items")
    suspend fun items(
        @Path("userId") userId: String,
        @Query("IncludeItemTypes") includeItemTypes: String? = null,
        @Query("ParentId") parentId: String? = null,
        @Query("Recursive") recursive: Boolean? = null,
        @Query("SortBy") sortBy: String? = null,
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null,
        @Query("Fields") fields: String? = null,
        @Query("AlbumArtistIds") albumArtistIds: String? = null,
        @Query("ArtistIds") artistIds: String? = null,
        @Query("GenreIds") genreIds: String? = null,
        @Query("SortOrder") sortOrder: String? = null,
        @Query("Filters") filters: String? = null,
        @Query("SearchTerm") searchTerm: String? = null
    ): ItemsResultDto

    /** The user's libraries (views): music libraries, playlists… — the folder tree starts at a music library (Phase 4b). */
    @GET("UserViews")
    suspend fun userViews(@Query("userId") userId: String): ItemsResultDto

    /** One item of the user's library (an album, artist or playlist page's head). */
    @GET("Users/{userId}/Items/{itemId}")
    suspend fun item(@Path("userId") userId: String, @Path("itemId") itemId: String): BaseItemDto

    /** Artists that have albums of their own — the library's "Artists" (not every guest on a track). */
    @GET("Artists/AlbumArtists")
    suspend fun albumArtists(
        @Query("UserId") userId: String,
        @Query("SearchTerm") searchTerm: String? = null,
        @Query("SortBy") sortBy: String? = null,
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null,
        @Query("Fields") fields: String? = null
    ): ItemsResultDto

    @GET("MusicGenres")
    suspend fun musicGenres(
        @Query("UserId") userId: String,
        @Query("SortBy") sortBy: String? = null,
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null
    ): ItemsResultDto

    /** A playlist's tracks in playlist order. */
    @GET("Playlists/{playlistId}/Items")
    suspend fun playlistItems(
        @Path("playlistId") playlistId: String,
        @Query("UserId") userId: String,
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null,
        @Query("Fields") fields: String? = null
    ): ItemsResultDto

    /** A new playlist of the user's, with its first tracks. */
    @POST("Playlists")
    suspend fun createPlaylist(@Body body: CreatePlaylistDto): CreatePlaylistResultDto

    /** Appends items to a playlist (comma-separated ids). */
    @POST("Playlists/{playlistId}/Items")
    suspend fun addToPlaylist(
        @Path("playlistId") playlistId: String,
        @Query("ids") ids: String,
        @Query("userId") userId: String
    )

    /** Play reporting (step 26): the server's "now playing", play count and resume position. */
    @POST("Sessions/Playing")
    suspend fun reportPlaying(@Body body: PlaybackReportDto)

    @POST("Sessions/Playing/Progress")
    suspend fun reportProgress(@Body body: PlaybackReportDto)

    @POST("Sessions/Playing/Stopped")
    suspend fun reportStopped(@Body body: PlaybackReportDto)
}

/**
 * One Retrofit client per server + user (D78), built on a shared base [OkHttpClient] (C6): the base brings the
 * app's timeouts and connection pool, this adds only the server's own auth header.
 */
object JellyfinApiFactory {
    val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** [serverUrl] as the user typed it, normalised to end with `/` so relative endpoint paths resolve under it. */
    fun baseUrl(serverUrl: String): HttpUrl = serverUrl.trim().trimEnd('/').plus("/").toHttpUrl()

    fun create(base: OkHttpClient, serverUrl: String, info: JellyfinClientInfo, token: () -> String?): JellyfinApi {
        val url = baseUrl(serverUrl)
        val client = base.newBuilder().addInterceptor(JellyfinAuthInterceptor(url, info, token)).build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(JellyfinApi::class.java)
    }
}
