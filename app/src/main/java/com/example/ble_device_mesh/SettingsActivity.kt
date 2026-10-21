package com.example.ble_device_mesh

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.ble_device_mesh.data.DeviceRepository
import com.example.ble_device_mesh.data.DeviceType
import com.example.ble_device_mesh.data.MeshDevice
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsActivity : AppCompatActivity() {
    private val viewModel: MeshViewModel by viewModels()

    // 导出文件时的权限：true = 仅控制（剥离 Device Key）
    private var exportControlOnly = false
    
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            importMeshConfig(it)
        }
    }
    
    private val fileSaverLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            exportMeshConfig(it)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        val btnBack = findViewById<Button>(R.id.btnBack)
        val btnImportConfig = findViewById<Button>(R.id.btnImportConfig)
        val btnExportConfig = findViewById<Button>(R.id.btnExportConfig)
        val btnMeshTransfer = findViewById<Button>(R.id.btnMeshTransfer)
        val tvConfigStatus = findViewById<TextView>(R.id.tvConfigStatus)
        val switchAutoConnect = findViewById<Switch>(R.id.switchAutoConnect)
        val btnScanProxy = findViewById<Button>(R.id.btnScanProxy)
        val tvProxyStatus = findViewById<TextView>(R.id.tvProxyStatus)
        val btnRestoreDeleted = findViewById<Button>(R.id.btnRestoreDeleted)
        val btnAddFromNetwork = findViewById<Button>(R.id.btnAddFromNetwork)
        val tvDeviceRestoreStatus = findViewById<TextView>(R.id.tvDeviceRestoreStatus)
        val deviceRepo = DeviceRepository(this)

        fun refreshRestoreHint() {
            val deleted = deviceRepo.getDeletedDevices().size
            val recoverable = viewModel.getRecoverableNodes().count { !it.alreadyAdded }
            tvDeviceRestoreStatus.text =
                "回收站 $deleted 台 · 网络中有 $recoverable 个节点未加入列表"
        }
        refreshRestoreHint()

        btnRestoreDeleted.setOnClickListener {
            showRestoreDeletedDialog(deviceRepo) { refreshRestoreHint() }
        }

        btnAddFromNetwork.setOnClickListener {
            showAddFromNetworkDialog(deviceRepo) { refreshRestoreHint() }
        }

        findViewById<Button>(R.id.btnDeviceFinder).setOnClickListener {
            startActivity(Intent(this, DeviceFinderActivity::class.java))
        }
        
        btnBack.setOnClickListener {
            finish()
        }
        
        btnImportConfig.setOnClickListener {
            filePickerLauncher.launch("application/json")
        }
        
        btnExportConfig.setOnClickListener {
            if (viewModel.isControlOnly()) {
                AlertDialog.Builder(this)
                    .setTitle("导出配置")
                    .setMessage("本机为控制端（网络中没有 Device Key），只能导出「仅控制」权限的配置。")
                    .setPositiveButton("仅控制分享") { _, _ ->
                        exportControlOnly = true
                        fileSaverLauncher.launch("mesh_config_control.json")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("导出配置")
                    .setMessage("选择导出用途：\n\n完整备份：包含 Device Key，可以在本机恢复或管理设备。\n仅控制：不包含 Device Key，对方手机只能开关灯/调光，不能清除配网。")
                    .setPositiveButton("完整备份") { _, _ ->
                        exportControlOnly = false
                        fileSaverLauncher.launch("mesh_config.json")
                    }
                    .setNeutralButton("仅控制分享") { _, _ ->
                        exportControlOnly = true
                        fileSaverLauncher.launch("mesh_config_control.json")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        btnMeshTransfer.setOnClickListener {
            startActivity(Intent(this, com.example.ble_device_mesh.mesh_transfer.TransferActivity::class.java))
        }

        setupTimeSyncSection()
        
        
        btnScanProxy.setOnClickListener {
            // TODO: 打开 Proxy 扫描界面
            Toast.makeText(this, "扫描 Proxy 节点", Toast.LENGTH_SHORT).show()
        }
        
        // 修改Provisioner地址
        tvConfigStatus.setOnClickListener {
            val currentAddr = viewModel.currentProvisionerAddress.value ?: 0
            val input = EditText(this)
            input.hint = "输入新地址(十六进制，如 200)"
            AlertDialog.Builder(this)
                .setTitle("修改Provisioner地址")
                .setMessage("当前地址: 0x${currentAddr.toString(16)}\n(基于设备ID自动生成)\n\n可手动修改，但建议保持自动分配")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    try {
                        val newAddr = input.text.toString().toInt(16)
                        viewModel.setProvisionerAddress(newAddr)
                        // 保存手动设置的地址
                        getSharedPreferences("mesh_config", MODE_PRIVATE)
                            .edit().putInt("provisioner_address", newAddr).apply()
                        Toast.makeText(this, "地址已设置为: 0x${newAddr.toString(16)}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "地址格式错误", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        
        // 观察网络状态
        viewModel.statusText.observe(this) { status ->
            if (status.contains("已就绪") || status.contains("创建成功") || status.contains("Mesh 网络")) {
                tvConfigStatus.text = "配置已就绪"
            }
        }
        
        viewModel.isConnected.observe(this) { connected ->
            tvProxyStatus.text = if (connected) "已连接" else "未连接"
        }
        
        viewModel.currentProvisionerAddress.observe(this) { addr ->
            tvConfigStatus.text = "Provisioner地址: 0x${addr?.toString(16)} (点击修改)"
        }
    }
    
    /** 回收站：列出最近删除的设备，一键恢复名称/地址/MAC */
    private fun showRestoreDeletedDialog(repo: DeviceRepository, onDone: () -> Unit) {
        val deleted = repo.getDeletedDevices()
        if (deleted.isEmpty()) {
            Toast.makeText(this, "回收站是空的", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = deleted.map { "${it.name}   0x${it.address.toString(16)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("恢复已删除设备")
            .setItems(labels) { _, which ->
                val device = deleted[which]
                repo.restoreDevice(device)
                Toast.makeText(this, "已恢复 ${device.name}", Toast.LENGTH_SHORT).show()
                onDone()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 从 Mesh 网络找回设备：列出网络数据库里的全部节点（含已在列表的），
     * 先广播一条 GenericOnOffGet 探测在线情况，按「在线优先」排序，避免加错地址。
     */
    private fun showAddFromNetworkDialog(repo: DeviceRepository, onDone: () -> Unit) {
        val nodes = viewModel.getRecoverableNodes()
        if (nodes.isEmpty()) {
            Toast.makeText(this, "网络里没有节点，请先连接 Proxy", Toast.LENGTH_SHORT).show()
            return
        }

        val probeStart = System.currentTimeMillis()
        val probed = nodes.count { viewModel.probeNode(it.address) }
        Toast.makeText(this, "正在探测 $probed 个节点…", Toast.LENGTH_SHORT).show()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val live = nodes
                .filter { (MeshViewModel.MeshState.lastMessageTime[it.address] ?: 0L) >= probeStart }
                .map { it.address }
                .toSet()

            val sorted = nodes.sortedWith(
                compareByDescending<RecoverableNode> { it.address in live }.thenBy { it.address }
            )
            val labels = sorted.map { node ->
                val state = if (node.address in live) "● 在线" else "○ 未响应"
                val name = node.name.ifBlank { "未命名" }
                val added = if (node.alreadyAdded) "  (已在列表)" else ""
                "$state  $name   0x${node.address.toString(16)}$added"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("从 Mesh 网络找回设备")
                .setItems(labels) { _, which ->
                    val node = sorted[which]
                    if (node.alreadyAdded) {
                        Toast.makeText(this, "0x${node.address.toString(16)} 已在设备列表中", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                    val name = node.name.ifBlank { "设备${node.address.toString(16)}" }
                    repo.addDevice(
                        MeshDevice(
                            id = "mesh_${node.address}",
                            name = name,
                            address = node.address,
                            type = DeviceType.LIGHT,
                            brightness = 0,
                            groupAddress = 0
                        )
                    )
                    repo.removeFromRecycleBin("mesh_${node.address}")
                    Toast.makeText(this, "已添加 $name (0x${node.address.toString(16)})", Toast.LENGTH_SHORT).show()
                    onDone()
                }
                .setNegativeButton("关闭", null)
                .show()
        }, 4000)
    }

    private fun importMeshConfig(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("无法打开文件流")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonData = reader.readText()
            reader.close()
            inputStream.close()

            viewModel.importMeshNetworkWithDevices(jsonData)
            Toast.makeText(this, "正在导入配置...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportMeshConfig(uri: Uri) {
        val json = viewModel.exportMeshNetworkWithDevices(exportControlOnly)
        if (json == null) {
            Toast.makeText(this, "导出失败：网络未加载或为空", Toast.LENGTH_LONG).show()
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(json.toByteArray())
            }
            val msg = if (exportControlOnly || viewModel.isControlOnly()) "导出成功（仅控制权限，不含 Device Key）" else "导出成功（包含设备信息）"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "写入文件失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 「时间同步」区块：
     * - 管理员：可选自动间隔、看上次结果 / 下次触发、手动立刻同步
     * - 仅控制：只显示提示（时间由管理员手机负责，本机不写设备时间）
     */
    private fun setupTimeSyncSection() {
        val tvRole = findViewById<TextView>(R.id.tvTimeSyncRole)
        val spinner = findViewById<Spinner>(R.id.spinnerSyncInterval)
        val tvStatus = findViewById<TextView>(R.id.tvSyncStatus)
        val tvHint = findViewById<TextView>(R.id.tvTimeSyncHint)
        val btnSyncNow = findViewById<Button>(R.id.btnSyncNow)

        fun refresh() {
            tvStatus.text = TimeSyncScheduler.summaryText(this)
        }

        if (viewModel.isControlOnly()) {
            tvRole.text = "本机角色：仅控制"
            spinner.visibility = View.GONE
            btnSyncNow.visibility = View.GONE
            tvHint.visibility = View.GONE
            tvStatus.text = "时间由管理员手机统一同步，本机不参与自动对时。"
            return
        }

        tvRole.text = "本机角色：管理员（时间权威）"

        val labels = TimeSyncScheduler.INTERVAL_OPTIONS.map { hours ->
            if (hours == 0) "关闭自动同步" else "每 $hours 小时"
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val current = TimeSyncScheduler.intervalHours(this)
        spinner.setSelection(TimeSyncScheduler.INTERVAL_OPTIONS.indexOf(current).coerceAtLeast(0))

        // setSelection 会回调一次，用已应用的间隔过滤掉，避免无意义的重排闹钟
        var lastApplied = current
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val hours = TimeSyncScheduler.INTERVAL_OPTIONS[position]
                if (hours == lastApplied) return
                lastApplied = hours
                TimeSyncScheduler.setIntervalHours(this@SettingsActivity, hours)
                refresh()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        refresh()

        btnSyncNow.setOnClickListener {
            btnSyncNow.isEnabled = false
            Toast.makeText(this, "正在同步时间...", Toast.LENGTH_SHORT).show()
            TimeSyncEngine.run(this, "manual") {
                btnSyncNow.isEnabled = true
                refresh()
                Toast.makeText(
                    this,
                    TimeSyncScheduler.lastResultText(this) ?: "同步结束",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
