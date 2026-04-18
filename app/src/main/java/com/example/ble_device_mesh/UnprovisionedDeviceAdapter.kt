package com.example.ble_device_mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode

class UnprovisionedDeviceAdapter(
    private val onDeviceClick: (UnprovisionedMeshNode) -> Unit
) : RecyclerView.Adapter<UnprovisionedDeviceAdapter.ViewHolder>() {
    
    private var devices = listOf<UnprovisionedMeshNode>()
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvUuid: TextView = view.findViewById(R.id.tvDeviceAddress)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.tvName.text = "未配网设备"
        holder.tvUuid.text = device.deviceUuid?.toString()?.substring(0, 8) ?: "无UUID"
        
        holder.itemView.setOnClickListener {
            onDeviceClick(device)
        }
    }
    
    override fun getItemCount() = devices.size
    
    fun updateDevices(newDevices: List<UnprovisionedMeshNode>) {
        devices = newDevices
        notifyDataSetChanged()
    }
}
