package com.example.ble_device_mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getIntExtra("device_address", -1)
        val turnOn = intent.getBooleanExtra("turn_on", true)
        val brightness = intent.getIntExtra("brightness", 100)

        if (address == -1) return

        Log.d("ScheduleReceiver", "定时触发: address=0x${address.toString(16)}, turnOn=$turnOn")

        // 通过全局 MeshState 发送指令（不需要连接 ViewModel）
        try {
            val network = MeshViewModel.MeshState.meshNetWork ?: return
            val appKey = network.appKeys.firstOrNull() ?: return

            val level = if (turnOn) {
                ((brightness - 50) * 655.35).toInt()
            } else {
                -32768
            }

            val message = no.nordicsemi.android.mesh.transport.GenericLevelSetUnacknowledged(
                appKey, level, MeshViewModel.MeshState.currentTid
            )
            MeshViewModel.MeshState.currentTid++
            MeshViewModel.MeshState.meshManagerApi.createMeshPdu(address, message)
            Log.d("ScheduleReceiver", "已发送${if (turnOn) "开机" else "关机"}指令")
        } catch (e: Exception) {
            Log.e("ScheduleReceiver", "发送指令失败: ${e.message}")
        }
    }
}
