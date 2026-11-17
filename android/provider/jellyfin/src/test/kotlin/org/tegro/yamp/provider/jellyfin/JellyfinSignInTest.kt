package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

class JellyfinSignInTest {

    private open class FakeApi : FakeJellyfinApi() {
        var info: () -> PublicSystemInfoDto = { PublicSystemInfoDto(id = "srv1", serverName = "Home Media", version = "10.10.3", productName = "Jellyfin Server") }
        var quickConnect = true
        var qcPolls = 0
        var approveAfter = 2
        var password = "right"

        override suspend fun publicSystemInfo() = info()
        override suspend fun authenticateByName(body: AuthenticateByNameDto): AuthenticationResultDto {
            if (body.password != password) throw http(401)
            return auth(body.username)
        }
        override suspend fun quickConnectEnabled() = quickConnect
        override suspend fun quickConnectInitiate() = QuickConnectResultDto(secret = "s3cret", code = "482915")
        override suspend fun quickConnectState(secret: String): QuickConnectResultDto {
            qcPolls++
            return QuickConnectResultDto(secret = secret, code = "482915", authenticated = qcPolls > approveAfter)
        }
        override suspend fun authenticateWithQuickConnect(body: QuickConnectAuthDto) = auth("alex")
        private fun auth(user: String) = AuthenticationResultDto(UserDto(id = "u1", name = user), accessToken = "tok", serverId = "srv1")
    }

    private fun signIn(api: FakeApi, allowHttp: Boolean = false) =
        JellyfinSignIn({ api }, allowHttp = { allowHttp }, pollIntervalMs = 10, quickConnectTimeoutMs = 100)

    @Test
    fun aJellyfinServerIsFoundWithItsNameVersionAndQuickConnect() = runTest {
        val found = signIn(FakeApi()).check("music.example.org/") as ServerCheck.Found
        assertEquals("https://music.example.org", found.url)
        assertEquals("srv1", found.id)
        assertEquals("Home Media", found.name)
        assertEquals("10.10.3", found.version)
        assertTrue(found.quickConnect)
    }

    @Test
    fun badAddressesNeverReachTheNetwork() = runTest {
        val api = object : FakeApi() { override suspend fun publicSystemInfo(): PublicSystemInfoDto = fail("called") as Nothing }
        val signIn = signIn(api)
        assertEquals(ServerCheck.BadAddress(ServerCheck.BadAddress.Reason.EMPTY), signIn.check("  "))
        assertEquals(ServerCheck.BadAddress(ServerCheck.BadAddress.Reason.INVALID), signIn.check("https://"))
        assertEquals(ServerCheck.BadAddress(ServerCheck.BadAddress.Reason.NOT_HTTPS), signIn.check("http://music.example.org"))
    }

    @Test
    fun plainHttpIsAllowedOnlyWhereTheBuildAllowsIt() = runTest {
        assertTrue(signIn(FakeApi(), allowHttp = true).check("http://10.0.2.2:8096") is ServerCheck.Found)
    }

    @Test
    fun nothingAnsweringIsUnreachableAndSomethingElseIsNotJellyfin() = runTest {
        val down = FakeApi().apply { info = { throw IOException("refused") } }
        assertEquals(ServerCheck.Unreachable, signIn(down).check("music.example.org"))
        val web = FakeApi().apply { info = { throw http(404) } }
        assertEquals(ServerCheck.NotJellyfin, signIn(web).check("music.example.org"))
        val other = FakeApi().apply { info = { PublicSystemInfoDto(id = "x", productName = "Emby Server") } }
        assertEquals(ServerCheck.NotJellyfin, signIn(other).check("music.example.org"))
    }

    @Test
    fun aWrongPasswordIsReportedAsSuch() = runTest {
        val api = FakeApi()
        val signIn = signIn(api)
        val server = signIn.check("music.example.org") as ServerCheck.Found
        try {
            signIn.withPassword(server, "alex", "wrong")
            fail("signed in")
        } catch (e: SignInFailed) {
            assertEquals(SignInError.WrongCredentials, e.error)
        }
        val ok = signIn.withPassword(server, " alex ", "right")
        assertEquals("srv1", ok.serverId)
        assertEquals("u1", ok.userId)
        assertEquals("alex", ok.userName)
        assertEquals("tok", ok.token)
    }

    @Test
    fun quickConnectWaitsForTheApprovalThenSignsIn() = runTest {
        val api = FakeApi()
        val signIn = signIn(api)
        val server = signIn.check("music.example.org") as ServerCheck.Found
        val code = signIn.startQuickConnect(server)
        assertEquals("482915", code.code)
        val ok = signIn.awaitQuickConnect(server, code.secret)
        assertEquals(3, api.qcPolls)
        assertEquals("tok", ok.token)
    }

    @Test
    fun anUnconfirmedQuickConnectCodeExpires() = runTest {
        val api = FakeApi().apply { approveAfter = Int.MAX_VALUE }
        val signIn = signIn(api)
        val server = signIn.check("music.example.org") as ServerCheck.Found
        try {
            signIn.awaitQuickConnect(server, "s3cret")
            fail("signed in")
        } catch (e: SignInFailed) {
            assertEquals(SignInError.QuickConnectExpired, e.error)
        }
    }
}
