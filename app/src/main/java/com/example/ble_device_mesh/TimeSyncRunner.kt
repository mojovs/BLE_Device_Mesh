package com.example.ble_device_mesh

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * 带唤醒锁的时间同步执行器。
 *
 * 闹钟唤醒后 CPU 只有很短的白名单时间，必须自己持有 PartialWakeLock，
 * 否则 CPU 睡下去 BLE 连接会建立失败——这是“定时唤醒同步”最常见的失败原因。
 */
object TimeSyncRunner {

    private const val TAG = "MeshTimeSync"
    private const val WAKE_LOCK_TIMEOUT_MS = 180_000L

    fun runWithWakeLock(context: Context, reason: String, onFinished: (() -> Unit)? = null) {
        val app = context.applicationContext
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = try {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshTimeSync::$reason")
        } catch (e: Exception) {
            Log.w(TAG, "申请唤醒锁失败: ${e.message}")
            null
        }

        try {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "已持有唤醒锁（原因: $reason）")
        } catch (e: Exception) {
            Log.w(TAG, "获取唤醒锁异常: ${e.message}")
        }

        TimeSyncEngine.run(app, reason) {
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
                Log.d(TAG, "已释放唤醒锁（原因: $reason）")
            } catch (e: Exception) {
                Log.w(TAG, "释放唤醒锁异常: ${e.message}")
            }
            onFinished?.invoke()
        }
    }
}
