package dev.pencilstub.mauvelauncher.auth

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class MicrosoftLoginFlow internal constructor(
    val authorizationUrl: String,
    internal val codeVerifier: String,
    internal val sessionId: String,
    private val state: String,
) {
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
        private const val REDIRECT_HOST = "login.live.com"
        private const val REDIRECT_PATH = "/oauth20_desktop.srf"
    }
}

sealed interface MicrosoftLoginResult {
    class Success(val code: String) : MicrosoftLoginResult {
        override fun toString() = "Success(code=<redacted>)"
    }

    data class Failure(val error: String, val description: String?) : MicrosoftLoginResult
}

internal object Pkce {
    private val random = SecureRandom()

    fun randomToken(): String = base64Url(ByteArray(32).also(random::nextBytes))

    fun challenge(verifier: String): String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
