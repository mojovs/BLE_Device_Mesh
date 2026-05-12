package com.example.ble_device_mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.GroupRepository
import com.example.ble_device_mesh.data.MeshDevice

class MeshDeviceAdapter(
    private var devices: MutableList<MeshDevice>,
    private val onDeviceClick: (MeshDevice) -> Unit,
    private val onBrightnessChange: (MeshDevice, Int) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<MeshDeviceAdapter.ViewHolder>() {

    fun swapItems(from: Int, to: Int) {
        val item = devices.removeAt(from)
        devices.add(to, item)
        notifyItemMoved(from, to)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivDeviceIcon: ImageView = view.findViewById(R.id.ivDeviceIcon)
        val layoutOnlineIndicator: View = view.findViewById(R.id.layoutOnlineIndicator)
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvDeviceGroup: TextView = view.findViewById(R.id.tvDeviceGroup)
        val seekBarBrightness: SeekBar = view.findViewById(R.id.seekBarQuickBrightness)
        val tvBrightness: TextView = view.findViewById(R.id.tvBrightness)
        val tvTemperature: TextView = view.findViewById(R.id.tvTemperature)
        val tvCardTemperature: TextView = view.findViewById(R.id.tvCardTemperature)
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

        // 亮度滑条控制
        holder.seekBarBrightness.progress = savedBrightness
        holder.seekBarBrightness.setOnSeekBarChangeListener(null) // 避免复用错乱
        holder.seekBarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    holder.tvBrightness.text = "$progress%"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: return
                prefs.edit().putInt("brightness_0x${device.address.toString(16)}", progress).apply()
                onBrightnessChange(device, progress)
            }
        })

        // 底部温度显示（替换原分组按钮）
        holder.tvCardTemperature.visibility = View.VISIBLE
        val temp = device.temperature
        if (temp != null) {
            holder.tvCardTemperature.text = "${String.format("%.1f", temp)} °C"
            holder.tvCardTemperature.setTextColor(
                when {
                    temp > 30f -> android.graphics.Color.parseColor("#FF3B30")     // 红色
                    temp > 20f -> android.graphics.Color.parseColor("#8BC34A")     // 草绿色
                    temp > 10f -> android.graphics.Color.parseColor("#FFD600")     // 荧黄色
                    else       -> android.graphics.Color.parseColor("#4FC3F7")     // 冰色
                }
            )
        } else {
            holder.tvCardTemperature.text = "-- °C"
            holder.tvCardTemperature.setTextColor(android.graphics.Color.parseColor("#6B7280"))
        }

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

    fun getDevices(): MutableList<MeshDevice> = devices

    fun updateDevices(newDevices: List<MeshDevice>) {
        devices = newDevices.toMutableList()
        notifyDataSetChanged()
    }
}
