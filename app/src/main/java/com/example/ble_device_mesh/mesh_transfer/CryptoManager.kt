package com.example.ble_device_mesh.mesh_transfer

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 加密与密钥派生。
 * 会话密钥由 6 位配对验证码 + BLE 交换的 32 字节随机盐经 PBKDF2 派生。
 */
object CryptoManager {

    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256

    private val random = SecureRandom()

    fun generateSalt(): ByteArray {
        return ByteArray(32).also { random.nextBytes(it) }
    }

    fun generatePairCode(): String {
        return (0 until 6).joinToString("") { random.nextInt(10).toString() }
    }

    fun deriveSessionKey(code: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** 返回 iv(12) + ciphertext+tag */
    fun encrypt(key: SecretKeySpec, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    /** 输入 iv(12) + ciphertext+tag，失败（tag 不匹配）抛异常 */
    fun decrypt(key: SecretKeySpec, data: ByteArray): ByteArray {
        require(data.size > IV_LEN) { "密文长度异常" }
        val iv = data.copyOfRange(0, IV_LEN)
        val ct = data.copyOfRange(IV_LEN, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    fun base64Encode(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
    fun base64Decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
