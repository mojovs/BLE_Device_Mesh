package com.example.ble_device_mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.ble_device_mesh.data.DeviceRepository

/**
 * 时间同步服务
 * 每小时自动同步手机时间到所有已配网设备
 */
class TimeSyncService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val syncInterval = 60 * 60 * 1000L // 1小时
    private var syncCount = 0

    companion object {
        private const val TAG = "TimeSyncService"
        private const val CHANNEL_ID = "time_sync_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, TimeSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TimeSyncService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("时间同步服务运行中"))

        // 立即同步一次
        syncTimeToAllDevices()

        // 定时同步
        handler.postDelayed(object : Runnable {
            override fun run() {
                syncTimeToAllDevices()
                handler.postDelayed(this, syncInterval)
            }
        }, syncInterval)

        Log.d(TAG, "时间同步服务已启动")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "时间同步服务已停止")
    }

    private fun syncTimeToAllDevices() {
        val deviceRepository = DeviceRepository(this)
        val devices = deviceRepository.getAllDevices()

        if (devices.isEmpty()) {
            Log.d(TAG, "没有设备需要同步")
            return
        }

        val isConnected = MeshViewModel.MeshState.isConnected.value == true
        if (!isConnected) {
            Log.w(TAG, "未连接设备，跳过时间同步")
            return
        }

        syncCount++
        Log.d(TAG, "开始第 $syncCount 次时间同步，共 ${devices.size} 个设备")

        // 逐个设备同步时间（间隔500ms避免消息冲突）
        devices.forEachIndexed { index, device ->
            handler.postDelayed({
                // 使用 MeshState 直接访问，避免创建 ViewModel 实例
                val network = MeshViewModel.MeshState.meshNetWork
                val appKey = network?.appKeys?.firstOrNull()
                if (appKey != null) {
                    // Unix 时间戳（从 1970-01-01 开始）
                    val unixSeconds = System.currentTimeMillis() / 1000

                    // 转换为 TAI 时间（从 2000-01-01 00:00:00 UTC 开始）
                    // 2000-01-01 00:00:00 UTC = 946684800 Unix 秒
                    // TAI-UTC 偏移 = 37 秒（2024年）
                    val tai2000Epoch = 946684800L
                    val taiUtcDelta: Short = 37
                    val taiSeconds = (unixSeconds - tai2000Epoch + taiUtcDelta).toInt()

                    val taiTime = no.nordicsemi.android.mesh.MeshTAITime(
                        taiSeconds, 0, 0, false, taiUtcDelta, 32
                    )
                    val message = no.nordicsemi.android.mesh.transport.TimeSet(appKey, taiTime)
                    try {
                        MeshViewModel.MeshState.meshManagerApi.createMeshPdu(device.address, message)
                        Log.d(TAG, "已同步时间到设备: ${device.name} (0x${device.address.toString(16)})")
                    } catch (e: Exception) {
                        Log.e(TAG, "同步时间到 ${device.name} 失败: ${e.message}")
                    }
                }
            }, (index * 500).toLong())
        }

        // 更新通知
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID,
            createNotification("已同步 $syncCount 次 | ${devices.size} 个设备"))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "时间同步服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "自动同步时间到BLE Mesh设备"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("BLE Mesh 时间同步")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("BLE Mesh 时间同步")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .build()
        }
    }
}
