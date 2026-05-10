package com.example.ble_device_mesh

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.GroupRepository
import com.example.ble_device_mesh.data.MeshGroup

class GroupManagementActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private lateinit var groupRepository: GroupRepository
    private lateinit var adapter: GroupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_management)

        groupRepository = GroupRepository(this)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddGroup).setOnClickListener { showCreateGroupDialog() }

        setupList()
        loadGroups()
    }

    private fun setupList() {
        val rv = findViewById<RecyclerView>(R.id.rvGroups)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = GroupAdapter(mutableListOf()) { group ->
            // card click - could navigate to group detail, currently no-op
        }
        rv.adapter = adapter
    }

    private fun loadGroups() {
        val groups = groupRepository.getAllGroups()
        adapter.updateGroups(groups)
        findViewById<TextView>(R.id.tvEmpty).visibility =
            if (groups.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        loadGroups()
    }

    private fun showCreateGroupDialog() {
        val input = EditText(this)
        input.hint = "输入分组名称"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT

        AlertDialog.Builder(this)
            .setTitle("创建新分组")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "请输入分组名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val address = groupRepository.allocateGroupAddress()
                val group = MeshGroup(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    address = address
                )
                groupRepository.addGroup(group)
                Toast.makeText(this, "已创建分组：$name (0x${address.toString(16).uppercase()})", Toast.LENGTH_SHORT).show()
                loadGroups()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameDialog(group: MeshGroup, onRenamed: (String) -> Unit) {
        val input = EditText(this)
        input.setText(group.name)
        input.setSelection(group.name.length)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT

        AlertDialog.Builder(this)
            .setTitle("重命名分组")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "分组名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onRenamed(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class GroupAdapter(
        private var groups: MutableList<MeshGroup>,
        private val onItemClick: (MeshGroup) -> Unit
    ) : RecyclerView.Adapter<GroupAdapter.ViewHolder>() {

        fun updateGroups(newGroups: List<MeshGroup>) {
            groups.clear()
            groups.addAll(newGroups)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_group, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groups[position]
            val ctx = holder.itemView.context

            holder.tvName.text = group.name
            holder.tvAddress.text = "0x${group.address.toString(16).uppercase()}"
            holder.tvMemberCount.text = "${group.memberDeviceIds.size} 台"

            // 设备在线状态
            val deviceRepo = com.example.ble_device_mesh.data.DeviceRepository(ctx)
            val devices = group.memberDeviceIds.mapNotNull { deviceRepo.getDeviceById(it) }
            val anyOnline = devices.any { it.isOnline }
            val onlineCount = devices.count { it.isOnline }

            holder.viewOnlineDot.setBackgroundResource(
                if (anyOnline) R.drawable.circle_green else R.drawable.circle_gray
            )

            // 设备状态摘要
            if (devices.isNotEmpty()) {
                holder.layoutDeviceStatus.visibility = View.VISIBLE
                holder.tvDeviceStatus.text = "$onlineCount/${devices.size} 台在线"
            } else {
                holder.layoutDeviceStatus.visibility = View.GONE
            }

            // 开关按钮
            holder.btnOnOff.text = if (anyOnline) "关" else "开"
            holder.btnOnOff.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    ctx.getColor(if (anyOnline) android.R.color.holo_red_dark else android.R.color.holo_green_dark)
                )
            )

            holder.seekBar.progress = 50
            holder.tvBrightness.text = "50"

            holder.btnOnOff.setOnClickListener {
                val isCurrentlyOn = holder.btnOnOff.text == "关"
                holder.btnOnOff.text = if (isCurrentlyOn) "开" else "关"
                holder.btnOnOff.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                        ctx.getColor(if (isCurrentlyOn) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
                    )
                )
                viewModel.sendGroupOnOff(group.address, !isCurrentlyOn)
            }

            holder.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                private var lastSend = 0L
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        holder.tvBrightness.text = progress.toString()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val progress = sb?.progress ?: return
                    val now = System.currentTimeMillis()
                    if (now - lastSend > 100) {
                        lastSend = now
                        viewModel.sendGroupBrightness(group.address, progress)
                    }
                }
            })

            // 重命名分组
            holder.btnRename.setOnClickListener {
                showRenameDialog(group) { newName ->
                    val updated = group.copy(name = newName)
                    groupRepository.updateGroup(updated)
                    loadGroups()
                }
            }

            // 长按删除
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("删除分组")
                    .setMessage("确定要删除分组 \"${group.name}\" 吗？\n设备不会被取消订阅，但将无法通过此分组控制。")
                    .setPositiveButton("删除") { _, _ ->
                        groupRepository.deleteGroup(group.id)
                        updateGroups(groupRepository.getAllGroups())
                        findViewById<TextView>(R.id.tvEmpty).visibility =
                            if (groups.isEmpty()) View.VISIBLE else View.GONE
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        override fun getItemCount() = groups.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(R.id.tvGroupName)
            val tvAddress: TextView = itemView.findViewById(R.id.tvGroupAddress)
            val tvMemberCount: TextView = itemView.findViewById(R.id.tvMemberCount)
            val viewOnlineDot: View = itemView.findViewById(R.id.viewOnlineDot)
            val btnRename: TextView = itemView.findViewById(R.id.btnRenameGroupEnd)
            val btnOnOff: Button = itemView.findViewById(R.id.btnGroupOnOff)
            val seekBar: SeekBar = itemView.findViewById(R.id.seekBarGroupBrightness)
            val tvBrightness: TextView = itemView.findViewById(R.id.tvGroupBrightness)
            val layoutDeviceStatus: View = itemView.findViewById(R.id.layoutDeviceStatus)
            val tvDeviceStatus: TextView = itemView.findViewById(R.id.tvDeviceStatus)
        }
    }
}
