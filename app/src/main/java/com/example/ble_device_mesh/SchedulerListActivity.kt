package com.example.ble_device_mesh

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.SchedulerRepository
import com.example.ble_device_mesh.data.SchedulerTask
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * 定时任务列表页面
 * 显示设备的所有定时任务，支持添加/编辑/删除/启用/禁用
 */
class SchedulerListActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private lateinit var schedulerRepository: SchedulerRepository
    private lateinit var adapter: SchedulerAdapter
    private var deviceAddress: Int = 0
    private var deviceName: String = ""
    private var tasks = mutableListOf<SchedulerTask>()
    private var readingFromDevice = false

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val REQUEST_EDIT_TASK = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scheduler_list)

        deviceAddress = intent.getIntExtra(EXTRA_DEVICE_ADDRESS, 0)
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "设备"
        schedulerRepository = SchedulerRepository(this)

        // 设置标题
        findViewById<TextView>(R.id.tvTitle).text = "$deviceName 定时任务"

        // 返回按钮
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        // 刷新按钮
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener {
            if (viewModel.isConnected.value == true) {
                readTasksFromDevice()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        // 同步时间按钮
        findViewById<TextView>(R.id.btnSyncTime).setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.setDeviceTime(deviceAddress)
                Toast.makeText(this, "正在同步时间...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        // 设置 RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerTasks)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = SchedulerAdapter(
            tasks = tasks,
            onTaskClick = { task -> editTask(task) },
            onTaskLongClick = { task -> showDeleteDialog(task) },
            onEnabledToggle = { task, enabled -> toggleTask(task, enabled) }
        )
        recyclerView.adapter = adapter

        // 添加任务按钮
        findViewById<FloatingActionButton>(R.id.fabAddTask).setOnClickListener {
            addNewTask()
        }

        // 加载本地缓存的任务
        loadLocalTasks()

        // 观察任务更新
        viewModel.schedulerTaskUpdates.observe(this) { task ->
            if (task.deviceAddress == deviceAddress || task.deviceAddress == 0) {
                // 更新本地缓存
                val existingIndex = tasks.indexOfFirst { it.index == task.index }
                val updatedTask = task.copy(deviceAddress = deviceAddress)
                if (existingIndex >= 0) {
                    tasks[existingIndex] = updatedTask
                } else {
                    tasks.add(updatedTask)
                }
                updateUI()
                schedulerRepository.upsertTask(deviceAddress, updatedTask)
            }
        }

        // 观察执行通知
        viewModel.schedulerExecutionNotify.observe(this) { task ->
            if (task.deviceAddress == deviceAddress || task.deviceAddress == 0) {
                val actionStr = if (task.action == SchedulerTask.Action.ON) "开灯" else "关灯"
                Toast.makeText(this, "任务 #${task.index} 已执行: $actionStr", Toast.LENGTH_SHORT).show()
            }
        }

        // 观察状态
        viewModel.statusText.observe(this) { status ->
            val tvStatus = findViewById<TextView>(R.id.tvStatus)
            if (status.contains("调度") || status.contains("定时") || status.contains("Scheduler")) {
                tvStatus.text = status
                tvStatus.visibility = View.VISIBLE
            }
        }

        // 自动从设备读取
        if (viewModel.isConnected.value == true) {
            readTasksFromDevice()
        }
    }

    override fun onResume() {
        super.onResume()
        loadLocalTasks()
    }

    private fun loadLocalTasks() {
        tasks.clear()
        tasks.addAll(schedulerRepository.getTasks(deviceAddress))
        updateUI()
    }

    private fun updateUI() {
        val emptyState = findViewById<View>(R.id.emptyState)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerTasks)

        if (tasks.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }

        adapter.updateTasks(tasks)
    }

    private fun readTasksFromDevice() {
        readingFromDevice = true
        viewModel.readAllSchedulerTasks(deviceAddress)
        Toast.makeText(this, "正在读取设备任务...", Toast.LENGTH_SHORT).show()
    }

    private fun addNewTask() {
        val nextIndex = schedulerRepository.getNextAvailableIndex(deviceAddress)
        if (nextIndex < 0) {
            Toast.makeText(this, "所有16个任务槽位已满", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, SchedulerEditActivity::class.java).apply {
            putExtra(SchedulerEditActivity.EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(SchedulerEditActivity.EXTRA_DEVICE_NAME, deviceName)
            putExtra(SchedulerEditActivity.EXTRA_TASK_INDEX, nextIndex)
        }
        startActivityForResult(intent, REQUEST_EDIT_TASK)
    }

    private fun editTask(task: SchedulerTask) {
        val intent = Intent(this, SchedulerEditActivity::class.java).apply {
            putExtra(SchedulerEditActivity.EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(SchedulerEditActivity.EXTRA_DEVICE_NAME, deviceName)
            putExtra(SchedulerEditActivity.EXTRA_TASK_INDEX, task.index)
            putExtra(SchedulerEditActivity.EXTRA_TASK, task)
        }
        startActivityForResult(intent, REQUEST_EDIT_TASK)
    }

    private fun showDeleteDialog(task: SchedulerTask) {
        AlertDialog.Builder(this)
            .setTitle("删除任务")
            .setMessage("确定删除 ${task.getTimeString()} ${task.getActionDescription()} 的定时任务？")
            .setPositiveButton("删除") { _, _ ->
                deleteTask(task)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteTask(task: SchedulerTask) {
        // 从本地删除
        schedulerRepository.deleteTask(deviceAddress, task.index)
        tasks.removeAll { it.index == task.index }
        updateUI()

        // 发送 NO_ACTION 到设备以清除该槽位
        if (viewModel.isConnected.value == true) {
            val emptyTask = SchedulerTask(
                index = task.index,
                hour = 0,
                minute = 0,
                second = 0,
                action = SchedulerTask.Action.NO_ACTION,
                brightness = 0,
                repeat = 0,
                enabled = false,
                deviceAddress = deviceAddress
            )
            viewModel.setSchedulerTask(deviceAddress, emptyTask)
            Toast.makeText(this, "任务已删除", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "任务已从本地删除，连接设备后同步", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleTask(task: SchedulerTask, enabled: Boolean) {
        val updatedTask = task.copy(enabled = enabled)
        val idx = tasks.indexOfFirst { it.index == task.index }
        if (idx >= 0) {
            tasks[idx] = updatedTask
        }
        schedulerRepository.upsertTask(deviceAddress, updatedTask)
        adapter.updateTasks(tasks)

        // 发送到设备
        if (viewModel.isConnected.value == true) {
            viewModel.setSchedulerTask(deviceAddress, updatedTask)
        } else {
            Toast.makeText(this, "仅在本地更新，连接设备后同步", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EDIT_TASK && resultCode == RESULT_OK) {
            // 重新加载任务
            loadLocalTasks()
        }
    }
}
