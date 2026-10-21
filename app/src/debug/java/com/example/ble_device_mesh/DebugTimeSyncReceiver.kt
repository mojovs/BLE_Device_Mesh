package com.example.ble_device_mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 调试用：不启动界面直接触发一次时间同步，用于验证“进程被闹钟唤醒”的
 * headless 连接通路是否可用。
 *
 *   adb shell am broadcast -a com.example.ble_device_mesh.DEBUG_TIME_SYNC \
 *       -n com.example.ble_device_mesh/.DebugTimeSyncReceiver
 *
 * 只在 debug 构建里存在（见 src/debug/AndroidManifest.xml）。
 */
class DebugTimeSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = intent.getStringExtra("reason") ?: "debug"
        Log.d("MeshTimeSync", "调试触发时间同步 (reason=$reason)")
        val pendingResult = goAsync()
        TimeSyncRunner.runWithWakeLock(context, reason) {
            try {
                pendingResult.finish()
            } catch (e: Exception) {
                Log.w("MeshTimeSync", "结束广播失败: ${e.message}")
            }
        }
    }
}
