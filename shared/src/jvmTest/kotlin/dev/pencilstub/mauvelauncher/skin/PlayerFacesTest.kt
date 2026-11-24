package dev.pencilstub.mauvelauncher.skin

import dev.pencilstub.mauvelauncher.auth.HttpCall
import dev.pencilstub.mauvelauncher.auth.HttpResult
import dev.pencilstub.mauvelauncher.auth.MinecraftAccount
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlayerFacesTest {
    @Test
    fun cutsTheFaceOutOfTheSkin() {
        val skin = skin(64, 64)
        val face = assertNotNull(SkinFace.of(skin))

        assertEquals(8, face.width)
        assertEquals(8, face.height)
        assertEquals(FACE, face.getRGB(0, 0))
        assertEquals(FACE, face.getRGB(7, 7))
    }

    @Test
    fun drawsTheHatOverTheFace() {
        val skin = skin(64, 64).apply {
            setRGB(40, 8, HAT)
            setRGB(47, 15, 0x80FFFFFF.toInt())
        }
        val face = assertNotNull(SkinFace.of(skin))

        assertEquals(HAT, face.getRGB(0, 0))
        assertEquals(FACE, face.getRGB(1, 0), "transparent hat pixels show the face")
        // half-transparent white over 0x336699
        assertEquals(0xFF99B3CC.toInt(), face.getRGB(7, 7))
    }

    @Test
    fun faceIsAlwaysOpaque() {
        val skin = skin(64, 64).apply { setRGB(8, 8, 0x00336699) }

        assertEquals(FACE, SkinFace.of(skin)!!.getRGB(0, 0))
    }

    @Test
    fun ignoresSolidHatsOnOldSkins() {
        val skin = skin(64, 32)
        for (y in 0 until 32) for (x in 32 until 64) skin.setRGB(x, y, HAT)

        assertEquals(FACE, SkinFace.of(skin)!!.getRGB(0, 0))
    }

    @Test
    fun keepsSeeThroughHatsOnOldSkins() {
        val skin = skin(64, 32).apply { setRGB(40, 8, HAT) }

        assertEquals(HAT, SkinFace.of(skin)!!.getRGB(0, 0))
    }

    @Test
    fun scalesWithHdSkins() {
        val skin = skin(128, 128).apply { setRGB(80, 16, HAT) }
        val face = assertNotNull(SkinFace.of(skin))

        assertEquals(16, face.width)
        assertEquals(HAT, face.getRGB(0, 0))
        assertEquals(FACE, face.getRGB(15, 15))
    }

    @Test
    fun rejectsImagesThatArentSkins() {
        assertNull(SkinFace.of(skin(32, 32)))
        assertNull(SkinFace.of(skin(64, 48)))
        assertNull(SkinFace.of(skin(100, 100)))
    }

    @Test
    fun downloadsTheSkinOverHttps() = runTest {
        val calls = mutableListOf<HttpCall>()
        val faces = PlayerFaces { call -> calls += call; HttpResult(200, emptyMap(), png(skin(64, 64))) }

        val face = assertNotNull(faces.face(account("http://textures.minecraft.net/texture/abc")))

        assertEquals(listOf("https://textures.minecraft.net/texture/abc"), calls.map { it.url })
        assertEquals(FACE, face.getRGB(3, 3))
    }

    @Test
    fun noFaceWhenTheSkinIsUnavailable() = runTest {
        val url = "https://textures.minecraft.net/texture/abc"
        assertNull(PlayerFaces { error("nothing to download") }.face(account(null)))
        assertNull(PlayerFaces { HttpResult(404, emptyMap(), "") }.face(account(url)))
        assertNull(PlayerFaces { throw IOException("offline") }.face(account(url)))
        assertNull(PlayerFaces { HttpResult(200, emptyMap(), "not a png") }.face(account(url)))
        assertNull(PlayerFaces { error("not fetched") }.face(account("file:///etc/passwd")))
    }

    private fun skin(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
        val scale = width / 64
        for (y in 8 * scale until 16 * scale) for (x in 8 * scale until 16 * scale) {
            if (x < width && y < height) setRGB(x, y, FACE)
        }
    }

    private fun png(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    private fun account(skinUrl: String?) = MinecraftAccount(
        id = UUID.randomUUID(),
        name = "Notch",
        accessToken = "token",
        accessTokenExpiresAt = Instant.now(),
        microsoftRefreshToken = "refresh",
        skinUrl = skinUrl,
    )

    private companion object {
        const val FACE = 0xFF336699.toInt()
        const val HAT = 0xFFCC2222.toInt()
    }
}