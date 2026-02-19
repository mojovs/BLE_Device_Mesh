package com.example.ble_device_mesh.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MeshDevice(
    val id: String,  // 唯一标识
    val name: String,  // 设备名称
    val address: Int,  // Mesh 地址 (例如 0x0005)
    val type: DeviceType,  // 设备类型
    var brightness: Int = 50,  // 当前亮度 0-100
    var temperature: Float? = null,  // 当前温度（摄氏度）
    var isOnline: Boolean = false,  // 是否在线
    val addedTime: Long = System.currentTimeMillis()  // 添加时间
) : Parcelable

enum class DeviceType {
    LIGHT,  // 灯光
    SWITCH,  // 开关
    SENSOR,  // 传感器
    OTHER   // 其他
}
