package dev.pencilstub.mauvelauncher.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

// the key xbox live ties a device to. every request to xbox's auth services is signed with it (proof of possession)
internal class XboxDeviceKey private constructor(private val keyPair: KeyPair) {
    val id: UUID = UUID.randomUUID()

    internal val publicKey: PublicKey get() = keyPair.public

    val proofKey: JsonObject = (keyPair.public as ECPublicKey).w.let { point ->
        buildJsonObject {
            put("kty", "EC")
            put("x", coordinate(point.affineX))
            put("y", coordinate(point.affineY))
            put("crv", "P-256")
            put("alg", "ES256")
            put("use", "sig")
        }
    }

    fun sign(time: Instant, path: String, authorization: String, body: ByteArray): String {
        val fileTime = windowsFileTime(time)
        val signed = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(SIGNATURE_VERSION).array())
            write(0)
            write(ByteBuffer.allocate(8).putLong(fileTime).array())
            write(0)
            for (part in listOf("POST".toByteArray(), path.toByteArray(), authorization.toByteArray(), body)) {
                write(part)
                write(0)
            }
        }.toByteArray()
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(keyPair.private)
            update(signed)
            sign()
        }
        val header = ByteBuffer.allocate(4 + 8 + signature.size).putInt(SIGNATURE_VERSION).putLong(fileTime).put(signature)
        return Base64.getEncoder().encodeToString(header.array())
    }

    companion object {
        private const val SIGNATURE_VERSION = 1

        fun generate() = XboxDeviceKey(
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        )

        internal fun windowsFileTime(time: Instant) = (time.epochSecond + 11_644_473_600L) * 10_000_000L

        // a P-256 coordinate as exactly 32 unsigned big-endian bytes, base64url without padding
        private fun coordinate(value: BigInteger): String {
            val bytes = value.toByteArray().takeLast(32).toByteArray()
            val padded = ByteArray(32 - bytes.size) + bytes
            return Base64.getUrlEncoder().withoutPadding().encodeToString(padded)
        }
    }
}
