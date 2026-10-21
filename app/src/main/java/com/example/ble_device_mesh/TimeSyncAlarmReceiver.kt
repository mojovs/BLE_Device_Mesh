package com.example.ble_device_mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 时间同步闹钟的接收器。
 *
 * 到点后先排下一次闹钟（保证即使本次失败，后续节奏也不会断），
 * 再持唤醒锁跑一次同步：临时连 proxy → 下发 TimeSet → 复检 → 断开。
 */
class TimeSyncAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MeshTimeSync"
        const val ACTION_TIME_SYNC_ALARM = "com.example.ble_device_mesh.TIME_SYNC_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "闹钟触发: ${intent.action}")

        // 1. 先排下一次，避免同步过程异常导致后续中断
        TimeSyncScheduler.rescheduleAfterTrigger(context)

        // 2. 用 goAsync 提高进程存活优先级；同时用唤醒锁保证 CPU 不休眠
        val pendingResult = goAsync()
        TimeSyncRunner.runWithWakeLock(context, "alarm") {
            try {
                pendingResult.finish()
            } catch (e: Exception) {
                Log.w(TAG, "结束广播失败: ${e.message}")
            }
        }
    }
}
