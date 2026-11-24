package dev.pencilstub.mauvelauncher.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class MinecraftAuth internal constructor(private val http: HttpTransport) {
    constructor() : this(JdkHttpTransport())

    private val deviceLock = Mutex()
    private var device: XboxDevice? = null

    // xbox rejects signatures whose timestamp is too far off its own clock, so sign with its time
    @Volatile
    private var clockSkew: Duration = Duration.ZERO

    suspend fun beginLogin(): MicrosoftLoginFlow {
        val device = device()
        val verifier = Pkce.randomToken()
        val state = Pkce.randomToken()
        val step = MinecraftAuthStep.SisuAuthenticate
        val response = signedPost(device.key, SISU_AUTHENTICATE, step, buildJsonObject {
            put("AppId", CLIENT_ID)
            put("DeviceToken", device.token)
            putJsonArray("Offers") { add(SCOPE) }
            putJsonObject("Query") {
                put("code_challenge", Pkce.challenge(verifier))
                put("code_challenge_method", "S256")
                put("state", state)
                put("prompt", "select_account")
            }
            put("RedirectUri", REDIRECT_URI)
            put("Sandbox", "RETAIL")
            put("TokenType", "code")
            put("TitleId", TITLE_ID)
        })
        val loginPage = response.decode<SisuAuthenticateResponse>(step).msaOauthRedirect
        val sessionId = response.header("X-SessionId")
            ?: throw MinecraftAuthException(step, "Xbox Live didn't start a sign-in session. Try again.")
        return MicrosoftLoginFlow(loginPage, verifier, sessionId, state)
    }

    suspend fun finishLogin(flow: MicrosoftLoginFlow, code: String): MinecraftAccount {
        val device = device()

        val microsoft = send(
            MinecraftAuthStep.MicrosoftToken,
            HttpCall(
                "POST", MSA_TOKEN,
                mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
                formEncode(
                    "client_id" to CLIENT_ID,
                    "code" to code,
                    "code_verifier" to flow.codeVerifier,
                    "grant_type" to "authorization_code",
                    "redirect_uri" to REDIRECT_URI,
                    "scope" to SCOPE,
                ).toByteArray(),
            ),
        ).decode<MicrosoftToken>(MinecraftAuthStep.MicrosoftToken)

        val xbox = signedPost(device.key, SISU_AUTHORIZE, MinecraftAuthStep.SisuAuthorize, buildJsonObject {
            put("AccessToken", "t=${microsoft.accessToken}")
            put("AppId", CLIENT_ID)
            put("DeviceToken", device.token)
            put("ProofKey", device.key.proofKey)
            put("Sandbox", "RETAIL")
            put("SessionId", flow.sessionId)
            put("SiteName", "user.auth.xboxlive.com")
            put("RelyingParty", "http://xboxlive.com")
            put("UseModernGamertag", true)
        }).decode<SisuAuthorizeResponse>(MinecraftAuthStep.SisuAuthorize)

        val xsts = signedPost(device.key, XSTS_AUTHORIZE, MinecraftAuthStep.XstsAuthorize, buildJsonObject {
            put("RelyingParty", "rp://api.minecraftservices.com/")
            put("TokenType", "JWT")
            putJsonObject("Properties") {
                put("SandboxId", "RETAIL")
                putJsonArray("UserTokens") { add(xbox.userToken.token) }
                put("DeviceToken", device.token)
                put("TitleToken", xbox.titleToken.token)
            }
        }).decode<XboxToken>(MinecraftAuthStep.XstsAuthorize)
        val userHash = xsts.userHash
            ?: throw MinecraftAuthException(MinecraftAuthStep.XstsAuthorize, "Xbox Live didn't say which account signed in. Try again.")

        val minecraft = send(
            MinecraftAuthStep.MinecraftToken,
            HttpCall(
                "POST", MINECRAFT_LOGIN,
                mapOf("Content-Type" to "application/json", "Accept" to "application/json", "User-Agent" to USER_AGENT),
                buildJsonObject {
                    put("platform", "PC_LAUNCHER")
                    put("xtoken", "XBL3.0 x=$userHash;${xsts.token}")
                }.toString().toByteArray(),
            ),
        ).decode<MinecraftToken>(MinecraftAuthStep.MinecraftToken)

        val profileResponse = send(
            MinecraftAuthStep.MinecraftProfile,
            HttpCall(
                "GET", MINECRAFT_PROFILE,
                mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT, "Authorization" to "Bearer ${minecraft.accessToken}"),
                null,
            ),
        )
        if (profileResponse.status == 404) {
            throw MinecraftAuthException(
                MinecraftAuthStep.MinecraftProfile,
                "This Microsoft account doesn't own Minecraft: Java Edition, or hasn't picked a Minecraft username yet.",
                status = 404,
            )
        }
        val profile = profileResponse.decode<MinecraftProfile>(MinecraftAuthStep.MinecraftProfile)

        return MinecraftAccount(
            id = parseUuid(profile.id),
            name = profile.name,
            accessToken = minecraft.accessToken,
            accessTokenExpiresAt = Instant.now().plusSeconds(minecraft.expiresIn),
            microsoftRefreshToken = microsoft.refreshToken,
            skinUrl = profile.activeSkin?.url,
        )
    }

    private suspend fun device(): XboxDevice = deviceLock.withLock {
        device?.takeIf { it.expiresAt > xboxNow().plusSeconds(60) }?.let { return it }

        val key = device?.key ?: XboxDeviceKey.generate()
        val step = MinecraftAuthStep.DeviceToken
        val token = signedPost(key, DEVICE_AUTHENTICATE, step, buildJsonObject {
            putJsonObject("Properties") {
                put("AuthMethod", "ProofOfPossession")
                put("Id", "{${key.id.toString().uppercase()}}")
                put("DeviceType", "Win32")
                put("Version", "10.16.0")
                put("ProofKey", key.proofKey)
            }
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }).decode<XboxToken>(step)
        XboxDevice(key, token.token, Instant.parse(token.notAfter)).also { device = it }
    }

    private fun xboxNow(): Instant = Instant.now().plus(clockSkew)

    private suspend fun signedPost(key: XboxDeviceKey, url: String, step: MinecraftAuthStep, body: JsonObject): HttpResult {
        val bytes = body.toString().toByteArray()
        val headers = buildMap {
            put("Content-Type", "application/json; charset=utf-8")
            put("Accept", "application/json")
            put("Signature", key.sign(xboxNow(), URI(url).rawPath, authorization = "", bytes))
            // sisu's authorize endpoint is the one that doesn't take it
            if (url != SISU_AUTHORIZE) put("x-xbl-contract-version", "1")
        }
        return send(step, HttpCall("POST", url, headers, bytes))
    }

    private suspend fun send(step: MinecraftAuthStep, call: HttpCall): HttpResult {
        val result = try {
            http.send(call)
        } catch (e: IOException) {
            throw MinecraftAuthException(step, "Couldn't connect to ${step.service}. Check your internet connection and try again.", cause = e)
        }
        result.header("Date")
            ?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
            ?.let { clockSkew = Duration.between(Instant.now(), it) }
        return result
    }

    private inline fun <reified T> HttpResult.decode(step: MinecraftAuthStep): T {
        if (status !in 200..299) throw errorFor(step, this)
        return try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw MinecraftAuthException(step, "${step.service} sent a response the launcher doesn't understand.", status, e)
        } catch (e: IllegalArgumentException) {
            throw MinecraftAuthException(step, "${step.service} sent a response the launcher doesn't understand.", status, e)
        }
    }

    private class XboxDevice(val key: XboxDeviceKey, val token: String, val expiresAt: Instant)

    internal companion object {
        const val CLIENT_ID = "00000000402b5328"
        const val TITLE_ID = "1794566092"
        const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
        const val USER_AGENT = "Mauve Launcher"

        const val DEVICE_AUTHENTICATE = "https://device.auth.xboxlive.com/device/authenticate"
        const val SISU_AUTHENTICATE = "https://sisu.xboxlive.com/authenticate"
        const val SISU_AUTHORIZE = "https://sisu.xboxlive.com/authorize"
        const val XSTS_AUTHORIZE = "https://xsts.auth.xboxlive.com/xsts/authorize"
        const val MSA_TOKEN = "https://login.live.com/oauth20_token.srf"
        const val MINECRAFT_LOGIN = "https://api.minecraftservices.com/launcher/login"
        const val MINECRAFT_PROFILE = "https://api.minecraftservices.com/minecraft/profile"

        private val json = Json { ignoreUnknownKeys = true }

        // XErr codes xbox live uses to say why an account can't sign in
        private val xboxErrors = mapOf(
            2148916222 to "This account needs to complete age verification on xbox.com before it can play.",
            2148916227 to "This account is banned from Xbox Live.",
            2148916229 to "This account isn't allowed to play online. A parent can allow it in the account's Microsoft Family settings.",
            2148916233 to "This Microsoft account doesn't have an Xbox profile yet. Sign in at minecraft.net once to create one, then try again.",
            2148916234 to "This account hasn't accepted the Xbox Live terms of use. Sign in at xbox.com to accept them, then try again.",
            2148916235 to "Xbox Live isn't available in this account's country or region.",
            2148916236 to "This account needs to complete adult verification on xbox.com before it can play.",
            2148916237 to "This account needs to complete adult verification on xbox.com before it can play.",
            2148916238 to "This is a child account. An adult needs to add it to a Microsoft family before it can play.",
        )

        fun errorFor(step: MinecraftAuthStep, response: HttpResult): MinecraftAuthException {
            val body = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            val message = body?.get("XErr")?.jsonPrimitive?.longOrNull?.let(xboxErrors::get)
                ?: when {
                    response.status == 429 -> "Too many sign-in attempts. Wait a few minutes, then try again."
                    response.status >= 500 -> "${step.service} is having problems right now. Try again later."
                    step == MinecraftAuthStep.MicrosoftToken ->
                        body?.get("error_description")?.jsonPrimitive?.contentOrNull?.let { "Microsoft didn't accept the sign-in: $it" }
                    else -> null
                }
                ?: "${step.service} didn't accept the sign-in (HTTP ${response.status} while ${step.action}). Try again."
            return MinecraftAuthException(step, message, response.status)
        }

        fun parseUuid(id: String): UUID = UUID.fromString(
            if (id.length == 32) "${id.take(8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.drop(20)}" else id
        )

        private fun formEncode(vararg params: Pair<String, String>) = params.joinToString("&") { (name, value) ->
            URLEncoder.encode(name, Charsets.UTF_8) + "=" + URLEncoder.encode(value, Charsets.UTF_8)
        }
    }
}

