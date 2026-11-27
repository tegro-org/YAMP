package org.tegro.yamp.presentation.sources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's own chooser (D93, 2026-11-22): it hands the user to the system picker when the file API can't read the
 * volume, and now also when it can't list the level being shown (a mount point that passes the access probe but
 * refuses a listing — the head unit's USB stick). "Use this folder" is off in both cases.
 */
class LocalChooserLogicTest {

    @Test
    fun `a readable volume with a listable level keeps use this folder`() {
        assertFalse(chooserNeedsSystemPicker(readable = true, levelFailed = false))
    }

    @Test
    fun `an unreadable mount point goes to the system picker`() {
        assertTrue(chooserNeedsSystemPicker(readable = false, levelFailed = false))
    }

    @Test
    fun `a mount point that can't list the level goes to the system picker`() {
        assertTrue(chooserNeedsSystemPicker(readable = true, levelFailed = true))
    }
}
