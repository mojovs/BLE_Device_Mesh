package com.example.ble_device_mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.GroupRepository
import com.example.ble_device_mesh.data.MeshDevice

class MeshDeviceAdapter(
    private var devices: MutableList<MeshDevice>,
    private val onDeviceClick: (MeshDevice) -> Unit
) : RecyclerView.Adapter<MeshDeviceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivDeviceIcon: ImageView = view.findViewById(R.id.ivDeviceIcon)
        val layoutOnlineIndicator: View = view.findViewById(R.id.layoutOnlineIndicator)
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvDeviceGroup: TextView = view.findViewById(R.id.tvDeviceGroup)
        val tvBrightness: TextView = view.findViewById(R.id.tvBrightness)
        val tvTemperature: TextView = view.findViewById(R.id.tvTemperature)
        val tvLightStatus: TextView = view.findViewById(R.id.tvLightStatus)
        val tvScheduleStatus: TextView = view.findViewById(R.id.tvScheduleStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mesh_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        val ctx = holder.itemView.context

        val prefs = ctx.getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        val savedBrightness = prefs.getInt("brightness_0x${device.address.toString(16)}", 0)
        device.brightness = savedBrightness

        holder.tvDeviceName.text = device.name
        holder.tvBrightness.text = "${savedBrightness}%"

        holder.tvTemperature.visibility = if (device.temperature != null) View.VISIBLE else View.GONE
        device.temperature?.let { holder.tvTemperature.text = "${String.format("%.1f", it)}°C" }

        // 在线状态指示
        holder.layoutOnlineIndicator.visibility = if (device.isOnline) View.VISIBLE else View.GONE

        // 分组名称
        val groupRepo = GroupRepository(ctx)
        val groupNames = device.groupIds?.mapNotNull { groupRepo.getGroupById(it)?.name } ?: emptyList()
        holder.tvDeviceGroup.text = if (groupNames.isNotEmpty()) groupNames.joinToString("、") else "未分组"

        if (device.type == com.example.ble_device_mesh.data.DeviceType.LIGHT) {
            holder.tvLightStatus.visibility = View.VISIBLE
            holder.tvLightStatus.text = if (savedBrightness > 0) "💡" else "🌑"
        } else {
            holder.tvLightStatus.visibility = View.GONE
        }

        val schedulePrefs = ctx.getSharedPreferences("SchedulePrefs", android.content.Context.MODE_PRIVATE)
        val key = "device_${device.address}"
        val hasSchedule = schedulePrefs.contains("${key}_on") || schedulePrefs.contains("${key}_off")
        holder.tvScheduleStatus.visibility = if (hasSchedule) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            onDeviceClick(device)
        }
    }

    override fun getItemCount() = devices.size

    fun updateDevices(newDevices: List<MeshDevice>) {
        devices = newDevices.toMutableList()
        notifyDataSetChanged()
    }
}
