package org.tegro.yamp.domain.provider

/**
 * What a provider call returns (docs/phase-3-providers.md D73): a value or a [ProviderError]. Repositories may
 * throw internally; the provider maps once at its boundary, so no exception crosses it. UI text comes from
 * resources, keyed by the error type.
 */
sealed interface Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>
    data class Err(val error: ProviderError) : Outcome<Nothing>
}

/** Why a provider call failed, in the terms the UI and the player act on. */
sealed interface ProviderError {
    data object Network : ProviderError
    data object Timeout : ProviderError
    data object Unauthorized : ProviderError
    data object NotFound : ProviderError
    data class Unavailable(val reason: String) : ProviderError
    data object RateLimited : ProviderError
    data class Server(val code: Int) : ProviderError
    data class Unknown(val message: String?) : ProviderError
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Ok)?.value
