package com.example.ble_device_mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ble_device_mesh.data.DeviceRepository
import com.example.ble_device_mesh.data.DeviceType
import com.example.ble_device_mesh.data.MeshDevice

/**
 * 仅 debug 构建：通过 adb 增删设备元数据（DeviceRepository），用于恢复本地设备列表。
 *
 * 添加：
 *   adb shell am broadcast -a com.example.ble_device_mesh.DEBUG_DEVICE \
 *     --es op add --es name 客厅 --ei address 75 --es mac 54:14:A7:7D:4C:ED
 * 删除：
 *   adb shell am broadcast -a com.example.ble_device_mesh.DEBUG_DEVICE \
 *     --es op delete --ei address 49
 * 打印：
 *   adb shell am broadcast -a com.example.ble_device_mesh.DEBUG_DEVICE --es op dump
 *
 * 验证完成后应删除本文件及 debug Manifest 中的对应条目。
 */
class DebugDeviceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repo = DeviceRepository(context)
        val op = intent.getStringExtra("op") ?: "dump"

        when (op) {
            "add" -> {
                val address = intent.getIntExtra("address", -1)
                if (address <= 0) {
                    Log.e("MeshApp", "DEBUG_DEVICE 地址无效")
                    return
                }
                val name = intent.getStringExtra("name") ?: "0x${address.toString(16)}"
                val mac = intent.getStringExtra("mac")
                val device = MeshDevice(
                    id = "mesh_$address",
                    name = name,
                    address = address,
                    type = DeviceType.LIGHT,
                    brightness = 0,
                    groupAddress = 0,
                    bluetoothMac = mac
                )
                repo.addDevice(device)
                Log.d("MeshApp", "DEBUG_DEVICE 已添加: $name 0x${address.toString(16)} mac=$mac")
            }
            "delete" -> {
                val address = intent.getIntExtra("address", -1)
                repo.deleteDeviceByAddress(address)
                Log.d("MeshApp", "DEBUG_DEVICE 已删除: 0x${address.toString(16)}")
            }
        }

        val devices = repo.getAllDevices()
        Log.d("MeshApp", "DEBUG_DEVICE 当前设备 ${devices.size} 台:")
        devices.forEach {
            Log.d("MeshApp", "DEBUG_DEVICE   ${it.name} 0x${it.address.toString(16)} ${it.bluetoothMac}")
        }
    }
}
