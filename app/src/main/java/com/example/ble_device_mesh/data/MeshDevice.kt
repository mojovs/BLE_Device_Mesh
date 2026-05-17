package com.example.ble_device_mesh.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MeshDevice(
    val id: String,  // 唯一标识
    var name: String,  // 设备名称
    val address: Int,  // Mesh Unicast 地址 (例如 0x0099)
    val type: DeviceType,  // 设备类型
    var groupAddress: Int? = null,  // Group 地址 (例如 0x0200)，用于控制设备
    var groupIds: MutableList<String>? = null,  // 所属分组 ID 列表
    var bluetoothMac: String? = null,  // BLE MAC 地址 (例如 D4:8A:FC:12:34:56)
    var brightness: Int = 50,  // 当前亮度 0-100
    var temperature: Float? = null,  // 当前温度（摄氏度）
    var deviceTime: Long? = null,  // 设备时间（Unix 时间戳，秒）
    var lightLevel: Float? = null,  // 当前光照度百分比（0-100%）
    var isOnline: Boolean = false,  // 是否在线
    val addedTime: Long = System.currentTimeMillis(),  // 添加时间
    var sortOrder: Int = 0,  // 排序序号，用于自定义排列
    var hourlyChimeEnabled: Boolean = false,  // 整点报时开关
    var buzzerVolume: Int = 50,  // 蜂鸣器音量 0-100
    var radarEnabled: Boolean = false,  // 雷达检测开关
    var radarNightDurationX10: Int = 30,  // 夜晚亮灯时长 (×0.1分钟), 默认3.0分钟
    var radarNightStartHour: Int = 18,  // 夜晚开始小时
    var radarNightEndHour: Int = 6  // 夜晚结束小时
) : Parcelable

enum class DeviceType {
    LIGHT,  // 灯光
    SWITCH,  // 开关
    SENSOR,  // 传感器
    OTHER   // 其他
}
