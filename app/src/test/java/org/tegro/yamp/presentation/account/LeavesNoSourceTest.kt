package org.tegro.yamp.presentation.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sign-out dialogs warn only when nothing would be left to open the app with (D100). */
class LeavesNoSourceTest {
    @Test
    fun aYandexOnlyUserLeavingEverythingIsWarned() {
        assertTrue(leavesNoSource(listOf("1"), setOf("1"), otherSources = 0))
        assertTrue(leavesNoSource(listOf("1", "2"), setOf("1", "2"), otherSources = 0))
    }

    @Test
    fun anotherSourceOrAStayingYandexAccountMeansNoWarning() {
        assertFalse(leavesNoSource(listOf("1"), setOf("1"), otherSources = 1))
        assertFalse(leavesNoSource(listOf("1", "2"), setOf("1"), otherSources = 0))
    }

    @Test
    fun withoutYandexAccountsThereIsNothingToWarnAbout() {
        assertFalse(leavesNoSource(emptyList(), emptySet(), otherSources = 0))
    }
}
