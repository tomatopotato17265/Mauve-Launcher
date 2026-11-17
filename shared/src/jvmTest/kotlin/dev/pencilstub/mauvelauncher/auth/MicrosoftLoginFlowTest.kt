package dev.pencilstub.mauvelauncher.auth

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MicrosoftLoginFlowTest {
    private val flow = MicrosoftLoginFlow("https://login.live.com/oauth20_authorize.srf?x=1", "verifier", "session", state = "the-state")

    @Test
    fun ignoresMicrosoftsOwnPages() {
        assertNull(flow.resultOf(flow.authorizationUrl))
        assertNull(flow.resultOf("https://login.live.com/ppsecure/post.srf?code=nope"))
        assertNull(flow.resultOf("https://account.live.com/oauth20_desktop.srf?code=nope"))
        assertNull(flow.resultOf("not a url"))
    }

    @Test
    fun redirectWithCodeSucceeds() {
        val result = flow.resultOf(redirect("code=M.C123_abc%2B%3D&state=the-state"))
        assertIs<MicrosoftLoginResult.Success>(result)
        assertEquals("M.C123_abc+=", result.code)
    }

    @Test
    fun redirectWithErrorFails() {
        val result = flow.resultOf(redirect("error=access_denied&error_description=The+user+declined&state=the-state"))
        assertEquals(MicrosoftLoginResult.Failure("access_denied", "The user declined"), result)
    }

    @Test
    fun redirectForAnotherAttemptIsRejected() {
        val result = flow.resultOf(redirect("code=M.C123&state=someone-elses"))
        assertIs<MicrosoftLoginResult.Failure>(result)
        assertEquals("state_mismatch", result.error)
    }

    @Test
    fun pkceChallengeIsUnpaddedBase64UrlSha256OfVerifier() {
        val verifier = Pkce.randomToken()
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertEquals(expected, Pkce.challenge(verifier))
        assertEquals(43, verifier.length)
        assertNotEquals(verifier, Pkce.randomToken())
    }

    private fun redirect(query: String) = "https://login.live.com/oauth20_desktop.srf?$query"
}
