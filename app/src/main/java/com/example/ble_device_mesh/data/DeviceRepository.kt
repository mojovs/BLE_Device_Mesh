package com.example.ble_device_mesh.data
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DeviceRepository(context: Context) {
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("mesh_devices", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val groupRepository = GroupRepository(context)
    
    companion object {
        private const val KEY_DEVICES = "devices"
        private const val KEY_DELETED = "deleted_devices"
        private const val MAX_DELETED = 20
    }
    
    // 获取所有设备，按排序序号排列
    fun getAllDevices(): List<MeshDevice> {
        val json = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        val type = object : TypeToken<List<MeshDevice>>() {}.type
        val devices: List<MeshDevice> = gson.fromJson(json, type)
        return devices.sortedBy { it.sortOrder }
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
        val maxOrder = devices.maxOfOrNull { it.sortOrder } ?: 0
        devices.add(device.copy(sortOrder = maxOrder + 1))
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
    
    // 删除设备（先放进回收站，删错可恢复）
    fun deleteDevice(deviceId: String) {
        val devices = getAllDevices().toMutableList()
        devices.firstOrNull { it.id == deviceId }?.let { addToRecycleBin(it) }
        devices.removeAll { it.id == deviceId }
        saveDevices(devices)
        // 同步清理分组内的成员记录，避免分组残留已删除设备
        groupRepository.removeDeviceFromAllGroups(deviceId)
    }

    // 根据单播地址删除设备
    fun deleteDeviceByAddress(address: Int) {
        val devices = getAllDevices().toMutableList()
        devices.filter { it.address == address }.forEach { addToRecycleBin(it) }
        val removedIds = devices.filter { it.address == address }.map { it.id }
        devices.removeAll { it.address == address }
        saveDevices(devices)
        removedIds.forEach { groupRepository.removeDeviceFromAllGroups(it) }
    }

    // ---------------- 回收站：删除只移除本地列表，设备端仍在 Mesh 网络里 ----------------

    /** 最近删除的设备（用于一键恢复名称/地址/MAC） */
    fun getDeletedDevices(): List<MeshDevice> {
        val json = prefs.getString(KEY_DELETED, null) ?: return emptyList()
        val type = object : TypeToken<List<MeshDevice>>() {}.type
        return try {
            gson.fromJson<List<MeshDevice>>(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun addToRecycleBin(device: MeshDevice) {
        val list = getDeletedDevices().filterNot { it.id == device.id }.toMutableList()
        list.add(0, device)
        while (list.size > MAX_DELETED) list.removeAt(list.size - 1)
        prefs.edit().putString(KEY_DELETED, gson.toJson(list)).apply()
    }

    /** 从回收站恢复设备 */
    fun restoreDevice(device: MeshDevice) {
        addDevice(device)
        removeFromRecycleBin(device.id)
    }

    fun removeFromRecycleBin(deviceId: String) {
        val list = getDeletedDevices().filterNot { it.id == deviceId }
        prefs.edit().putString(KEY_DELETED, gson.toJson(list)).apply()
    }

    /** 设备端已真正解绑（Node Reset）时，回收站里的记录也不能再恢复，一并清掉 */
    fun purgeDeletedByAddress(address: Int) {
        val list = getDeletedDevices().filterNot { it.address == address }
        prefs.edit().putString(KEY_DELETED, gson.toJson(list)).apply()
    }

    
    // 根据 ID 获取设备
    fun getDeviceById(deviceId: String): MeshDevice? {
        return getAllDevices().find { it.id == deviceId }
    }
    
    // 重新排序设备
    fun reorderDevices(devices: List<MeshDevice>) {
        saveDevices(devices)
    }
    /** 记录某设备的固件版本（仅当地址匹配时更新） */
    fun updateFirmwareVersion(address: Int, version: String) {
        val devices = getAllDevices().toMutableList()
        var changed = false
        devices.forEach { d ->
            if (d.address == address && d.firmwareVersion != version) {
                d.firmwareVersion = version
                changed = true
            }
        }
        if (changed) saveDevices(devices)
    }

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
