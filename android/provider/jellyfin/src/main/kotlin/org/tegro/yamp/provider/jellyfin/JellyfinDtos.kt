package org.tegro.yamp.provider.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The parts of Jellyfin's API (10.x / 12.x) the provider reads. Field names are Jellyfin's PascalCase; unknown
 * fields are ignored (the Json in JellyfinApiFactory), so newer servers with more fields still parse.
 */

/** `GET /System/Info/Public` — answered without signing in; [id] is the server id refs and accounts are built on. */
@Serializable
data class PublicSystemInfoDto(
    @SerialName("Id") val id: String,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("ProductName") val productName: String? = null,
    @SerialName("StartupWizardCompleted") val startupWizardCompleted: Boolean? = null
)

/** `POST /Users/AuthenticateByName` body. */
@Serializable
data class AuthenticateByNameDto(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null
)

/** `POST /Users/AuthenticateByName` (and Quick Connect) result: the token belongs to this server + user. */
@Serializable
data class AuthenticationResultDto(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String
)

/** An artist or album artist as an item names it. */
@Serializable
data class NameIdPairDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("Id") val id: String? = null
)

/** One library item: a track (`Audio`), `MusicAlbum`, `MusicArtist` or `Playlist`. */
@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<NameIdPairDto> = emptyList(),
    @SerialName("ArtistItems") val artistItems: List<NameIdPairDto> = emptyList(),
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("NormalizationGain") val normalizationGain: Double? = null
)

/** A page of items: `GET /Items` and friends. */
@Serializable
data class ItemsResultDto(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("StartIndex") val startIndex: Int = 0
)

/** `POST /QuickConnect/Initiate` and `GET /QuickConnect/Connect` — the code shown to the user and its secret. */
@Serializable
data class QuickConnectResultDto(
    @SerialName("Secret") val secret: String,
    @SerialName("Code") val code: String,
    @SerialName("Authenticated") val authenticated: Boolean = false
)

/** `POST /Users/AuthenticateWithQuickConnect` — the secret of an approved Quick Connect request. */
@Serializable
data class QuickConnectAuthDto(
    @SerialName("Secret") val secret: String
)

/** The body of the three `/Sessions/Playing*` reports; positions in Jellyfin ticks (100 ns). */
@Serializable
data class PlaybackReportDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    // No defaults below: the client's Json drops default values, and the server must always get these
    @SerialName("IsPaused") val isPaused: Boolean,
    @SerialName("CanSeek") val canSeek: Boolean,
    @SerialName("PlayMethod") val playMethod: String,
    /** "Pause" / "Unpause" / "TimeUpdate" on progress reports, as the official clients send them. */
    @SerialName("EventName") val eventName: String?,
    @SerialName("PlaySessionId") val playSessionId: String?
)

/** `POST /Playlists`: no defaults — the client's Json drops default values. */
@Serializable
data class CreatePlaylistDto(
    @SerialName("Name") val name: String,
    @SerialName("Ids") val ids: List<String>,
    @SerialName("UserId") val userId: String,
    @SerialName("MediaType") val mediaType: String
)

@Serializable
data class CreatePlaylistResultDto(@SerialName("Id") val id: String)
