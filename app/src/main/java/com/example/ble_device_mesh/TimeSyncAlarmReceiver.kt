package com.example.ble_device_mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 时间同步闹钟的接收器。
 *
 * 正式闹钟到点后先排下一次（保证即使本次失败，后续节奏也不会断），再持唤醒锁
 * 跑一次同步：临时连 proxy → 读时间 → 只写偏差大的设备 → 复检 → 断开。
 *
 * 失败重试用另一条 action（[ACTION_TIME_SYNC_RETRY]），不影响正式节奏。
 */
class TimeSyncAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MeshTimeSync"
        const val ACTION_TIME_SYNC_ALARM = "com.example.ble_device_mesh.TIME_SYNC_ALARM"
        const val ACTION_TIME_SYNC_RETRY = "com.example.ble_device_mesh.TIME_SYNC_RETRY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "闹钟触发: $action")

        // 角色可能在排完闹钟之后才变成「仅控制」，或者用户刚关掉自动同步，
        // 这里再兜一次，避免残留闹钟继续写设备时间。
        if (!AppRole.canWriteTime(context) || !TimeSyncScheduler.isEnabled(context)) {
            Log.d(TAG, "自动同步已关闭或本机为仅控制，忽略本次闹钟并清除")
            TimeSyncScheduler.cancel(context)
            return
        }

        if (action == ACTION_TIME_SYNC_ALARM) {
            // 正式周期：清零重试计数，并排下一次
            TimeSyncScheduler.resetRetryCount(context)
            TimeSyncScheduler.rescheduleAfterTrigger(context)
        }

        // 用 goAsync 提高进程存活优先级；同时用唤醒锁保证 CPU 不休眠
        val pendingResult = goAsync()
        val reason = if (action == ACTION_TIME_SYNC_RETRY) "retry" else "alarm"
        TimeSyncRunner.runWithWakeLock(context, reason) {
            try {
                pendingResult.finish()
            } catch (e: Exception) {
                Log.w(TAG, "结束广播失败: ${e.message}")
            }
        }
    }
}
