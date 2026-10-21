package com.example.ble_device_mesh

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.DeviceRepository
import com.example.ble_device_mesh.data.DeviceType
import com.example.ble_device_mesh.data.GroupRepository
import com.example.ble_device_mesh.data.MeshDevice
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val viewModel: MeshViewModel by viewModels()
    private lateinit var deviceAdapter: MeshDeviceAdapter
    private lateinit var deviceRepository: DeviceRepository

    companion object {
        private const val TAG = "MainActivity"
        // 全关串行发送间隔，需 >=300ms 规避 MIUI 写特征值不回调的问题
        private const val ALL_OFF_INTERVAL_MS = 300L
        // 全关后读回校验：首条读回延迟、读回间隔、等待回包时间
        private const val VERIFY_START_DELAY_MS = 1200L
        private const val VERIFY_INTERVAL_MS = 300L
        private const val VERIFY_WAIT_MS = 1500L
    }

    // 全关执行中标记，避免连点重复下发
    private var allOffInProgress = false
    // 全关过程中投递的延迟任务，便于退出时取消
    private val allOffRunnables = mutableListOf<Runnable>()

    // 全关读回校验状态：待回包设备 address -> name，校验失败设备 address -> (name, 实际亮度)
    private var allOffVerifyActive = false
    private val allOffVerifyPending = mutableMapOf<Int, String>()
    private val allOffVerifyFailed = mutableMapOf<Int, Pair<String, Int>>()
    private val allOffVerifyRunnables = mutableListOf<Runnable>()

    // 通知权限（Android 13+），时间同步结果通过通知栏展示
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Log.w(TAG, "通知权限被拒绝，同步结果不会显示在通知栏")
        }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            deviceRepository = DeviceRepository(this)

            // 排布每 3 小时的时间同步闹钟（即使本次没连上也要排）
            TimeSyncScheduler.ensureScheduled(this)
            requestNotificationPermissionIfNeeded()

            // 清除旧的温度数据，避免显示过期的信息
            deviceRepository.clearAllTemperatures()

            setupViews()
            loadDevices()

            // 自动连接已有设备
            tryAutoConnect()
        } catch (e: Exception) {
            Log.e("MainActivity", "onCreate 严重错误: ${e.message}")
            e.printStackTrace()
            Toast.makeText(this, "应用启动异常: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Android 13+ 同步结果需要通过通知栏展示，这里申请一次通知权限。
     * 没授权时同步照常执行，只是结果看不到通知。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            Log.w(TAG, "请求通知权限失败: ${e.message}")
        }
    }
    
    private fun setupViews() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvConnectedDeviceName = findViewById<TextView>(R.id.tvConnectedDeviceName)
        val btnAddDevice = findViewById<Button>(R.id.btnAddDevice)
        val rvDevices = findViewById<RecyclerView>(R.id.rvDevices)
        val layoutEmpty = findViewById<LinearLayout>(R.id.layoutEmpty)
        
        // 添加设置按钮（如果布局中有的话）
        findViewById<Button>(R.id.btnSettings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        

        
        findViewById<LinearLayout>(R.id.sceneAllOff).setOnClickListener {
            sendAllOffScene()
        }

        // 设备列表
        deviceAdapter = MeshDeviceAdapter(
            mutableListOf(),
            onDeviceClick = { device ->
                openDeviceDetail(device)
            },
            onBrightnessChange = { device, progress ->
                val targetAddress = device.groupAddress ?: device.address
                viewModel.sendBrightness(targetAddress, progress)
            }
        )
        rvDevices.layoutManager = GridLayoutManager(this, 2)
        rvDevices.adapter = deviceAdapter

        // 长按拖拽排序
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                deviceAdapter.swapItems(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled() = true

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 拖拽结束，保存最终顺序
                val reordered = deviceAdapter.getDevices()
                reordered.forEachIndexed { index, device ->
                    device.sortOrder = index
                }
                deviceRepository.reorderDevices(reordered)
            }
        })
        itemTouchHelper.attachToRecyclerView(rvDevices)

        // 配网设备按钮
        btnAddDevice.text = "配网设备"
        btnAddDevice.setOnClickListener {
            startActivity(Intent(this, ProvisionActivity::class.java))
        }
        
        // 底部导航栏
        findViewById<LinearLayout>(R.id.navHome).setOnClickListener {
            // 已在主页
        }
        
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        findViewById<LinearLayout>(R.id.navProfile).setOnClickListener {
            startActivity(Intent(this, GroupManagementActivity::class.java))
        }
        
        // 观察状态
        viewModel.statusText.observe(this) { status ->
            tvStatus.text = "状态: $status"
        }
        
        // 观察本机地址 (新增)
        viewModel.currentProvisionerAddress.observe(this) { address ->
             val tvSrc = findViewById<TextView>(R.id.tvSrcAddress)
             tvSrc.text = "本机地址: 0x${address.toString(16).uppercase()}"
        }
        
        // 观察连接状态
        viewModel.isConnected.observe(this) { connected ->
            if (connected) {
                tvStatus.text = "状态: 已连接到 Proxy"
                // 排布时间同步闹钟；距上次成功同步超过 1 小时则顺带补一次
                TimeSyncScheduler.ensureScheduled(this)
                TimeSyncEngine.runIfStale(this, "connect")
                // 连接后立即读取所有设备的温度
                readAllTemperatures()
            } else {
                tvConnectedDeviceName.text = ""
            }
        }

        viewModel.connectedDeviceAddress.observe(this) { mac ->
            val deviceName = mac?.let { connectedMac ->
                deviceRepository.getAllDevices()
                    .firstOrNull { it.bluetoothMac.equals(connectedMac, ignoreCase = true) }
                    ?.name
            }
            tvConnectedDeviceName.text = deviceName?.let { " · $it" } ?: ""
        }
        
        // 观察温度更新
        viewModel.temperatureUpdates.observe(this) { (address, temperature) ->
            Log.d("MainActivity", "收到温度更新: 地址=0x${address.toString(16)}, 温度=$temperature°C")
            // 更新设备列表中对应设备的温度
            val devices = deviceRepository.getAllDevices()
            val device = devices.find { it.address == address }
            if (device != null) {
                device.temperature = temperature
                deviceRepository.updateDevice(device)
                loadDevices() // 刷新列表
            }
        }
        
        // 观察时间更新
        viewModel.timeUpdates.observe(this) { (address, unixTime) ->
            Log.d("MainActivity", "收到时间更新: 地址=0x${address.toString(16)}, 时间=$unixTime")
            // 更新设备列表中对应设备的时间
            val devices = deviceRepository.getAllDevices()
            val device = devices.find { it.address == address }
            if (device != null) {
                device.deviceTime = unixTime
                deviceRepository.updateDevice(device)
            }
        }

        // 在线状态变化刷新设备列表
        viewModel.deviceOnlineUpdates.observe(this) {
            loadDevices()
        }

        // 全关后的读回校验（Generic Level Status → 0-100%）
        viewModel.levelReadUpdates.observe(this) { (address, percent) ->
            if (!allOffVerifyActive) return@observe
            if (allOffVerifyPending.remove(address) == null) return@observe

            Log.d(TAG, "全关校验-回读: 0x${address.toString(16)} = $percent%")
            if (percent > 0) {
                val name = deviceRepository.getAllDevices()
                    .firstOrNull { it.address == address }?.name ?: "0x${address.toString(16)}"
                allOffVerifyFailed[address] = Pair(name, percent)
            }
        }

    }
    
    /**
     * 一键全关（Mesh 组网）
     *
     * 说明：
     * - 指令经 proxy 节点进入 mesh 后靠泛洪多跳转发，与"手机能否直接扫到该灯"无关，
     *   因此不按 isOnline 过滤（isOnline 只反映 BLE 视野/最近报文时间）。
     * - 设备绑定且分组表里存在的组地址只发一次组播（1 帧覆盖全组），
     *   未分组或组地址已失效的设备用单播补齐，避免"有分组就漏掉未分组设备"。
     * - 串行发送间隔 >=300ms，规避 MIUI 写特征值不回调的 bug。
     */
    private fun sendAllOffScene() {
        if (viewModel.isConnected.value != true) {
            Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            return
        }
        if (allOffInProgress) {
            Toast.makeText(this, "正在执行全关，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        // 重新执行时先取消上一轮的读回校验
        cancelAllOffVerify()

        val devices = deviceRepository.getAllDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "暂无设备", Toast.LENGTH_SHORT).show()
            return
        }

        val knownGroupAddresses = GroupRepository(this).getAllGroups().map { it.address }.toSet()

        // 组播目标（去重）与单播目标
        val groupTargets = mutableListOf<Int>()
        val unicastTargets = mutableListOf<Int>()
        devices.forEach { device ->
            val groupAddress = device.groupAddress
            if (groupAddress != null && groupAddress in knownGroupAddresses) {
                if (groupAddress !in groupTargets) groupTargets += groupAddress
            } else {
                unicastTargets += device.address
            }
        }

        val sendJobs = mutableListOf<() -> Unit>()
        groupTargets.forEach { addr ->
            sendJobs += {
                Log.d(TAG, "全关-组播: 0x${addr.toString(16)}")
                viewModel.sendGroupOnOff(addr, false)
            }
        }
        unicastTargets.forEach { addr ->
            sendJobs += {
                Log.d(TAG, "全关-单播: 0x${addr.toString(16)}")
                viewModel.sendOnOff(addr, false)
            }
        }

        Log.d(TAG, "全关开始: 组播 ${groupTargets.size} 条, 单播 ${unicastTargets.size} 条, 覆盖设备 ${devices.size} 台")

        allOffInProgress = true
        allOffRunnables.clear() // 上一轮的任务已执行完，清掉避免列表无限增长
        findViewById<LinearLayout>(R.id.sceneAllOff)?.isEnabled = false

        sendJobs.forEachIndexed { index, job ->
            val runnable = Runnable { job() }
            allOffRunnables += runnable
            handler.postDelayed(runnable, index * ALL_OFF_INTERVAL_MS)
        }

        // 全部指令投递完毕后再统一清零本地状态，避免与蓝牙写入抢主线程
        val finishRunnable = Runnable {
            allOffInProgress = false
            findViewById<LinearLayout>(R.id.sceneAllOff)?.isEnabled = true
            applyAllOffLocalState(devices)
            Toast.makeText(this, "已发送全部关闭", Toast.LENGTH_SHORT).show()
            // 再花几秒读回亮度，确认哪些设备其实没关掉
            startAllOffVerify(devices)
        }
        allOffRunnables += finishRunnable
        handler.postDelayed(finishRunnable, sendJobs.size * ALL_OFF_INTERVAL_MS + 300L)
    }

    /**
     * 全关后把本地亮度状态清零，让首页卡片的滑条/百分比/灯泡图标立即正确。
     * 卡片亮度读的是 DevicePrefs 里的 brightness_0x<addr>，不是数据库，所以两边都要写。
     */
    private fun applyAllOffLocalState(devices: List<MeshDevice>) {
        try {
            val editor = getSharedPreferences("DevicePrefs", MODE_PRIVATE).edit()
            devices.forEach { device ->
                editor.putInt("brightness_0x${device.address.toString(16)}", 0)
            }
            editor.apply()

            devices.forEach { device ->
                if (device.brightness != 0) {
                    deviceRepository.updateDevice(device.copy(brightness = 0))
                }
            }
            loadDevices()
        } catch (e: Exception) {
            Log.e(TAG, "全关后更新本地状态失败: ${e.message}")
        }
    }

    /**
     * 全关后逐台读回亮度（Generic Level Get），用来确认指令是否真的到达。
     * 能直接暴露三类问题：设备不在网内 / TTL 跳数不够 / 组播的 AppKey 绑定缺失（组播没关掉、单播可关）。
     */
    private fun startAllOffVerify(devices: List<MeshDevice>) {
        cancelAllOffVerify()
        devices.forEach { allOffVerifyPending[it.address] = it.name }
        val count = allOffVerifyPending.size
        if (count == 0) return

        allOffVerifyActive = true
        allOffVerifyPending.keys.forEachIndexed { index, address ->
            val runnable = Runnable {
                if (allOffVerifyActive) viewModel.readLevel(address)
            }
            allOffVerifyRunnables += runnable
            handler.postDelayed(runnable, VERIFY_START_DELAY_MS + index * VERIFY_INTERVAL_MS)
        }

        val finalizeRunnable = Runnable {
            allOffVerifyActive = false
            reportAllOffVerify(devices)
        }
        allOffVerifyRunnables += finalizeRunnable
        handler.postDelayed(
            finalizeRunnable,
            VERIFY_START_DELAY_MS + count * VERIFY_INTERVAL_MS + VERIFY_WAIT_MS
        )
    }

    /** 汇总读回结果：仍亮着的把真实亮度写回本地，无响应的单独列出 */
    private fun reportAllOffVerify(devices: List<MeshDevice>) {
        if (isFinishing || isDestroyed) return

        val failed = allOffVerifyFailed.values.toList()
        val noResponse = allOffVerifyPending.values.toList()

        if (failed.isEmpty() && noResponse.isEmpty()) {
            Toast.makeText(this, "全关完成，${devices.size} 台已确认关闭", Toast.LENGTH_SHORT).show()
            return
        }

        // 仍在亮的设备：把真实亮度写回本地，避免卡片一直显示 0%
        if (failed.isNotEmpty()) {
            val editor = getSharedPreferences("DevicePrefs", MODE_PRIVATE).edit()
            devices.forEach { device ->
                val actual = allOffVerifyFailed[device.address]?.second
                if (actual != null) {
                    editor.putInt("brightness_0x${device.address.toString(16)}", actual)
                    deviceRepository.updateDevice(device.copy(brightness = actual))
                }
            }
            editor.apply()
            loadDevices()
        }

        val message = buildString {
            append("仍未关闭 ${failed.size} 台：")
            append(failed.joinToString("、") { "${it.first} ${it.second}%" })
            if (noResponse.isNotEmpty()) {
                append("\n\n无响应 ${noResponse.size} 台：")
                append(noResponse.joinToString("、"))
            }
            append("\n\n无响应通常是设备不在网内、跳数(TTL)不够，或组播地址的 AppKey 未绑定。")
        }

        Log.w(TAG, "全关校验失败: $message")

        AlertDialog.Builder(this)
            .setTitle("全关校验")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 取消尚未完成的读回校验（重新全关或页面销毁时调用） */
    private fun cancelAllOffVerify() {
        allOffVerifyActive = false
        allOffVerifyRunnables.forEach { handler.removeCallbacks(it) }
        allOffVerifyRunnables.clear()
        allOffVerifyPending.clear()
        allOffVerifyFailed.clear()
    }

    private fun loadDevices() {
        val devices = deviceRepository.getAllDevices()
        deviceAdapter.updateDevices(devices)

        val layoutEmpty = findViewById<LinearLayout>(R.id.layoutEmpty)
        val rvDevices = findViewById<RecyclerView>(R.id.rvDevices)

        if (devices.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            rvDevices.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            rvDevices.visibility = View.VISIBLE
        }

        // 更新在线/离线计数
        val tvDeviceCount = findViewById<TextView>(R.id.tvDeviceCount)
        val onlineCount = devices.count { it.isOnline }
        tvDeviceCount.text = if (onlineCount > 0) "$onlineCount/${devices.size} 台在线" else "${devices.size} 台"
    }
    
    private fun tryAutoConnect() {
        if (viewModel.isConnected.value == true) return
        val deviceMacs = deviceRepository.getAllDevices()
            .mapNotNull { it.bluetoothMac?.takeIf { mac -> mac.isNotBlank() } }
            .distinct()
        if (deviceMacs.isNotEmpty()) {
            Log.d("MainActivity", "自动扫描设备卡片并连接信号最强设备: ${deviceMacs.size} 个")
            viewModel.autoConnectToStrongestProxyInAddresses(deviceMacs)
        } else {
            Log.d("MainActivity", "没有设备卡片 MAC，自动扫描并连接信号最强设备...")
            viewModel.autoConnectToStrongestKnownProxy()
        }
    }

    private fun showAddDeviceDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_device, null)
        val etDeviceName = dialogView.findViewById<EditText>(R.id.etDeviceName)
        val etDeviceAddress = dialogView.findViewById<EditText>(R.id.etDeviceAddress)
        val spinnerDeviceType = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)
        
        // 设置设备类型选择器
        val deviceTypes = arrayOf("灯光", "开关", "传感器", "其他")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDeviceType.adapter = adapter
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            val name = etDeviceName.text.toString().trim()
            val addressStr = etDeviceAddress.text.toString().trim()
            
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入设备名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (addressStr.isEmpty()) {
                Toast.makeText(this, "请输入设备地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // 解析地址（支持 0x0005 或 5 格式）
            val address = try {
                if (addressStr.startsWith("0x", ignoreCase = true)) {
                    addressStr.substring(2).toInt(16)
                } else {
                    addressStr.toInt(16)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "地址格式错误", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val deviceType = when (spinnerDeviceType.selectedItemPosition) {
                0 -> DeviceType.LIGHT
                1 -> DeviceType.SWITCH
                2 -> DeviceType.SENSOR
                else -> DeviceType.OTHER
            }
            
            val device = MeshDevice(
                id = UUID.randomUUID().toString(),
                name = name,
                address = address,
                type = deviceType
            )
            
            deviceRepository.addDevice(device)
            loadDevices()
            dialog.dismiss()
            
            Toast.makeText(this, "设备添加成功", Toast.LENGTH_SHORT).show()
        }
        
        dialog.show()
    }
    
    private fun showDeleteConfirmDialog(device: MeshDevice) {
        AlertDialog.Builder(this)
            .setTitle("删除设备")
            .setMessage("确定要把 ${device.name} 从设备列表移除吗？\n\n设备本身仍留在 Mesh 网络中，随时可在「设置 → 设备管理」里找回，不用重新配网。")
            .setPositiveButton("移除") { _, _ ->
                deviceRepository.deleteDevice(device.id)
                loadDevices()
                Toast.makeText(this, "已移出列表，可在设置里找回", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun openDeviceDetail(device: MeshDevice) {
        val intent = Intent(this, DeviceDetailActivity::class.java)
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE, device)
        intent.putExtra("EXTRA_IS_CONNECTED", viewModel.isConnected.value ?: false) // Pass connection state
        startActivity(intent)
    }
    
    override fun onResume() {
        super.onResume()
        loadDevices()
        startTemperaturePolling()
    }
    
    private fun readAllTemperatures() {
        try {
            if (isFinishing || isDestroyed) return
            if (viewModel.isConnected.value != true) return

            val devices = deviceRepository.getAllDevices()
            if (devices.isNotEmpty()) {
                Log.d("MainActivity", "读取 ${devices.size} 个设备的传感器数据...")
                devices.forEach { device ->
                    viewModel.readSensors(device.address)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "读取温度出错: ${e.message}")
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val temperatureRunnable = object : Runnable {
        override fun run() {
            readAllTemperatures()
            handler.postDelayed(this, 30000) // 30秒后再次执行
        }
    }

    private fun startTemperaturePolling() {
        Log.d("MainActivity", "启动传感器轮询")
        handler.removeCallbacks(temperatureRunnable) // 避免重复
        
        // 5秒后每次执行
        handler.postDelayed(temperatureRunnable, 5000)
    }
    
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(temperatureRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(temperatureRunnable)
        // 取消尚未投递的全关任务和读回校验，避免 Activity 销毁后继续往 mesh 发指令
        allOffRunnables.forEach { handler.removeCallbacks(it) }
        allOffRunnables.clear()
        cancelAllOffVerify()
    }
}