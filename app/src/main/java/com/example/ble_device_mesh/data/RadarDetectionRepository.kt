package com.example.ble_device_mesh.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 雷达检测历史本地存储仓库
 * 使用 SharedPreferences + Gson 持久化
 * 按设备地址分组存储，检测时间永不清除（app 端永久保留）
 */
class RadarDetectionRepository(context: Context) {

    private val prefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * 检测记录
     */
    data class DetectionRecord(
        val date: String,          // 日期 "2026-05-17"
        val hour: Int,             // 小时 0-23
        val minute: Int,           // 分钟 0-59
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 雷达模式配置（本地记忆）
     */
    data class RadarConfig(
        val enabled: Boolean = false,
        val nightDurationMin: Float = 3.0f,  // 夜晚亮灯时长（分钟）
        val nightStartHour: Int = 18,
        val nightEndHour: Int = 6
    )

    /**
     * 获取指定设备的所有检测记录
     */
    fun getRecords(deviceAddress: Int): List<DetectionRecord> {
        val key = "radar_records_0x${deviceAddress.toString(16)}"
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DetectionRecord>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 导入设备端当天的检测记录（合并去重）
     * 固件只返回当天记录，app 端合并到本地历史中
     */
    fun importDeviceRecords(deviceAddress: Int, date: String, times: List<Pair<Int, Int>>) {
        val existing = getRecords(deviceAddress).toMutableList()
        for ((hour, minute) in times) {
            // 去重：同设备同日期同时分的不重复添加
            val exists = existing.any { it.date == date && it.hour == hour && it.minute == minute }
            if (!exists) {
                existing.add(DetectionRecord(date = date, hour = hour, minute = minute))
            }
        }
        saveRecords(deviceAddress, existing)
    }

    /**
     * 保存指定设备的所有记录
     */
    private fun saveRecords(deviceAddress: Int, records: List<DetectionRecord>) {
        val key = "radar_records_0x${deviceAddress.toString(16)}"
        val json = gson.toJson(records)
        prefs.edit().putString(key, json).apply()
    }

    /**
     * 获取雷达模式本地配置
     */
    fun getConfig(deviceAddress: Int): RadarConfig {
        val key = "radar_config_0x${deviceAddress.toString(16)}"
        val json = prefs.getString(key, null) ?: return RadarConfig()
        return try {
            val type = object : TypeToken<RadarConfig>() {}.type
            gson.fromJson(json, type) ?: RadarConfig()
        } catch (e: Exception) {
            RadarConfig()
        }
    }

    /**
     * 保存雷达模式本地配置
     */
    fun saveConfig(deviceAddress: Int, config: RadarConfig) {
        val key = "radar_config_0x${deviceAddress.toString(16)}"
        val json = gson.toJson(config)
        prefs.edit().putString(key, json).apply()
    }
}
