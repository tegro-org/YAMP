package org.tegro.yamp.domain.queue

/**
 * One reference form for every item of every provider (ARCHITECTURE C1, D27): `<provider>:<kind>:<id>`,
 * e.g. `yandex:track:12345`, `yandex:playlist:1234567:1003`, `jellyfin:folder:3f2a…`. Parsing splits at the
 * first two colons; the rest is the provider's own id, opaque, and may itself contain colons.
 *
 * The provider part may name an instance (docs/phase-3-providers.md D78): `jellyfin@<serverId>:track:<itemId>` —
 * item ids are unique per server, so the ref says which one. [providerType] is the part before `@`, [instance]
 * the part after it (null for Yandex, whose refs stay `yandex:track:<id>`).
 */
data class MediaRef(val provider: String, val kind: String, val id: String) {
    val value: String get() = "$provider:$kind:$id"

    /** The provider without its instance: `jellyfin` for `jellyfin@abc`, `yandex` for `yandex`. */
    val providerType: String get() = provider.substringBefore(INSTANCE_SEPARATOR)

    /** The instance after `@`, or null when the provider part names none. */
    val instance: String? get() = provider.substringAfter(INSTANCE_SEPARATOR, "").ifEmpty { null }

    override fun toString(): String = value

    companion object {
        const val KIND_TRACK = "track"
        const val PROVIDER_YANDEX = "yandex"

        /** The FM stations: a built-in source, not a streaming service's items (D76). */
        const val PROVIDER_RADIO = "radio"
        const val INSTANCE_SEPARATOR = '@'

        /** Null when the text is not of the form `provider:kind:id` with all three parts non-empty. */
        fun parse(value: String): MediaRef? {
            val first = value.indexOf(':').takeIf { it > 0 } ?: return null
            val second = value.indexOf(':', first + 1).takeIf { it > first + 1 } ?: return null
            val id = value.substring(second + 1)
            if (id.isEmpty()) return null
            return MediaRef(value.substring(0, first), value.substring(first + 1, second), id)
        }
    }
}

/** A reference of kind `track`: the only track identity in new code (queue entries, player, downloads). */
@JvmInline
value class TrackRef(val value: String) {
    val ref: MediaRef get() = MediaRef.parse(value) ?: error("not a track ref: $value")
    val provider: String get() = ref.provider
    val id: String get() = ref.id

    override fun toString(): String = value

    companion object {
        fun of(provider: String, id: String): TrackRef = TrackRef(MediaRef(provider, MediaRef.KIND_TRACK, id).value)

        /** Phase 1 bridge: a Yandex track id (`12345` or `12345:678`) as a ref. */
        fun yandex(id: String): TrackRef = of(MediaRef.PROVIDER_YANDEX, id)

        /** Null unless the text is a well-formed reference of kind `track`. */
        fun parse(value: String): TrackRef? = MediaRef.parse(value)?.takeIf { it.kind == MediaRef.KIND_TRACK }?.let { TrackRef(it.value) }
    }
}
