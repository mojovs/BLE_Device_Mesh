package com.example.ble_device_mesh.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class GroupRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mesh_groups", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_GROUPS = "groups"
        private const val KEY_LAST_ADDR = "last_group_address"
        const val MIN_GROUP_ADDRESS = 0xC000
        const val MAX_GROUP_ADDRESS = 0xCFFF
    }

    fun getAllGroups(): List<MeshGroup> {
        val json = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        val type = object : TypeToken<List<MeshGroup>>() {}.type
        return gson.fromJson(json, type)
    }

    fun getGroupById(id: String): MeshGroup? = getAllGroups().find { it.id == id }

    fun getGroupByAddress(address: Int): MeshGroup? = getAllGroups().find { it.address == address }

    private fun saveGroups(groups: List<MeshGroup>) {
        prefs.edit().putString(KEY_GROUPS, gson.toJson(groups)).apply()
    }

    fun addGroup(group: MeshGroup) {
        val groups = getAllGroups().toMutableList()
        if (groups.any { it.id == group.id }) return
        groups.add(group)
        saveGroups(groups)
    }

    fun updateGroup(group: MeshGroup) {
        val groups = getAllGroups().toMutableList()
        val index = groups.indexOfFirst { it.id == group.id }
        if (index != -1) {
            groups[index] = group
            saveGroups(groups)
        }
    }

    fun deleteGroup(id: String) {
        val groups = getAllGroups().toMutableList()
        groups.removeAll { it.id == id }
        saveGroups(groups)
    }

    fun getGroupsForDevice(deviceId: String): List<MeshGroup> {
        return getAllGroups().filter { deviceId in it.memberDeviceIds }
    }

    /**
     * 从所有分组中移除某个设备（设备被删除/重置时调用），
     * 避免 memberDeviceIds 残留导致分组"看起来有成员、实际全是幽灵设备"。
     */
    fun removeDeviceFromAllGroups(deviceId: String) {
        val groups = getAllGroups()
        val affected = groups.filter { deviceId in it.memberDeviceIds }
        if (affected.isEmpty()) return

        val updated = groups.map { group ->
            if (deviceId in group.memberDeviceIds) {
                group.copy(memberDeviceIds = group.memberDeviceIds - deviceId)
            } else group
        }
        saveGroups(updated)
        Log.d("GroupRepository", "从 ${affected.size} 个分组中移除设备 $deviceId")
    }

    fun allocateGroupAddress(): Int {
        val existing = getAllGroups().map { it.address }.toSet()
        var addr = prefs.getInt(KEY_LAST_ADDR, MIN_GROUP_ADDRESS)
        if (addr !in existing) return addr
        for (i in 0..(MAX_GROUP_ADDRESS - MIN_GROUP_ADDRESS)) {
            addr = MIN_GROUP_ADDRESS + i
            if (addr !in existing) {
                prefs.edit().putInt(KEY_LAST_ADDR, addr).apply()
                return addr
            }
        }
        Log.w("GroupRepository", "No available group addresses in 0xC000-0xCFFF")
        return MAX_GROUP_ADDRESS
    }
}
