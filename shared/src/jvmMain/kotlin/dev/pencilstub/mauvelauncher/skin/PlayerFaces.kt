package dev.pencilstub.mauvelauncher.skin

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.pencilstub.mauvelauncher.auth.HttpCall
import dev.pencilstub.mauvelauncher.auth.HttpTransport
import dev.pencilstub.mauvelauncher.auth.JdkHttpTransport
import dev.pencilstub.mauvelauncher.auth.MinecraftAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import javax.imageio.ImageIO

// the front of the head from an account's skin, hat layer included
class PlayerFaces internal constructor(private val http: HttpTransport) {
    constructor() : this(JdkHttpTransport())

    suspend fun load(account: MinecraftAccount): ImageBitmap? = face(account)?.toComposeImageBitmap()

    internal suspend fun face(account: MinecraftAccount): BufferedImage? {
        val url = account.skinUrl?.let(::secure) ?: return null
        val response = try {
            http.send(HttpCall("GET", url, mapOf("User-Agent" to USER_AGENT), null))
        } catch (e: IOException) {
            return null
        }
        if (response.status !in 200..299) return null
        return withContext(Dispatchers.Default) {
            val skin = try {
                ImageIO.read(ByteArrayInputStream(response.bytes))
            } catch (e: IOException) {
                null
            }
            skin?.let(SkinFace::of)
        }
    }

    private companion object {
        const val USER_AGENT = "Mauve Launcher"

        fun secure(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            return when (uri.scheme?.lowercase()) {
                "https" -> url
                "http" -> "https" + url.substring(uri.scheme.length)
                else -> null
            }
        }
    }
}

internal object SkinFace {
    // where the face sits in a 64-pixel-wide skin
    private const val FACE_X = 8
    private const val HAT_X = 40
    private const val FACE_Y = 8
    private const val FACE_SIZE = 8

    // skins are 64x64, or 64x32 from before 1.8, or bigger multiples of those
    fun of(skin: BufferedImage): BufferedImage? {
        val scale = skin.width / 64
        val legacy = skin.height * 2 == skin.width
        if (scale == 0 || skin.width % 64 != 0 || (skin.height != skin.width && !legacy)) return null

        val size = FACE_SIZE * scale
        val drawHat = !legacy || hasTransparency(skin, 32 * scale, 0, skin.width, skin.height)
        val face = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val base = skin.getRGB(FACE_X * scale + x, FACE_Y * scale + y) or OPAQUE
                val pixel = if (drawHat) over(skin.getRGB(HAT_X * scale + x, FACE_Y * scale + y), base) else base
                face.setRGB(x, y, pixel)
            }
        }
        return face
    }

    private const val OPAQUE = 0xFF000000.toInt()

    private fun hasTransparency(image: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                if (image.getRGB(x, y) ushr 24 < 128) return true
            }
        }
        return false
    }

    // blends top onto an opaque bottom pixel
    private fun over(top: Int, bottom: Int): Int {
        val alpha = top ushr 24
        if (alpha == 0) return bottom
        if (alpha == 255) return top
        fun channel(shift: Int): Int {
            val t = (top shr shift) and 0xFF
            val b = (bottom shr shift) and 0xFF
            return (t * alpha + b * (255 - alpha) + 127) / 255
        }
        return OPAQUE or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}