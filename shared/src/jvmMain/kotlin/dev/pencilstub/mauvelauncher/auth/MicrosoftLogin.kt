package dev.pencilstub.mauvelauncher.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class MicrosoftLoginFlow private constructor(
    val codeVerifier: String,
    private val state: String,
) {
    val authorizationUrl: String = AUTHORIZE_ENDPOINT + "?" + formEncode(
        "client_id" to CLIENT_ID,
        "response_type" to "code",
        "scope" to SCOPE,
        "redirect_uri" to REDIRECT_URI,
        "prompt" to "select_account",
        "code_challenge" to base64Url(MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray())),
        "code_challenge_method" to "S256",
        "state" to state,
    )

    fun resultOf(url: String): MicrosoftLoginResult? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != REDIRECT_HOST || uri.path != REDIRECT_PATH) return null

        val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { param ->
            val (name, value) = param.split('=', limit = 2).map { URLDecoder.decode(it, Charsets.UTF_8) } + ""
            name to value
        }
        val code = params["code"]
        return when {
            params["state"] != state -> MicrosoftLoginResult.Failure("state_mismatch", "The sign-in response didn't match this sign-in attempt")
            code != null -> MicrosoftLoginResult.Success(code)
            else -> MicrosoftLoginResult.Failure(params["error"] ?: "unknown_error", params["error_description"])
        }
    }

    companion object {
        const val CLIENT_ID = "00000000402b5328"
        const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
        private const val REDIRECT_HOST = "login.live.com"
        private const val REDIRECT_PATH = "/oauth20_desktop.srf"
        private const val AUTHORIZE_ENDPOINT = "https://login.live.com/oauth20_authorize.srf"

        private val random = SecureRandom()

        fun begin() = MicrosoftLoginFlow(codeVerifier = randomToken(), state = randomToken())

        private fun randomToken() = base64Url(ByteArray(32).also(random::nextBytes))

        private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun formEncode(vararg params: Pair<String, String>) = params.joinToString("&") { (name, value) ->
            URLEncoder.encode(name, Charsets.UTF_8) + "=" + URLEncoder.encode(value, Charsets.UTF_8)
        }
    }
}

sealed interface MicrosoftLoginResult {
    class Success(val code: String) : MicrosoftLoginResult {
        override fun toString() = "Success(code=<redacted>)"
    }

    data class Failure(val error: String, val description: String?) : MicrosoftLoginResult
}
