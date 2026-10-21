package com.example.ble_device_mesh

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.ble_device_mesh.mesh_transfer.CryptoManager
import javax.crypto.spec.SecretKeySpec

/**
 * 本机保存的“设备密码”，按设备单播地址区分。
 *
 * - 只在本机使用，不会随 Mesh 网络导出（导出内容只有网络 Key 和设备元数据）。
 * - 用 AES-GCM 加密后存 SharedPreferences，密钥由本机 ANDROID_ID 派生。
 *   这不是硬件级安全，但可避免密码以明文出现在 prefs 里。
 */
object PasswordStore {

    private const val PREFS = "DevicePasswordStore"
    private val KEY_SALT = "ble-mesh-device-password-v1".toByteArray(Charsets.UTF_8)

    private var cachedKey: SecretKeySpec? = null
    private var cachedAndroidId: String? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyFor(address: Int) = "pwd_0x${address.toString(16)}"

    private fun secretKey(context: Context): SecretKeySpec {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
        cachedKey?.let { if (cachedAndroidId == androidId) return it }
        val key = CryptoManager.deriveSessionKey(androidId, KEY_SALT)
        cachedKey = key
        cachedAndroidId = androidId
        return key
    }

    /** 保存密码；0000 视为默认密码，直接清除记录 */
    fun save(context: Context, address: Int, password: String) {
        if (password == "0000") {
            clear(context, address)
            return
        }
        try {
            val encrypted = CryptoManager.encrypt(secretKey(context), password.toByteArray(Charsets.UTF_8))
            prefs(context).edit()
                .putString(keyFor(address), CryptoManager.base64Encode(encrypted))
                .apply()
        } catch (e: Exception) {
            Log.e("PasswordStore", "保存密码失败: ${e.message}")
        }
    }

    /** 读取密码；没有记录或解密失败返回 null */
    fun get(context: Context, address: Int): String? {
        val encoded = prefs(context).getString(keyFor(address), null) ?: return null
        return try {
            val decrypted = CryptoManager.decrypt(
                secretKey(context), CryptoManager.base64Decode(encoded)
            )
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("PasswordStore", "读取密码失败: ${e.message}")
            null
        }
    }

    fun clear(context: Context, address: Int) {
        prefs(context).edit().remove(keyFor(address)).apply()
    }
}
