package org.tegro.yamp.widget

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.MainActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetAskTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `an intent adds a start and a tab, and a later intent keeps what is still unanswered`() {
        val start = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_MUSIC, true)
        val tab = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TAB, "radio")
        val ask = WidgetAsk().withIntent(start).withIntent(tab)
        assertEquals(WidgetAsk(start = true, tab = "radio"), ask)
        assertEquals(ask, ask.withIntent(Intent(context, MainActivity::class.java)))
    }

    @Test
    fun `an unanswered ask survives the activity being recreated, an answered one does not`() {
        val pending = WidgetAsk(start = true, tab = "library")
        val saved = Bundle().also(pending::saveTo)
        assertEquals(pending, WidgetAsk.restoreFrom(saved))

        val answered = pending.copy(start = false, tab = null)
        val savedAnswered = Bundle().also(answered::saveTo)
        assertEquals(WidgetAsk(), WidgetAsk.restoreFrom(savedAnswered))
        assertNull(WidgetAsk.restoreFrom(null).tab)
        assertTrue(!WidgetAsk.restoreFrom(null).start)
    }
}
