package com.example.ble_device_mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.MeshDevice

class MeshDeviceAdapter(
    private var devices: List<MeshDevice>,
    private val onDeviceClick: (MeshDevice) -> Unit,
    private val onDeleteClick: (MeshDevice) -> Unit
) : RecyclerView.Adapter<MeshDeviceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvDeviceAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        val tvBrightness: TextView = view.findViewById(R.id.tvBrightness)
        val tvTemperature: TextView = view.findViewById(R.id.tvTemperature)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mesh_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        
        // 从 SharedPreferences 读取保存的亮度
        val prefs = holder.itemView.context.getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        val savedBrightness = prefs.getInt("brightness_0x${device.address.toString(16)}", 0)
        device.brightness = savedBrightness
        
        holder.tvDeviceName.text = device.name
        holder.tvDeviceAddress.text = "地址: 0x${device.address.toString(16).uppercase().padStart(4, '0')}"
        holder.tvBrightness.text = "亮度: ${savedBrightness}%"
        
        // 显示温度（如果有）
        if (device.temperature != null) {
            holder.tvTemperature.visibility = View.VISIBLE
            holder.tvTemperature.text = "${String.format("%.1f", device.temperature)}°C"
        } else {
            holder.tvTemperature.visibility = View.GONE
        }
        
        // 点击整个项目进入详情
        holder.itemView.setOnClickListener {
            onDeviceClick(device)
        }
        
        // 长按删除
        holder.itemView.setOnLongClickListener {
            onDeleteClick(device)
            true
        }
    }

    override fun getItemCount() = devices.size

    fun updateDevices(newDevices: List<MeshDevice>) {
        devices = newDevices
        notifyDataSetChanged()
    }
}
