package com.example.ble_device_mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 改时间 / 换时区 / 应用升级后重新排布时间同步闹钟。
 *
 * 这些事件都会让原来的 AlarmManager 闹钟失效：
 *  - 手机重启后所有闹钟被清空
 *  - 用户手动改系统时间后原来的触发时刻不再对应 2:00 / 5:00 …
 *  - 换时区后本地整点偏移
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.d("MeshTimeSync", "收到 $action，重排时间同步闹钟")
                TimeSyncScheduler.ensureScheduled(context)
            }
            else -> Log.d("MeshTimeSync", "忽略广播 $action")
        }
    }
}
