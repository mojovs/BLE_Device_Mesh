package com.example.ble_device_mesh

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.MotionEvent
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
    private var isEditMode = false
    var onEditModeChanged: ((Boolean) -> Unit)? = null
    // 垃圾桶 View，由 Activity 设置
    var trashZoneView: View? = null

    fun setEditMode(enabled: Boolean) {
        if (isEditMode == enabled) return
        isEditMode = enabled
        onEditModeChanged?.invoke(enabled)
        notifyDataSetChanged()
    }

    fun isInEditMode() = isEditMode

    fun attachToRecyclerView(rv: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            private var draggingDevice: MeshDevice? = null
            // 实时记录拖动中卡片的屏幕中心坐标
            private var lastDragCenterX = 0f
            private var lastDragCenterY = 0f
            private var hasDragged = false

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                if (from < 0 || to < 0) return false
                Collections.swap(devices, from, to)
                notifyItemMoved(from, to)
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    draggingDevice = devices.getOrNull(viewHolder.adapterPosition)
                    hasDragged = false
                    trashZoneView?.setBackgroundColor(0xDDCC0000.toInt())
                } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    trashZoneView?.setBackgroundColor(0xBBEE3333.toInt())
                }
            }

            override fun onChildDraw(
                c: android.graphics.Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                    val view = vh.itemView
                    // 用屏幕绝对坐标记录卡片中心
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    lastDragCenterX = loc[0] + view.width / 2f
                    lastDragCenterY = loc[1] + view.height / 2f
                    if (Math.abs(dX) > 10 || Math.abs(dY) > 10) hasDragged = true
                }
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                val trash = trashZoneView
                val device = draggingDevice
                var hitTrash = false

                if (trash != null && device != null && hasDragged) {
                    val trashRect = Rect()
                    trash.getGlobalVisibleRect(trashRect)

                    val screenX = lastDragCenterX.toInt()
                    val screenY = lastDragCenterY.toInt()

                    android.util.Log.d("TrashDrop", "trashRect=$trashRect  cardCenter=($screenX,$screenY)")
                    hitTrash = trashRect.contains(screenX, screenY)
                }

                super.clearView(rv, vh)
                trashZoneView?.setBackgroundColor(0xBBEE3333.toInt())

                if (hitTrash && device != null) {
                    onDeleteClick(device)
                } else {
                    onOrderChanged(devices.toList())
                }
                draggingDevice = null
                hasDragged = false
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        }
        itemTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(rv) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceIcon: TextView = view.findViewById(R.id.tvDeviceIcon)
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvDeviceAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        val tvBrightness: TextView = view.findViewById(R.id.tvBrightness)
        val tvTemperature: TextView = view.findViewById(R.id.tvTemperature)
        val tvLightLevel: TextView = view.findViewById(R.id.tvLightLevel)
        val tvLightStatus: TextView = view.findViewById(R.id.tvLightStatus)
        val tvScheduleStatus: TextView = view.findViewById(R.id.tvScheduleStatus)
        val btnDelete: TextView = view.findViewById(R.id.btnDeleteDevice)
        var wobbleAnimator: AnimatorSet? = null
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

        holder.tvTemperature.visibility = if (device.temperature != null) View.VISIBLE else View.GONE
        device.temperature?.let { holder.tvTemperature.text = "${String.format("%.1f", it)}°C" }

        holder.tvLightLevel.visibility = if (device.lightLevel != null) View.VISIBLE else View.GONE
        device.lightLevel?.let { holder.tvLightLevel.text = "${String.format("%.1f", it)} lux" }

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

        if (isEditMode) {
            holder.btnDelete.visibility = View.VISIBLE
            startWobble(holder)
        } else {
            holder.btnDelete.visibility = View.GONE
            stopWobble(holder)
        }

        holder.itemView.setOnClickListener {
            if (isEditMode) {
                setEditMode(false)
            } else {
                onDeviceClick(device)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (!isEditMode) setEditMode(true)
            itemTouchHelper?.startDrag(holder)
            true
        }
        holder.btnDelete.setOnClickListener {
            onDeleteClick(device)
        }
    }

    private fun startWobble(holder: ViewHolder) {
        holder.wobbleAnimator?.cancel()
        val delay = (holder.adapterPosition % 3) * 60L
        val rotate = ObjectAnimator.ofFloat(holder.itemView, "rotation", -2f, 2f).apply {
            duration = 180
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            startDelay = delay
        }
        holder.wobbleAnimator = AnimatorSet().apply { play(rotate); start() }
    }

    private fun stopWobble(holder: ViewHolder) {
        holder.wobbleAnimator?.cancel()
        holder.wobbleAnimator = null
        holder.itemView.rotation = 0f
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        stopWobble(holder)
    }

    override fun getItemCount() = devices.size

    fun updateDevices(newDevices: List<MeshDevice>) {
        devices = newDevices.toMutableList()
        notifyDataSetChanged()
    }
}
