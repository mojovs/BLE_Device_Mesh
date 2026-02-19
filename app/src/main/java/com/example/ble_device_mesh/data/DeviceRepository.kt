package com.example.ble_device_mesh.data
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DeviceRepository(context: Context) {
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("mesh_devices", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_DEVICES = "devices"
    }
    
    // 获取所有设备
    fun getAllDevices(): List<MeshDevice> {
        val json = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        val type = object : TypeToken<List<MeshDevice>>() {}.type
        return gson.fromJson(json, type)
    }
    
    // 保存设备列表
    private fun saveDevices(devices: List<MeshDevice>) {
        val json = gson.toJson(devices)
        prefs.edit().putString(KEY_DEVICES, json).apply()
    }
    
    // 添加设备
    fun addDevice(device: MeshDevice) {
        val devices = getAllDevices().toMutableList()
        // 检查是否已存在
        if (devices.any { it.id == device.id }) {
            return
        }
        devices.add(device)
        saveDevices(devices)
    }
    
    // 更新设备
    fun updateDevice(device: MeshDevice) {
        val devices = getAllDevices().toMutableList()
        val index = devices.indexOfFirst { it.id == device.id }
        if (index != -1) {
            devices[index] = device
            saveDevices(devices)
        }
    }
    
    // 删除设备
    fun deleteDevice(deviceId: String) {
        val devices = getAllDevices().toMutableList()
        devices.removeAll { it.id == deviceId }
        saveDevices(devices)
    }
    
    // 根据 ID 获取设备
    fun getDeviceById(deviceId: String): MeshDevice? {
        return getAllDevices().find { it.id == deviceId }
    }
    
    // 清除所有设备的温度数据
    fun clearAllTemperatures() {
        val devices = getAllDevices().toMutableList()
        var updated = false
        devices.forEach { 
            if (it.temperature != null) {
                it.temperature = null
                updated = true
            }
        }
        if (updated) {
            saveDevices(devices)
        }
    }
}
