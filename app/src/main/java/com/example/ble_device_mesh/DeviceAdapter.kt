package com.example.ble_device_mesh

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.DeviceRepository

class DeviceAdapter(
    private var devices: List<ScanResult>,
    private val onDeviceClick: (ScanResult) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvDeviceAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        val tvDeviceRssi: TextView = view.findViewById(R.id.tvDeviceRssi)
    }

    // MAC -> 设备卡片名称（扫描结果里已知设备直接显示名字，不用"未知设备"）
    private var macToName: Map<String, String>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]

        val mac = try {
            device.device.address ?: ""
        } catch (e: Exception) {
            ""
        }
        val knownName = resolveNameTable(holder.itemView.context)[mac.uppercase()]

        holder.tvDeviceName.text = knownName
            ?: device.device.name
            ?: "未知设备"
        holder.tvDeviceAddress.text = mac
        holder.tvDeviceRssi.text = "信号强度: ${device.rssi} dBm"

        holder.itemView.setOnClickListener {
            onDeviceClick(device)
        }
    }

    override fun getItemCount() = devices.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateDevices(newDevices: List<ScanResult>) {
        devices = newDevices
        macToName = null  // 设备可能刚改名/刚添加，重新读一次
        notifyDataSetChanged()
    }

    /** 用设备卡片里的名称（含 DevicePrefs 历史 MAC）建一次 MAC→名称 表 */
    private fun resolveNameTable(context: Context): Map<String, String> {
        macToName?.let { return it }
        val map = HashMap<String, String>()
        try {
            val repo = DeviceRepository(context.applicationContext)
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            for (device in repo.getAllDevices()) {
                device.bluetoothMac?.takeIf { it.isNotBlank() }?.let {
                    map[it.uppercase()] = device.name
                }
                prefs.getString("device_mac_0x${device.address.toString(16)}", null)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { map[it.uppercase()] = device.name }
            }
        } catch (e: Exception) {
            // 读取失败就退回广播名称
        }
        macToName = map
        return map
    }
}
