package org.tegro.yamp.provider.jellyfin

/** A [JellyfinApi] answering nothing; tests override what they use. */
internal open class FakeJellyfinApi : JellyfinApi {
    override suspend fun publicSystemInfo(): PublicSystemInfoDto = unused()
    override suspend fun authenticateByName(body: AuthenticateByNameDto): AuthenticationResultDto = unused()
    override suspend fun quickConnectEnabled(): Boolean = unused()
    override suspend fun quickConnectInitiate(): QuickConnectResultDto = unused()
    override suspend fun quickConnectState(secret: String): QuickConnectResultDto = unused()
    override suspend fun authenticateWithQuickConnect(body: QuickConnectAuthDto): AuthenticationResultDto = unused()
    override suspend fun items(
        userId: String, includeItemTypes: String?, parentId: String?, recursive: Boolean?, sortBy: String?,
        startIndex: Int?, limit: Int?, fields: String?, albumArtistIds: String?, artistIds: String?, genreIds: String?,
        sortOrder: String?, filters: String?, searchTerm: String?
    ): ItemsResultDto = ItemsResultDto()
    override suspend fun item(userId: String, itemId: String): BaseItemDto = unused()
    override suspend fun albumArtists(userId: String, searchTerm: String?, sortBy: String?, startIndex: Int?, limit: Int?, fields: String?): ItemsResultDto = ItemsResultDto()
    override suspend fun musicGenres(userId: String, sortBy: String?, startIndex: Int?, limit: Int?): ItemsResultDto = ItemsResultDto()
    override suspend fun playlistItems(playlistId: String, userId: String, startIndex: Int?, limit: Int?, fields: String?): ItemsResultDto =
        ItemsResultDto()

    override suspend fun reportPlaying(body: PlaybackReportDto): Unit = unused()
    override suspend fun reportProgress(body: PlaybackReportDto): Unit = unused()
    override suspend fun reportStopped(body: PlaybackReportDto): Unit = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by this test")
}
