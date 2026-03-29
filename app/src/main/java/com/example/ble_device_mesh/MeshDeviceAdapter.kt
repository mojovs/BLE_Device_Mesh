package com.example.ble_device_mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.MeshDevice
import java.util.Collections

class MeshDeviceAdapter(
    private var devices: MutableList<MeshDevice>,
    private val onDeviceClick: (MeshDevice) -> Unit,
    private val onDeleteClick: (MeshDevice) -> Unit,
    private val onOrderChanged: (List<MeshDevice>) -> Unit
) : RecyclerView.Adapter<MeshDeviceAdapter.ViewHolder>() {

    private var itemTouchHelper: ItemTouchHelper? = null

    fun attachToRecyclerView(rv: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                Collections.swap(devices, from, to)
                notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                onOrderChanged(devices.toList())
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(rv) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceIcon: TextView = view.findViewById(R.id.tvDeviceIcon)
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvDeviceAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
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

        val prefs = holder.itemView.context.getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        val savedBrightness = prefs.getInt("brightness_0x${device.address.toString(16)}", 0)
        device.brightness = savedBrightness

        holder.tvDeviceIcon.text = when (device.type) {
            com.example.ble_device_mesh.data.DeviceType.LIGHT -> "💡"
            com.example.ble_device_mesh.data.DeviceType.SWITCH -> "🔌"
            com.example.ble_device_mesh.data.DeviceType.SENSOR -> "🌡"
            com.example.ble_device_mesh.data.DeviceType.OTHER -> "📦"
        }
        holder.tvDeviceName.text = device.name
        holder.tvDeviceAddress.text = "地址: 0x${device.address.toString(16).uppercase().padStart(4, '0')}"
        holder.tvBrightness.text = "亮度: ${savedBrightness}%"

        if (device.temperature != null) {
            holder.tvTemperature.visibility = View.VISIBLE
            holder.tvTemperature.text = "${String.format("%.1f", device.temperature)}°C"
        } else {
            holder.tvTemperature.visibility = View.GONE
        }

        if (device.type == com.example.ble_device_mesh.data.DeviceType.LIGHT) {
            holder.tvLightStatus.visibility = View.VISIBLE
            holder.tvLightStatus.text = if (savedBrightness > 0) "💡" else "🌑"
        } else {
            holder.tvLightStatus.visibility = View.GONE
        }

        val schedulePrefs = holder.itemView.context.getSharedPreferences("SchedulePrefs", android.content.Context.MODE_PRIVATE)
        val key = "device_${device.address}"
        val hasSchedule = schedulePrefs.contains("${key}_on") || schedulePrefs.contains("${key}_off")
        holder.tvScheduleStatus.visibility = if (hasSchedule) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onDeviceClick(device) }
        holder.itemView.setOnLongClickListener {
            onDeleteClick(device)
            true
        }
    }

    override fun getItemCount() = devices.size

    fun updateDevices(newDevices: List<MeshDevice>) {
        devices = newDevices.toMutableList()
        notifyDataSetChanged()
    }
}
