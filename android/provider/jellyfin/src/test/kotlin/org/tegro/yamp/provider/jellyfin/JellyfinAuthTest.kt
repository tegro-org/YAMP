package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinAuthTest {
    private val info = JellyfinClientInfo(client = "YAMP", device = "Head unit", deviceId = "dev-1", version = "0.5.0")

    @Test
    fun theHeaderNamesTheClientAndAddsTheTokenOnlyWhenSignedIn() {
        assertEquals(
            "MediaBrowser Client=\"YAMP\", Device=\"Head unit\", DeviceId=\"dev-1\", Version=\"0.5.0\"",
            JellyfinAuthHeader.value(info, null)
        )
        assertEquals(
            "MediaBrowser Client=\"YAMP\", Device=\"Head unit\", DeviceId=\"dev-1\", Version=\"0.5.0\", Token=\"t0k\"",
            JellyfinAuthHeader.value(info, "t0k")
        )
    }

    @Test
    fun quotesInsideAValueCannotBreakTheHeader() {
        val header = JellyfinAuthHeader.value(info.copy(device = "My \"car\"\\"), null)
        assertEquals(true, header.contains("Device=\"My car\""))
    }

    @Test
    fun credentialsGoOnlyToTheServersOwnHostAndPort() {
        val seen = mutableMapOf<String, String?>()
        val recorder = Interceptor { chain ->
            seen[chain.request().url.toString()] = chain.request().header(JellyfinAuthHeader.NAME)
            okResponse(chain.request(), "{}")
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(JellyfinAuthInterceptor("http://10.0.2.2:8096/".toHttpUrl(), info) { "secret" })
            .addInterceptor(recorder)
            .build()
        listOf("http://10.0.2.2:8096/System/Info/Public", "http://10.0.2.2:9999/x", "https://images.example.org/a.jpg")
            .forEach { client.newCall(Request.Builder().url(it).build()).execute().close() }

        assertEquals(true, seen["http://10.0.2.2:8096/System/Info/Public"]!!.endsWith("Token=\"secret\""))
        assertNull(seen["http://10.0.2.2:9999/x"])
        assertNull(seen["https://images.example.org/a.jpg"])
    }

    @Test
    fun theApiParsesTheServersPublicInfoThroughRetrofit() = runTest {
        val fixture = resource("system-info-public.json")
        val base = OkHttpClient.Builder()
            .addInterceptor { chain -> okResponse(chain.request(), fixture) }
            .build()
        val api = JellyfinApiFactory.create(base, "http://10.0.2.2:8096", info) { null }

        val result = api.publicSystemInfo()

        assertEquals("0123456789abcdef0123456789abcdef", result.id)
        assertEquals(true, result.startupWizardCompleted)
    }

    @Test
    fun theServerUrlIsNormalisedSoPathsResolveUnderIt() {
        assertEquals("http://host:8096/jellyfin/", JellyfinApiFactory.baseUrl(" http://host:8096/jellyfin// ").toString())
    }

    private fun okResponse(request: Request, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}

internal fun resource(name: String): String =
    checkNotNull(JellyfinAuthTest::class.java.classLoader?.getResource("jellyfin/$name")) { "fixture $name missing" }.readText()
