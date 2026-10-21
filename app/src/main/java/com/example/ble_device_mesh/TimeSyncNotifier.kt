package com.example.ble_device_mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 时间同步的通知（普通通知，不需要前台服务）。
 *
 * 全部使用同一个 notification id 覆盖，不会在通知栏堆积；
 * 成功时自动消失，失败时保留供用户点击查看。
 */
object TimeSyncNotifier {

    private const val TAG = "MeshTimeSync"
    private const val CHANNEL_ID = "time_sync_channel_v2"
    /** 早期版本用过的渠道（IMPORTANCE_LOW，状态栏看不到图标） */
    private const val LEGACY_CHANNEL_ID = "time_sync_channel"
    private const val NOTIFICATION_ID = 2001
    private const val AUTO_DISMISS_MS = 20_000L
    private const val KEEP_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        // 旧渠道重要性改不了，直接删掉换新 id
        try {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        } catch (e: Exception) {
            Log.w(TAG, "删除旧通知渠道失败: ${e.message}")
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "时间同步",
            // DEFAULT 才会在状态栏显示图标；再用 setSound(null) + 关震动保证半夜不打扰
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "BLE Mesh 设备时间自动同步的失败提醒"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
        Log.d(TAG, "已创建通知渠道 $CHANNEL_ID")
    }

    /**
     * 结果提示。
     *
     * 注意：MIUI 的“通知过滤”会把本应用的普通通知直接拦掉（日志里是
     * "notification blocked by assistant request"），只有 ongoing 的能稳定显示，
     * 所以这里统一按 ongoing 发，再定时主动取消，避免留在通知栏里删不掉。
     */
    fun notifyResult(context: Context, title: String, text: String, keep: Boolean) {
        notify(context, title, text, ongoing = true, bigText = text)
        val delay = if (keep) KEEP_MS else AUTO_DISMISS_MS
        handler.postDelayed({ cancel(context) }, delay)
    }

    fun cancel(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "取消通知失败: ${e.message}")
        }
    }

    private fun notify(
        context: Context,
        title: String,
        text: String,
        ongoing: Boolean,
        bigText: String? = null
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setContentIntent(contentIntent)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            builder.setPriority(Notification.PRIORITY_LOW)
        }
        if (bigText != null) {
            builder.setStyle(Notification.BigTextStyle().bigText(bigText))
        }

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "发送通知失败: ${e.message}")
        }
    }
}
