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

    init {
        migrateBrightnessToUiIfNeeded()
    }

    /**
     * 旧版本把 OC6701 补偿后的设备亮度直接存进了本地缓存，
     * 新版本统一按 UI 百分比存储。这里做一次性转换，
     * 否则旧任务被再次下发时会被二次映射导致灯变暗。
     */
    private fun migrateBrightnessToUiIfNeeded() {
        if (prefs.getBoolean(KEY_BRIGHTNESS_MIGRATED, false)) return

        val type = object : TypeToken<List<SchedulerTask>>() {}.type
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(TASK_KEY_PREFIX) || value !is String) return@forEach
            try {
                val tasks = gson.fromJson<List<SchedulerTask>>(value, type) ?: return@forEach
                val migrated = tasks.map {
                    it.copy(brightness = BrightnessMap.unmap(it.brightness))
                }
                editor.putString(key, gson.toJson(migrated))
            } catch (_: Exception) {
                // 解析失败的任务保持原样，下次从设备读取时会覆盖
            }
        }
        editor.putBoolean(KEY_BRIGHTNESS_MIGRATED, true).apply()
    }

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

    private companion object {
        const val TASK_KEY_PREFIX = "tasks_0x"
        const val KEY_BRIGHTNESS_MIGRATED = "brightness_migrated_to_ui_v1"
    }
}
