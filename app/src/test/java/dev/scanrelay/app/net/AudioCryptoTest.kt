package dev.scanrelay.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AudioCryptoTest {
    @Test fun rfc5869HkdfSha256Vector() {
        val hex = HexFormat.of()
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex.parseHex("000102030405060708090a0b0c")
        val info = hex.parseHex("f0f1f2f3f4f5f6f7f8f9")
        val expected = hex.parseHex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
        assertArrayEquals(expected, AudioCrypto.hkdfSha256(ikm, salt, info, 42))
    }

    @Test fun p256RawPointRoundTripsAndAgrees() {
        val a = AudioCrypto.generateP256KeyPair()
        val b = AudioCrypto.generateP256KeyPair()
        val bDecoded = AudioCrypto.decodeP256PublicKey(AudioCrypto.encodeUncompressed(b.public as java.security.interfaces.ECPublicKey))
        val aDecoded = AudioCrypto.decodeP256PublicKey(AudioCrypto.encodeUncompressed(a.public as java.security.interfaces.ECPublicKey))
        assertArrayEquals(AudioCrypto.deriveSharedSecret(a, bDecoded), AudioCrypto.deriveSharedSecret(b, aDecoded))
    }

    @Test fun aesGcmNoncePrefixDecrypts() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 7).toByte() }
        val plaintext = "scanner audio".toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val packed = nonce + cipher.doFinal(plaintext)
        assertArrayEquals(plaintext, AudioCrypto.decryptNoncePrefixed(key, packed))
    }
}
