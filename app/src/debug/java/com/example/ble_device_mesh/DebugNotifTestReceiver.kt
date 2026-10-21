package com.example.ble_device_mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 【临时调试用，验证完删除】
 *
 * 同时发 4 条不同配置的通知，判断 MIUI 通知助手到底拦截哪一类：
 *  2101 ongoing=true
 *  2102 ongoing=false
 *  2103 ongoing=false + 渠道带默认提示音
 *  2104 ongoing=false + 只有标题文字（无 autoCancel / 无 onlyAlertOnce）
 */
class DebugNotifTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf("dbg_ch_silent", "dbg_ch_sound").forEach { id ->
                if (manager.getNotificationChannel(id) == null) {
                    val channel = NotificationChannel(
                        id,
                        id,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        if (id == "dbg_ch_silent") setSound(null, null)
                        enableVibration(false)
                    }
                    manager.createNotificationChannel(channel)
                }
            }
        }

        fun build(id: Int, channel: String, ongoing: Boolean, plain: Boolean): Notification {
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, channel)
            } else {
                Notification.Builder(context)
            }
            b.setContentTitle("测试通知 $id")
                .setContentText("ongoing=$ongoing")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(ongoing)
            if (!plain) {
                b.setAutoCancel(!ongoing).setOnlyAlertOnce(true)
            }
            return b.build()
        }

        manager.notify(2101, build(2101, "dbg_ch_silent", ongoing = true, plain = false))
        manager.notify(2102, build(2102, "dbg_ch_silent", ongoing = false, plain = false))
        manager.notify(2103, build(2103, "dbg_ch_sound", ongoing = false, plain = false))
        manager.notify(2104, build(2104, "dbg_ch_silent", ongoing = false, plain = true))
        Log.d("MeshTimeSync", "已发送 4 条测试通知 2101/2102/2103/2104")
    }
}