class MinecraftAccount(
    val id: UUID,
    val name: String,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val microsoftRefreshToken: String,
    val skinUrl: String? = null,
) {
    override fun toString() = "MinecraftAccount(id=$id, name=$name)"
}

enum class MinecraftAuthStep(val service: String, val action: String) {
    DeviceToken("Xbox Live", "registering this device"),
    SisuAuthenticate("Xbox Live", "starting the sign-in"),
    MicrosoftToken("Microsoft", "getting a Microsoft token"),
    SisuAuthorize("Xbox Live", "signing in to Xbox Live"),
    XstsAuthorize("Xbox Live", "authorizing Minecraft"),
    MinecraftToken("Minecraft", "signing in to Minecraft"),
    MinecraftProfile("Minecraft", "loading the Minecraft profile"),
}

// message is meant to be shown to the user as is
class MinecraftAuthException(
    val step: MinecraftAuthStep,
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
internal class XboxToken(
    @SerialName("Token") val token: String,
    @SerialName("NotAfter") val notAfter: String = "",
    @SerialName("DisplayClaims") val displayClaims: JsonObject = JsonObject(emptyMap()),
) {
    val userHash: String?
        get() = runCatching { displayClaims["xui"]!!.jsonArray[0].jsonObject["uhs"]!!.jsonPrimitive.content }.getOrNull()
}

@Serializable
internal class SisuAuthenticateResponse(@SerialName("MsaOauthRedirect") val msaOauthRedirect: String)

@Serializable
internal class SisuAuthorizeResponse(
    @SerialName("UserToken") val userToken: XboxToken,
    @SerialName("TitleToken") val titleToken: XboxToken,
)

@Serializable
internal class MicrosoftToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
internal class MinecraftToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 86_400,
)

@Serializable
internal class MinecraftProfile(val id: String, val name: String, val skins: List<MinecraftSkin> = emptyList()) {
    val activeSkin: MinecraftSkin?
        get() = skins.firstOrNull { it.state == "ACTIVE" } ?: skins.firstOrNull()
}

@Serializable
internal class MinecraftSkin(val url: String, val state: String = "")
