package com.example.ble_device_mesh.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class StreetlightPresetRepository(context: Context) {

    private val prefs = context.getSharedPreferences("StreetlightPresetPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getPresets(): List<StreetlightPreset> {
        val json = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<StreetlightPreset>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePreset(preset: StreetlightPreset) {
        val presets = getPresets().toMutableList()
        val existingIndex = presets.indexOfFirst { it.name == preset.name }
        if (existingIndex >= 0) {
            presets[existingIndex] = preset
        } else {
            presets.add(preset)
        }
        savePresets(presets)
    }

    fun deletePreset(name: String) {
        savePresets(getPresets().filterNot { it.name == name })
    }

    private fun savePresets(presets: List<StreetlightPreset>) {
        prefs.edit().putString(KEY_PRESETS, gson.toJson(presets)).apply()
    }

    companion object {
        private const val KEY_PRESETS = "presets"
    }
}
