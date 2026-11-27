package org.tegro.yamp.presentation.common

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * "Get a subscription" (D101): Yandex's Plus page in the browser, on the head unit too. YAMP sells nothing and shows no
 * prices. Coming back to the app asks Plus again at once ([takeReturned], used by `MainActivity`).
 */
object PlusPage {
    const val URL = "https://plus.yandex.ru/"

    @Volatile
    private var opened = false

    /** Opens the page; false when there is no browser to open it. */
    fun open(context: Context): Boolean {
        val shown = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (shown) opened = true
        return shown
    }

    /** True once after the page was opened: the user is back from it. */
    fun takeReturned(): Boolean = opened.also { opened = false }
}
