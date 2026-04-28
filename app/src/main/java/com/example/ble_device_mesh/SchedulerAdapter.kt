package com.example.ble_device_mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.SchedulerTask

/**
 * 定时任务列表适配器
 */
class SchedulerAdapter(
    private var tasks: List<SchedulerTask>,
    private val onTaskClick: (SchedulerTask) -> Unit,
    private val onTaskLongClick: (SchedulerTask) -> Unit,
    private val onEnabledToggle: (SchedulerTask, Boolean) -> Unit
) : RecyclerView.Adapter<SchedulerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvRepeat: TextView = view.findViewById(R.id.tvRepeat)
        val tvAction: TextView = view.findViewById(R.id.tvAction)
        val tvBrightness: TextView = view.findViewById(R.id.tvBrightness)
        val switchEnabled: SwitchCompat = view.findViewById(R.id.switchEnabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scheduler_task, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val task = tasks[position]

        holder.tvTime.text = task.getTimeString()
        holder.tvRepeat.text = task.getRepeatDescription()

        // 动作显示
        when (task.action) {
            SchedulerTask.Action.ON -> {
                holder.tvAction.text = "开灯"
                holder.tvAction.setTextColor(
                    holder.itemView.context.getColor(android.R.color.holo_green_dark)
                )
                holder.tvBrightness.text = "亮度 ${task.brightness}%"
                holder.tvBrightness.visibility = View.VISIBLE
            }
            SchedulerTask.Action.OFF -> {
                holder.tvAction.text = "关灯"
                holder.tvAction.setTextColor(
                    holder.itemView.context.getColor(android.R.color.holo_red_dark)
                )
                holder.tvBrightness.visibility = View.GONE
            }
            SchedulerTask.Action.NO_ACTION -> {
                holder.tvAction.text = "未设置"
                holder.tvAction.setTextColor(
                    holder.itemView.context.getColor(android.R.color.darker_gray)
                )
                holder.tvBrightness.visibility = View.GONE
            }
            SchedulerTask.Action.STREETLIGHT -> {
                holder.tvAction.text = "路灯"
                holder.tvAction.setTextColor(
                    holder.itemView.context.getColor(android.R.color.holo_orange_dark)
                )
                holder.tvBrightness.text = "亮度 ${task.brightness}%"
                holder.tvBrightness.visibility = View.VISIBLE
            }
        }

        // 禁用时的视觉效果
        val alpha = if (task.enabled) 1.0f else 0.5f
        holder.tvTime.alpha = alpha
        holder.tvRepeat.alpha = alpha
        holder.tvAction.alpha = alpha
        holder.tvBrightness.alpha = alpha

        // 启用开关
        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = task.enabled
        holder.switchEnabled.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            onEnabledToggle(task, isChecked)
        }

        // 点击编辑
        holder.itemView.setOnClickListener {
            onTaskClick(task)
        }

        // 长按删除
        holder.itemView.setOnLongClickListener {
            onTaskLongClick(task)
            true
        }
    }

    override fun getItemCount() = tasks.size

    fun updateTasks(newTasks: List<SchedulerTask>) {
        tasks = newTasks
        notifyDataSetChanged()
    }
}
