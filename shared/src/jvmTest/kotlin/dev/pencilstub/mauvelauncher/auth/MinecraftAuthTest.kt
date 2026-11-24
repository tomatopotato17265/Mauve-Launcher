package dev.pencilstub.mauvelauncher.auth

import dev.pencilstub.mauvelauncher.auth.MinecraftAuth.Companion.DEVICE_AUTHENTICATE
import dev.pencilstub.mauvelauncher.auth.MinecraftAuth.Companion.MINECRAFT_LOGIN
import dev.pencilstub.mauvelauncher.auth.MinecraftAuth.Companion.MINECRAFT_PROFILE
import dev.pencilstub.mauvelauncher.auth.MinecraftAuth.Companion.MSA_TOKEN
import dev.pencilstub.mauvelauncher.auth.MinecraftAuth.Companion.SISU_AUTHENTICATE
import dev.pencilstub.mauvelauncher.auth.MinecraftAuth.Companion.SISU_AUTHORIZE
import dev.pencilstub.mauvelauncher.auth.MinecraftAuth.Companion.XSTS_AUTHORIZE
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MinecraftAuthTest {
    @Test
    fun signsInThroughTheWholeChain() = runTest {
        val xbox = FakeServices()
        val auth = MinecraftAuth(xbox)

        val flow = auth.beginLogin()
        assertTrue(flow.authorizationUrl.startsWith("https://login.live.com/oauth20_authorize.srf?"))
        // microsoft redirects back with the state we gave sisu
        val redirect = flow.resultOf("https://login.live.com/oauth20_desktop.srf?code=the-code&state=${xbox.state}")
        assertIs<MicrosoftLoginResult.Success>(redirect)

        val account = auth.finishLogin(flow, redirect.code)

        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), account.id)
        assertEquals("Notch", account.name)
        assertEquals("minecraft-token", account.accessToken)
        assertEquals("msa-refresh", account.microsoftRefreshToken)
        assertEquals("http://textures.minecraft.net/texture/new", account.skinUrl)
        assertTrue(abs(Duration.between(Instant.now().plusSeconds(86_400), account.accessTokenExpiresAt).seconds) < 60)

        assertEquals(
            listOf(DEVICE_AUTHENTICATE, SISU_AUTHENTICATE, MSA_TOKEN, SISU_AUTHORIZE, XSTS_AUTHORIZE, MINECRAFT_LOGIN, MINECRAFT_PROFILE),
            xbox.calls.map { it.url },
            "the device token is reused rather than fetched again",
        )
        val form = xbox.form(xbox.call(MSA_TOKEN))
        assertEquals("the-code", form["code"])
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("00000000402b5328", form["client_id"])
        assertEquals(form["code_verifier"]!!.let(Pkce::challenge), xbox.codeChallenge)

        val authorize = xbox.json(xbox.call(SISU_AUTHORIZE))
        assertEquals("t=msa-access", authorize.string("AccessToken"))
        assertEquals("sisu-session", authorize.string("SessionId"))
        assertEquals("device-token", authorize.string("DeviceToken"))

        val xsts = xbox.json(xbox.call(XSTS_AUTHORIZE))["Properties"]!!.jsonObject
        assertEquals("user-token", xsts["UserTokens"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("title-token", xsts.string("TitleToken"))

        assertEquals("XBL3.0 x=user-hash;xsts-token", xbox.json(xbox.call(MINECRAFT_LOGIN)).string("xtoken"))
        assertEquals("Bearer minecraft-token", xbox.call(MINECRAFT_PROFILE).headers["Authorization"])
    }

    @Test
    fun everyXboxRequestIsSignedWithTheDeviceKey() = runTest {
        val xbox = FakeServices()
        val auth = MinecraftAuth(xbox)
        auth.finishLogin(auth.beginLogin(), "the-code")

        val signed = listOf(DEVICE_AUTHENTICATE, SISU_AUTHENTICATE, SISU_AUTHORIZE, XSTS_AUTHORIZE).map(xbox::call)
        val key = xbox.proofKey()
        for (call in signed) {
            assertTrue(verifySignature(key, call), "signature of ${call.url}")
            val contractVersion = call.headers["x-xbl-contract-version"]
            assertEquals(if (call.url == SISU_AUTHORIZE) null else "1", contractVersion, call.url)
        }
        // the proof key sisu gets is the same one the device registered with
        assertEquals(xbox.json(xbox.call(DEVICE_AUTHENTICATE))["Properties"]!!.jsonObject["ProofKey"], xbox.json(xbox.call(SISU_AUTHORIZE))["ProofKey"])
    }

    @Test
    fun signsWithXboxsClock() = runTest {
        val serverTime = Instant.now().minus(Duration.ofHours(3))
        val xbox = FakeServices(serverTime = serverTime)
        MinecraftAuth(xbox).beginLogin()

        val signedAt = signatureTime(xbox.call(SISU_AUTHENTICATE))
        assertTrue(abs(Duration.between(serverTime, signedAt).seconds) < 60, "signed at $signedAt, server time $serverTime")
    }

    @Test
    fun explainsXboxAccountProblems() = runTest {
        val xbox = FakeServices(failing = mapOf(XSTS_AUTHORIZE to HttpResult(401, emptyMap(), """{"Identity":"0","XErr":2148916233,"Message":""}""")))
        val auth = MinecraftAuth(xbox)

        val error = assertFailsWith<MinecraftAuthException> { auth.finishLogin(auth.beginLogin(), "the-code") }
        assertEquals(MinecraftAuthStep.XstsAuthorize, error.step)
        assertTrue(error.message!!.contains("doesn't have an Xbox profile"), error.message)
    }

    @Test
    fun explainsMissingMinecraft() = runTest {
        val xbox = FakeServices(failing = mapOf(MINECRAFT_PROFILE to HttpResult(404, emptyMap(), """{"error":"NOT_FOUND"}""")))
        val auth = MinecraftAuth(xbox)

        val error = assertFailsWith<MinecraftAuthException> { auth.finishLogin(auth.beginLogin(), "the-code") }
        assertEquals(MinecraftAuthStep.MinecraftProfile, error.step)
        assertTrue(error.message!!.contains("doesn't own Minecraft"), error.message)
    }

    @Test
    fun explainsRejectedCodes() = runTest {
        val body = """{"error":"invalid_grant","error_description":"The provided value for the 'code' parameter is not valid."}"""
        val xbox = FakeServices(failing = mapOf(MSA_TOKEN to HttpResult(400, emptyMap(), body)))
        val auth = MinecraftAuth(xbox)

        val error = assertFailsWith<MinecraftAuthException> { auth.finishLogin(auth.beginLogin(), "the-code") }
        assertEquals("Microsoft didn't accept the sign-in: The provided value for the 'code' parameter is not valid.", error.message)
    }

    @Test
    fun explainsNetworkFailures() = runTest {
        val auth = MinecraftAuth { throw IOException("offline") }

        val error = assertFailsWith<MinecraftAuthException> { auth.beginLogin() }
        assertEquals(MinecraftAuthStep.DeviceToken, error.step)
        assertTrue(error.message!!.startsWith("Couldn't connect to Xbox Live"), error.message)
    }

    @Test
    fun windowsFileTimeCountsFrom1601() {
        assertEquals(116_444_736_000_000_000L, XboxDeviceKey.windowsFileTime(Instant.EPOCH))
    }

    private fun signatureTime(call: HttpCall): Instant {
        val header = ByteBuffer.wrap(Base64.getDecoder().decode(call.headers["Signature"]))
        assertEquals(1, header.int)
        val fileTime = header.long
        return Instant.ofEpochSecond(fileTime / 10_000_000L - 11_644_473_600L)
    }

    private fun verifySignature(key: PublicKey, call: HttpCall): Boolean {
        val header = ByteBuffer.wrap(Base64.getDecoder().decode(call.headers["Signature"]))
        val version = header.int
        val fileTime = header.long
        val signature = ByteArray(header.remaining()).also(header::get)
        val signed = ByteBuffer.allocate(4 + 1 + 8 + 1 + 1024 + call.body!!.size).apply {
            putInt(version).put(0).putLong(fileTime).put(0)
            for (part in listOf("POST".toByteArray(), URI(call.url).rawPath.toByteArray(), ByteArray(0), call.body)) put(part).put(0)
        }
        return Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(key)
            update(signed.array(), 0, signed.position())
            verify(signature)
        }
    }

    private fun JsonObject.string(name: String) = this[name]!!.jsonPrimitive.content

    // stands in for xbox live, microsoft and minecraft services
    private class FakeServices(
        private val serverTime: Instant = Instant.now(),
        private val failing: Map<String, HttpResult> = emptyMap(),
    ) : HttpTransport {
        val calls = mutableListOf<HttpCall>()
        lateinit var state: String
        lateinit var codeChallenge: String

        fun call(url: String) = calls.single { it.url == url }
        fun json(call: HttpCall) = Json.parseToJsonElement(call.body!!.decodeToString()).jsonObject
        fun form(call: HttpCall) = call.body!!.decodeToString().split('&').associate {
            val (name, value) = it.split('=', limit = 2).map { part -> URLDecoder.decode(part, Charsets.UTF_8) }
            name to value
        }

        fun proofKey(): PublicKey {
            val jwk = json(call(DEVICE_AUTHENTICATE))["Properties"]!!.jsonObject["ProofKey"]!!.jsonObject
            fun coordinate(name: String) = BigInteger(1, Base64.getUrlDecoder().decode(jwk[name]!!.jsonPrimitive.content))
            val p256 = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(coordinate("x"), coordinate("y")), p256))
        }

        override suspend fun send(call: HttpCall): HttpResult {
            calls += call
            failing[call.url]?.let { return it }
            val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(serverTime.atOffset(ZoneOffset.UTC))
            val headers = mutableMapOf("Date" to listOf(date))
            val body = when (call.url) {
                DEVICE_AUTHENTICATE -> """{"Token":"device-token","NotAfter":"${serverTime.plus(Duration.ofDays(14))}","DisplayClaims":{"xdi":{"did":"x"}}}"""
                SISU_AUTHENTICATE -> {
                    val query = json(call)["Query"]!!.jsonObject
                    state = query.string("state")
                    codeChallenge = query.string("code_challenge")
                    headers["X-SessionId"] = listOf("sisu-session")
                    """{"MsaOauthRedirect":"https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&state=$state"}"""
                }
                MSA_TOKEN -> """{"token_type":"bearer","expires_in":86400,"access_token":"msa-access","refresh_token":"msa-refresh"}"""
                SISU_AUTHORIZE -> """{"UserToken":{"Token":"user-token","NotAfter":"2099-01-01T00:00:00.0000000Z"},"TitleToken":{"Token":"title-token"}}"""
                XSTS_AUTHORIZE -> """{"Token":"xsts-token","DisplayClaims":{"xui":[{"uhs":"user-hash"}]}}"""
                MINECRAFT_LOGIN -> """{"username":"x","access_token":"minecraft-token","token_type":"Bearer","expires_in":86400}"""
                MINECRAFT_PROFILE -> """{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch","skins":[{"id":"old","state":"INACTIVE","url":"http://textures.minecraft.net/texture/old","variant":"CLASSIC"},{"id":"new","state":"ACTIVE","url":"http://textures.minecraft.net/texture/new","variant":"CLASSIC"}],"capes":[]}"""
                else -> error("unexpected request to ${call.url}")
            }
            return HttpResult(200, headers, body)
        }

        private fun JsonObject.string(name: String) = this[name]!!.jsonPrimitive.content
    }
}
