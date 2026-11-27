package org.tegro.yamp.data.queue

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.data.provider.yandex.queue.RotorFeedbackLine
import retrofit2.HttpException
import retrofit2.Response

/** The journal line per Yandex station feedback: the result, a scrubbed excerpt of a refusal, Plus when known. */
class RotorFeedbackLineTest {

    @Test
    fun aTakenFeedbackSaysOk_withPlusWhenKnown() {
        assertEquals("feedback skip → OK · Plus yes", RotorFeedbackLine.ok("skip", true))
        assertEquals("feedback trackStarted → OK", RotorFeedbackLine.ok("trackStarted", null))
    }

    @Test
    fun aRefusalGivesItsCodeAndAScrubbedExcerpt() {
        val body = """{"error":{"name":"forbidden","message":"no plus for uid=123456789","token":"y0_AgAAAAB4abcdefghijklmnopqrstuv"}}"""
        val error = HttpException(Response.error<Any>(403, body.toResponseBody()))

        val line = RotorFeedbackLine.of("trackFinished", error, false)

        assertTrue(line, line.startsWith("feedback trackFinished → HTTP 403 «"))
        assertTrue(line, line.endsWith(" · Plus no"))
        assertTrue("the reason stays", "forbidden" in line && "no plus" in line)
        assertFalse("no user id", "123456789" in line)
        assertFalse("no token", "y0_AgAAAAB4" in line)
    }

    @Test
    fun aNetworkFailureNamesItsKind() {
        assertEquals(
            "feedback skip → network SocketTimeoutException",
            RotorFeedbackLine.of("skip", java.net.SocketTimeoutException("timeout"), null)
        )
    }

    @Test
    fun scrubbingCutsLongBodiesAndOpaqueStrings() {
        assertEquals(null, RotorFeedbackLine.scrub("  "))
        assertEquals("Authorization: …", RotorFeedbackLine.scrub("Authorization: OAuth AQAAAABBBBCCCCDDDD"))
        assertEquals("sent with Bearer …", RotorFeedbackLine.scrub("sent with Bearer abc.def"))
        val long = RotorFeedbackLine.scrub("x ".repeat(200))!!
        assertTrue(long.length <= 121)
    }
}
