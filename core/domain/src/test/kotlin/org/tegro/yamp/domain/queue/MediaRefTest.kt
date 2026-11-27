package org.tegro.yamp.domain.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaRefTest {

    @Test
    fun aYandexRefHasNoInstance() {
        val ref = MediaRef.parse("yandex:track:12345:678")!!
        assertEquals("yandex", ref.providerType)
        assertNull(ref.instance)
        assertEquals("12345:678", ref.id)
    }

    @Test
    fun aRefCanNameTheServerItCameFrom() {
        val ref = MediaRef.parse("jellyfin@3f2a9c:track:abc")!!
        assertEquals("jellyfin", ref.providerType)
        assertEquals("3f2a9c", ref.instance)
        assertEquals("track", ref.kind)
        assertEquals("jellyfin@3f2a9c:track:abc", ref.value)
    }
}
