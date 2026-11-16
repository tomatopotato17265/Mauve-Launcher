package dev.pencilstub.mauvelauncher.auth

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MicrosoftLoginFlowTest {
    private val flow = MicrosoftLoginFlow.begin()
    private val params = URI(flow.authorizationUrl).rawQuery.split('&').associate {
        val (name, value) = it.split('=', limit = 2)
        name to URLDecoder.decode(value, Charsets.UTF_8)
    }

    @Test
    fun authorizationUrlRequestsMinecraftLauncherSignIn() {
        assertEquals("https://login.live.com/oauth20_authorize.srf", flow.authorizationUrl.substringBefore('?'))
        assertEquals("00000000402b5328", params["client_id"])
        assertEquals("code", params["response_type"])
        assertEquals("service::user.auth.xboxlive.com::MBI_SSL", params["scope"])
        assertEquals("https://login.live.com/oauth20_desktop.srf", params["redirect_uri"])
    }

    @Test
    fun authorizationUrlCarriesPkceChallengeForVerifier() {
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(flow.codeVerifier.toByteArray()))
        assertEquals(expected, params["code_challenge"])
        assertEquals("S256", params["code_challenge_method"])
    }

    @Test
    fun eachFlowIsUnique() {
        val other = MicrosoftLoginFlow.begin()
        assertNotEquals(flow.codeVerifier, other.codeVerifier)
        assertNotEquals(flow.authorizationUrl, other.authorizationUrl)
    }

    @Test
    fun ignoresMicrosoftsOwnPages() {
        assertNull(flow.resultOf(flow.authorizationUrl))
        assertNull(flow.resultOf("https://login.live.com/ppsecure/post.srf?code=nope"))
        assertNull(flow.resultOf("https://account.live.com/oauth20_desktop.srf?code=nope"))
        assertNull(flow.resultOf("not a url"))
    }

    @Test
    fun redirectWithCodeSucceeds() {
        val result = flow.resultOf(redirect("code=M.C123_abc%2B%3D&state=${params["state"]}"))
        assertIs<MicrosoftLoginResult.Success>(result)
        assertEquals("M.C123_abc+=", result.code)
    }

    @Test
    fun redirectWithErrorFails() {
        val result = flow.resultOf(redirect("error=access_denied&error_description=The+user+declined&state=${params["state"]}"))
        assertEquals(MicrosoftLoginResult.Failure("access_denied", "The user declined"), result)
    }

    @Test
    fun redirectForAnotherAttemptIsRejected() {
        val result = flow.resultOf(redirect("code=M.C123&state=someone-elses"))
        assertIs<MicrosoftLoginResult.Failure>(result)
        assertEquals("state_mismatch", result.error)
    }

    private fun redirect(query: String) = "https://login.live.com/oauth20_desktop.srf?$query"
}
