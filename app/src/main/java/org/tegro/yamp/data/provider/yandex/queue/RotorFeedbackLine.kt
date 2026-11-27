package org.tegro.yamp.data.provider.yandex.queue

import retrofit2.HttpException

/**
 * The journal line for one Yandex station feedback send (`ROTOR feedback <type> → <result>`): whether the station
 * took it, the HTTP code and a short, scrubbed excerpt of a refusal's body, and whether the account has Plus when the
 * app knows. Nothing that identifies the user: tokens, ids and long numbers are cut out of the body. Pure.
 */
object RotorFeedbackLine {
    private const val EXCERPT_MAX = 120

    fun ok(type: String, plus: Boolean?): String = "feedback $type → OK" + plusOf(plus)

    fun refused(type: String, code: Int, body: String?, plus: Boolean?): String =
        "feedback $type → HTTP $code" + (scrub(body)?.let { " «$it»" } ?: "") + plusOf(plus)

    fun failed(type: String, error: Throwable, plus: Boolean?): String =
        "feedback $type → network ${error.javaClass.simpleName}" + plusOf(plus)

    /** The line for [error] thrown by the send: a refusal (with its body) or a network failure. */
    fun of(type: String, error: Throwable, plus: Boolean?): String = when (error) {
        is HttpException -> refused(type, error.code(), runCatching { error.response()?.errorBody()?.string() }.getOrNull(), plus)
        else -> failed(type, error, plus)
    }

    private fun plusOf(plus: Boolean?) = when (plus) {
        true -> " · Plus yes"
        false -> " · Plus no"
        null -> ""
    }

    /** A refusal body without anything personal, collapsed and cut to [EXCERPT_MAX] characters; null when empty. */
    fun scrub(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val clean = body
            // "token": "…", uid=…, Authorization: OAuth … — the value goes
            .replace(Regex("""(?i)("?(?:[a-z_]*token|oauth|authorization|uid|user_?id|login|session[a-z_]*|email)"?\s*[:=]\s*)("[^"]*"|(?i:OAuth|Bearer)\s+\S+|[^\s,;&}]+)"""), "$1…")
            .replace(Regex("""(?i)\b(OAuth|Bearer)\s+\S+"""), "$1 …")
            // Long digit runs (user and track ids) and long opaque strings (hashes, tokens)
            .replace(Regex("""\d{5,}"""), "#")
            .replace(Regex("""[A-Za-z0-9_\-]{24,}"""), "…")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (clean.isEmpty()) return null
        return if (clean.length <= EXCERPT_MAX) clean else clean.take(EXCERPT_MAX) + "…"
    }
}
