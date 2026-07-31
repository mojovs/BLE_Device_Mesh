package com.example.ble_device_mesh.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class StreetlightPreset(
    val name: String,
    val controlPoints: List<StreetlightProfile.ControlPoint>,
    val createdAt: Long
) : Parcelable {
    fun getDescription(): String {
        if (controlPoints.isEmpty()) return "未设置"
        return controlPoints.sortedBy { it.toMinutes() }.joinToString(" → ") {
            "${it.getTimeString()} ${it.brightness}%"
        }
    }
}
