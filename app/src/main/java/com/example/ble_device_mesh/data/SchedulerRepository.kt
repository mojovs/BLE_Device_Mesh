package com.example.ble_device_mesh.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 定时任务本地存储仓库
 * 使用 SharedPreferences + Gson 持久化
 * 按设备地址分组存储
 */
class SchedulerRepository(context: Context) {

    private val prefs = context.getSharedPreferences("SchedulerPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * 获取指定设备的所有任务
     */
    fun getTasks(deviceAddress: Int): List<SchedulerTask> {
        val key = "tasks_0x${deviceAddress.toString(16)}"
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SchedulerTask>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 保存指定设备的所有任务
     */
    fun saveTasks(deviceAddress: Int, tasks: List<SchedulerTask>) {
        val key = "tasks_0x${deviceAddress.toString(16)}"
        val json = gson.toJson(tasks)
        prefs.edit().putString(key, json).apply()
    }

    /**
     * 添加或更新任务
     */
    fun upsertTask(deviceAddress: Int, task: SchedulerTask) {
        val tasks = getTasks(deviceAddress).toMutableList()
        val existingIndex = tasks.indexOfFirst { it.index == task.index }
        if (existingIndex >= 0) {
            tasks[existingIndex] = task
        } else {
            tasks.add(task)
        }
        saveTasks(deviceAddress, tasks)
    }

    /**
     * 删除任务
     */
    fun deleteTask(deviceAddress: Int, taskIndex: Int) {
        val tasks = getTasks(deviceAddress).toMutableList()
        tasks.removeAll { it.index == taskIndex }
        saveTasks(deviceAddress, tasks)
    }

    /**
     * 切换任务启用状态
     */
    fun toggleTaskEnabled(deviceAddress: Int, taskIndex: Int): SchedulerTask? {
        val tasks = getTasks(deviceAddress).toMutableList()
        val idx = tasks.indexOfFirst { it.index == taskIndex }
        if (idx < 0) return null
        val updated = tasks[idx].copy(enabled = !tasks[idx].enabled)
        tasks[idx] = updated
        saveTasks(deviceAddress, tasks)
        return updated
    }

    /**
     * 获取下一个可用的任务索引 (0-15)
     */
    fun getNextAvailableIndex(deviceAddress: Int): Int {
        val usedIndices = getTasks(deviceAddress).map { it.index }.toSet()
        for (i in 0..15) {
            if (i !in usedIndices) return i
        }
        return -1 // 所有槽位已满
    }

    /**
     * 清除指定设备的所有任务
     */
    fun clearTasks(deviceAddress: Int) {
        val key = "tasks_0x${deviceAddress.toString(16)}"
        prefs.edit().remove(key).apply()
    }
}
