package org.tegro.yamp.widget

import android.content.Intent
import android.os.Bundle
import org.tegro.yamp.MainActivity

/**
 * What a home screen widget has asked of the app and MainScreen has not answered yet: start the music, open a tab.
 * Kept across the activity's recreation (only what is still unanswered, so nothing is replayed).
 */
data class WidgetAsk(val start: Boolean = false, val tab: String? = null) {

    /** This ask with what [intent] adds to it. */
    fun withIntent(intent: Intent): WidgetAsk = WidgetAsk(
        start = start || intent.getBooleanExtra(MainActivity.EXTRA_START_MUSIC, false),
        tab = intent.getStringExtra(MainActivity.EXTRA_OPEN_TAB) ?: tab
    )

    fun saveTo(out: Bundle) {
        if (start) out.putBoolean(KEY_START, true)
        tab?.let { out.putString(KEY_TAB, it) }
    }

    companion object {
        private const val KEY_START = "widget_ask_start"
        private const val KEY_TAB = "widget_ask_tab"

        fun restoreFrom(saved: Bundle?): WidgetAsk =
            if (saved == null) WidgetAsk() else WidgetAsk(saved.getBoolean(KEY_START, false), saved.getString(KEY_TAB))
    }
}
