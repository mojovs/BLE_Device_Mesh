package com.example.ble_device_mesh.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MeshGroup(
    val id: String,
    var name: String,
    val address: Int,
    val memberDeviceIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable
