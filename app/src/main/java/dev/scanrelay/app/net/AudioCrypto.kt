package dev.scanrelay.app.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AudioCrypto(private val httpClient: OkHttpClient = OkHttpClient()) {
    fun fetchMasterKey(relayUrl: String, clientToken: String): ByteArray {
        require(relayUrl.isNotBlank()) { "Relay URL is missing" }
        require(clientToken.isNotBlank()) { "Audio client token is missing" }

        val pair = generateP256KeyPair()
        val requestJson = JSONObject()
            .put("public_key", Base64.getEncoder().encodeToString(encodeUncompressed(pair.public as ECPublicKey)))
            .toString()
        val request = Request.Builder()
            .url(relayUrl.trimEnd('/') + "/api/audio/key-exchange")
            .header("Authorization", "Bearer $clientToken")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Relay key exchange failed: HTTP ${response.code}")
            }
            val body = response.body.string()
            val json = JSONObject(body)
            val serverPublic = Base64.getDecoder().decode(json.getString("public_key"))
            val wrappedKey = Base64.getDecoder().decode(json.getString("wrapped_key"))
            val shared = deriveSharedSecret(pair, decodeP256PublicKey(serverPublic))
            val wrappingKey = hkdfSha256(shared, byteArrayOf(), "tlr-audio-key-wrap-v1".toByteArray(), 32)
            val masterKey = decryptNoncePrefixed(wrappingKey, wrappedKey)
            require(masterKey.size == 32) { "Relay returned ${masterKey.size}-byte master key" }
            return masterKey
        }
    }

    fun decryptCall(masterKey: ByteArray, base64Ciphertext: String): ByteArray =
        decryptNoncePrefixed(masterKey, Base64.getDecoder().decode(base64Ciphertext))

    companion object {
        internal fun generateP256KeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        internal fun deriveSharedSecret(pair: KeyPair, peer: ECPublicKey): ByteArray =
            KeyAgreement.getInstance("ECDH").run {
                init(pair.private)
                doPhase(peer, true)
                generateSecret()
            }

        internal fun encodeUncompressed(key: ECPublicKey): ByteArray {
            val x = fixedUnsigned(key.w.affineX, 32)
            val y = fixedUnsigned(key.w.affineY, 32)
            return byteArrayOf(0x04) + x + y
        }

        internal fun decodeP256PublicKey(raw: ByteArray): ECPublicKey {
            require(raw.size == 65 && raw[0] == 0x04.toByte()) { "Invalid P-256 public key" }
            val x = BigInteger(1, raw.copyOfRange(1, 33))
            val y = BigInteger(1, raw.copyOfRange(33, 65))
            val spec = p256Params()
            return KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
        }

        internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length in 1..(255 * 32))
            val mac = Mac.getInstance("HmacSHA256")
            val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
            mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            val output = ByteArray(length)
            var previous = byteArrayOf()
            var offset = 0
            var counter = 1
            while (offset < length) {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                val copy = minOf(previous.size, length - offset)
                previous.copyInto(output, offset, 0, copy)
                offset += copy
                counter++
            }
            return output
        }

        internal fun decryptNoncePrefixed(key: ByteArray, packed: ByteArray): ByteArray {
            require(key.size == 32) { "AES-256 key required" }
            require(packed.size >= 12 + 16) { "Ciphertext is too short" }
            val nonce = packed.copyOfRange(0, 12)
            val ciphertext = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            return cipher.doFinal(ciphertext)
        }

        private fun fixedUnsigned(value: BigInteger, size: Int): ByteArray {
            val raw = value.toByteArray()
            val unsigned = if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size) else raw
            return ByteArray(size - unsigned.size) + unsigned
        }

        private fun p256Params(): ECParameterSpec {
            val params = AlgorithmParameters.getInstance("EC")
            params.init(ECGenParameterSpec("secp256r1"))
            return params.getParameterSpec(ECParameterSpec::class.java)
        }
    }
}
