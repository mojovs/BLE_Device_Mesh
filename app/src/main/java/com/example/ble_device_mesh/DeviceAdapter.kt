package com.example.ble_device_mesh

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private var devices: List<ScanResult>,
    private val onDeviceClick: (ScanResult) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvDeviceAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        val tvDeviceRssi: TextView = view.findViewById(R.id.tvDeviceRssi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        
        holder.tvDeviceName.text = device.device.name ?: "未知设备"
        holder.tvDeviceAddress.text = device.device.address
        holder.tvDeviceRssi.text = "信号强度: ${device.rssi} dBm"
        
        holder.itemView.setOnClickListener {
            onDeviceClick(device)
        }
    }

    override fun getItemCount() = devices.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateDevices(newDevices: List<ScanResult>) {
        devices = newDevices
        notifyDataSetChanged()
    }
}
