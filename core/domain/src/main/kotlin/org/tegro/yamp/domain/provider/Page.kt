package org.tegro.yamp.domain.provider

/**
 * One page of a list (docs/phase-3-providers.md §1): Jellyfin libraries run into thousands and are paged;
 * a provider that returns everything at once (Yandex) answers with a single page and no [next].
 */
data class Page<out T>(val items: List<T>, val next: Cursor? = null) {
    /** An opaque position understood only by the provider that issued it. */
    @JvmInline
    value class Cursor(val value: String)
}
