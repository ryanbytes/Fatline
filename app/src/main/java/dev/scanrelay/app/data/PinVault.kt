package dev.scanrelay.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PinVault(context: Context) {
    private val prefs = context.getSharedPreferences("scanrelay_pins", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun keyAlias(profileId: String) = "scanrelay_pin_$profileId"

    private fun key(profileId: String): SecretKey {
        val alias = keyAlias(profileId)
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun put(profileId: String, pin: String) {
        if (pin.isBlank()) {
            remove(profileId)
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(profileId))
        val cipherText = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
        require(cipher.iv.size in 1..255)
        val packed = ByteArray(1 + cipher.iv.size + cipherText.size)
        packed[0] = cipher.iv.size.toByte()
        System.arraycopy(cipher.iv, 0, packed, 1, cipher.iv.size)
        System.arraycopy(cipherText, 0, packed, 1 + cipher.iv.size, cipherText.size)
        prefs.edit().putString(profileId, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(profileId: String): String {
        val encoded = prefs.getString(profileId, null) ?: return ""
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 2)
            val ivLength = packed[0].toInt() and 0xff
            require(ivLength > 0 && packed.size > 1 + ivLength)
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val cipherText = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(profileId), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun remove(profileId: String) {
        prefs.edit().remove(profileId).apply()
        val alias = keyAlias(profileId)
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }
}
