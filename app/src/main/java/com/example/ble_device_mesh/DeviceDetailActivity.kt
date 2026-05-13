package com.example.ble_device_mesh

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.Observer
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.GroupRepository
import com.example.ble_device_mesh.data.MeshDevice
import com.example.ble_device_mesh.data.MeshGroup

class DeviceDetailActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private lateinit var device: MeshDevice
    private lateinit var scanAdapter: DeviceAdapter
    private val deviceRepository by lazy { com.example.ble_device_mesh.data.DeviceRepository(this) }
    private val groupRepository by lazy { GroupRepository(this) }
    private var isInitialSelection = true  // 标记是否是初始化时的选择

    // 亮度拖动节流
    private val brightnessHandler = Handler(Looper.getMainLooper())
    private var lastBrightnessSendTime = 0L
    private val brightnessThrottleMs = 120L  // 最小发送间隔 120ms
    private var pendingBrightness = -1  // 暂存的亮度值，用于延迟发送
    
    companion object {
        const val EXTRA_DEVICE = "extra_device"
    }
    
    // 权限请求
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startScanForProxy()
        } else {
            Toast.makeText(this, "需要蓝牙权限才能扫描设备", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_detail)
        
        // 获取设备信息
        device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DEVICE, MeshDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DEVICE)
        } ?: run {
            finish()
            return
        }
        
        setupViews()
        observeViewModel()

        // 进入页面自动开始连接
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startAutoConnect()
        }, 500)
    }

    // 目标设备 MAC（用于连接后持续扫描监测）
    private val targetDeviceMac by lazy { getDeviceMac(device.address) }
    private var targetMonitorObserver: Observer<List<ScanResult>>? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTargetMonitoring()
    }
    

    private fun setupCollapsible(headerId: Int, bodyId: Int, iconId: Int, expanded: Boolean = true) {
        val header = findViewById<View>(headerId)
        val body = findViewById<View>(bodyId)
        val icon = findViewById<TextView>(iconId)
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        icon.text = if (expanded) "⌄" else "›"
        header.setOnClickListener {
            val isVisible = body.visibility == View.VISIBLE
            body.visibility = if (isVisible) View.GONE else View.VISIBLE
            icon.text = if (isVisible) "›" else "⌄"
        }
    }

    private fun setupViews() {
        setupCollapsible(R.id.headerConnection, R.id.bodyConnection, R.id.iconConnection, true)
        setupCollapsible(R.id.headerDeviceInfo, R.id.bodyDeviceInfo, R.id.iconDeviceInfo, true)
        setupCollapsible(R.id.headerBrightness, R.id.bodyBrightness, R.id.iconBrightness, true)
        setupCollapsible(R.id.headerTemperature, R.id.bodyTemperature, R.id.iconTemperature, false)
        setupCollapsible(R.id.headerLightLevel, R.id.bodyLightLevel, R.id.iconLightLevel, false)
        setupCollapsible(R.id.headerTime, R.id.bodyTime, R.id.iconTime, false)
        setupCollapsible(R.id.headerAutoLight, R.id.bodyAutoLight, R.id.iconAutoLight, false)

        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val btnBack = findViewById<TextView>(R.id.btnBack)
        val tvConnectionStatus = findViewById<TextView>(R.id.tvConnectionStatus)
        val tvSignalStrength = findViewById<TextView>(R.id.tvSignalStrength)
        val spinnerProxyAddress = findViewById<Spinner>(R.id.spinnerProxyAddress)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnAutoConnect = findViewById<Button>(R.id.btnAutoConnect)
        val tvDeviceInfo = findViewById<TextView>(R.id.tvDeviceInfo)
        val tvBrightnessValue = findViewById<TextView>(R.id.tvBrightnessValue)
        val seekBarBrightness = findViewById<SeekBar>(R.id.seekBarBrightness)
        val btnBrightnessDown = findViewById<Button>(R.id.btnBrightnessDown)
        val btnBrightnessUp = findViewById<Button>(R.id.btnBrightnessUp)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val spinnerGroup = findViewById<Spinner>(R.id.spinnerGroupSelect)
        val btnCreateGroup = findViewById<Button>(R.id.btnCreateGroup)

        // 设置标题
        val savedMac = getDeviceMac(device.address)
        tvTitle.text = if (savedMac != null) "${device.name}  $savedMac" else device.name

        // 返回按钮
        btnBack.setOnClickListener {
            finish()
        }

        // ========== 分组选择 Spinner ==========
        fun refreshGroupSpinner(selectGroupId: String? = null) {
            val groups = groupRepository.getAllGroups()
            val items = mutableListOf("无分组")
            items.addAll(groups.map { "${it.name} (0x${it.address.toString(16).uppercase()})" })
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerGroup.adapter = adapter

            // 选择设备当前所属分组
            if (selectGroupId != null) {
                val idx = groups.indexOfFirst { it.id == selectGroupId }
                if (idx >= 0) spinnerGroup.setSelection(idx + 1)
            } else {
                val currentGroups = groupRepository.getGroupsForDevice(device.id)
                if (currentGroups.isNotEmpty()) {
                    val idx = groups.indexOfFirst { it.id == currentGroups[0].id }
                    if (idx >= 0) spinnerGroup.setSelection(idx + 1)
                }
            }
        }

        refreshGroupSpinner()

        isInitialSelection = true
        spinnerGroup.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isInitialSelection) { isInitialSelection = false; return }
                val groups = groupRepository.getAllGroups()
                val prevGroups = groupRepository.getGroupsForDevice(device.id)

                if (position == 0) {
                    // 选择"无分组"：取消订阅所有分组
                    prevGroups.forEach { group ->
                        viewModel.unsubscribeDeviceFromGroup(device.address, group.address)
                        val updated = group.copy(memberDeviceIds = group.memberDeviceIds - device.id)
                        groupRepository.updateGroup(updated)
                    }
                    device.groupIds = mutableListOf()
                    device.groupAddress = null
                    deviceRepository.updateDevice(device)
                    Toast.makeText(this@DeviceDetailActivity, "已取消订阅所有分组", Toast.LENGTH_SHORT).show()
                } else {
                    val selectedGroup = groups.getOrNull(position - 1) ?: return
                    // 先取消旧分组
                    prevGroups.forEach { oldGroup ->
                        if (oldGroup.id != selectedGroup.id) {
                            viewModel.unsubscribeDeviceFromGroup(device.address, oldGroup.address)
                            val updated = oldGroup.copy(memberDeviceIds = oldGroup.memberDeviceIds - device.id)
                            groupRepository.updateGroup(updated)
                        }
                    }
                    // 订阅新分组
                    if (device.groupIds?.contains(selectedGroup.id) != true) {
                        viewModel.subscribeDeviceToGroup(device.address, selectedGroup.address)
                    }
                    val updatedGroup = selectedGroup.copy(
                        memberDeviceIds = (selectedGroup.memberDeviceIds + device.id).distinct()
                    )
                    groupRepository.updateGroup(updatedGroup)
                    device.groupIds = mutableListOf(selectedGroup.id)
                    device.groupAddress = selectedGroup.address
                    deviceRepository.updateDevice(device)
                    Toast.makeText(this@DeviceDetailActivity, "已加入分组：${selectedGroup.name}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 创建新分组
        btnCreateGroup.setOnClickListener {
            showCreateGroupDialog { newGroup ->
                groupRepository.addGroup(newGroup)
                refreshGroupSpinner(newGroup.id)
                isInitialSelection = true
                // 触发订阅
                viewModel.subscribeDeviceToGroup(device.address, newGroup.address)
                val updatedGroup = newGroup.copy(
                    memberDeviceIds = (newGroup.memberDeviceIds + device.id).distinct()
                )
                groupRepository.updateGroup(updatedGroup)
                device.groupIds = mutableListOf(newGroup.id)
                device.groupAddress = newGroup.address
                deviceRepository.updateDevice(device)
                Toast.makeText(this, "已创建并加入分组：${newGroup.name}", Toast.LENGTH_SHORT).show()
            }
        }

        // 清除配网信息按钮（长按 3 秒触发）
        val layoutNodeReset = findViewById<android.widget.FrameLayout>(R.id.layoutNodeReset)
        val btnNodeReset = findViewById<TextView>(R.id.btnNodeReset)
        val progressNodeReset = findViewById<ProgressBar>(R.id.progressNodeReset)
        var isHoldingForReset = false
        val resetHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var resetAnimator: android.animation.ObjectAnimator? = null

        val resetRunnable = Runnable {
            if (isHoldingForReset) {
                isHoldingForReset = false
                progressNodeReset.visibility = View.GONE
                progressNodeReset.progress = 0
                btnNodeReset.text = "长按清除配网信息"
                btnNodeReset.alpha = 1f

                AlertDialog.Builder(this)
                    .setTitle("确认清除配网信息")
                    .setMessage("确定要清除设备 ${device.name} 的配网信息吗？\n\n清除后设备将恢复为未配网状态，需要重新配网才能使用。")
                    .setPositiveButton("确定") { _, _ ->
                        viewModel.resetNode(device.address)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            deviceRepository.deleteDeviceByAddress(device.address)
                            Toast.makeText(this, "设备已移除，可重新配网", Toast.LENGTH_SHORT).show()
                            finish()
                        }, 3000)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        btnNodeReset.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isHoldingForReset = true
                    progressNodeReset.visibility = View.VISIBLE
                    progressNodeReset.progress = 0
                    btnNodeReset.text = "请保持按住..."
                    btnNodeReset.alpha = 0.6f
                    resetAnimator?.cancel()
                    resetAnimator = android.animation.ObjectAnimator.ofInt(progressNodeReset, "progress", 0, 100)
                    resetAnimator?.duration = 3000
                    resetAnimator?.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (isHoldingForReset) {
                                resetHandler.post(resetRunnable)
                            }
                        }
                    })
                    resetAnimator?.start()
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (isHoldingForReset) {
                        isHoldingForReset = false
                        resetAnimator?.cancel()
                        progressNodeReset.visibility = View.GONE
                        progressNodeReset.progress = 0
                        btnNodeReset.text = "长按清除配网信息"
                        btnNodeReset.alpha = 1f
                    }
                    true
                }
                else -> false
            }
        }

        // 设置 Spinner 数据
        setupProxySpinner(spinnerProxyAddress)
        
        // 设置设备 MAC 地址 Spinner
        
        // Spinner 选择监听 - 自动连接
        spinnerProxyAddress.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                Log.d("DeviceDetailActivity", "onItemSelected 被调用: position=$position, isInitialSelection=$isInitialSelection")

                // 跳过初始化时的自动触发
                if (isInitialSelection) {
                    isInitialSelection = false
                    Log.d("DeviceDetailActivity", "跳过初始选择")
                    return
                }

                val selectedItem = spinnerProxyAddress.selectedItem?.toString()
                Log.d("DeviceDetailActivity", "选择的项: $selectedItem")

                // 避免在初始化时触发连接
                if (selectedItem.isNullOrEmpty() || selectedItem == "未连接") return

                // 从 "名称 (MAC)" 格式中提取 MAC
                val selectedMac = extractMacFromItem(selectedItem)

                // 如果选择的是当前已连接的地址，不重复连接
                val currentAddress = viewModel.connectedDeviceAddress.value
                if (selectedMac == currentAddress) {
                    Log.d("DeviceDetailActivity", "已经连接到该地址")
                    return
                }

                // 选择了历史地址，自动连接
                viewModel.connectToAddress(selectedMac)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // 扫描按钮
        val btnScanProxy = findViewById<Button>(R.id.btnScanProxy)
        btnScanProxy.setOnClickListener {
            showProxyScanDialog()
        }
        
        // 连接按钮改为断开按钮
        btnConnect.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                // 断开前，禁用自动连接
                isInitialSelection = true
                viewModel.disconnectDevice()
            }
        }
        
        // 自动连接按钮
        btnAutoConnect.setOnClickListener {
            startAutoConnect()
        }
        
        // 设备信息
        tvDeviceInfo.text = if (savedMac != null) {
            """
            名称: ${device.name}
            地址: 0x${device.address.toString(16).uppercase()}
            类型: ${getDeviceTypeName(device.type)}
            MAC: $savedMac
            """.trimIndent()
        } else {
            """
            名称: ${device.name}
            地址: 0x${device.address.toString(16).uppercase()}
            类型: ${getDeviceTypeName(device.type)}
            """.trimIndent()
        }
        

        // 菜单按钮
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { anchor ->
            val popup = android.widget.PopupMenu(this, anchor)
            popup.menu.add(0, 1, 0, "修改名称")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        val input = android.widget.EditText(this).apply {
                            setText(device.name)
                            selectAll()
                        }
                        AlertDialog.Builder(this)
                            .setTitle("修改设备名称")
                            .setView(input)
                            .setPositiveButton("确定") { _, _ ->
                                val newName = input.text.toString().trim()
                                if (newName.isNotEmpty() && newName != device.name) {
                                    device.name = newName
                                    deviceRepository.updateDevice(device)
                                    val mac = getDeviceMac(device.address)
                                    tvTitle.text = if (mac != null) "$newName  $mac" else newName
                                    tvDeviceInfo.text = if (mac != null) {
                                        "名称: $newName\n地址: 0x${device.address.toString(16).uppercase()}\n类型: ${getDeviceTypeName(device.type)}\nMAC: $mac"
                                    } else {
                                        "名称: $newName\n地址: 0x${device.address.toString(16).uppercase()}\n类型: ${getDeviceTypeName(device.type)}"
                                    }
                                    Toast.makeText(this, "名称已更新", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
        // 亮度控制
        val savedBrightness = getSavedBrightness(device.address)
        device.brightness = savedBrightness // Update memory object
        seekBarBrightness.progress = savedBrightness
        tvBrightnessValue.text = "$savedBrightness%"
        
        seekBarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBrightnessValue.text = "$progress%"
                if (fromUser) {
                    val now = System.currentTimeMillis()
                    if (now - lastBrightnessSendTime >= brightnessThrottleMs) {
                        // 距上次发送已超过节流间隔，立即发送
                        val targetAddress = device.groupAddress ?: device.address
                        viewModel.sendBrightness(targetAddress, progress)
                        lastBrightnessSendTime = now
                        pendingBrightness = -1
                    } else {
                        // 节流期间暂存最新值，延迟发送
                        pendingBrightness = progress
                        brightnessHandler.removeCallbacksAndMessages(null)
                        brightnessHandler.postDelayed({
                            if (pendingBrightness >= 0) {
                                val targetAddress = device.groupAddress ?: device.address
                                viewModel.sendBrightness(targetAddress, pendingBrightness)
                                lastBrightnessSendTime = System.currentTimeMillis()
                                pendingBrightness = -1
                            }
                        }, brightnessThrottleMs)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 清除延迟任务，确保最后一次值被发送
                brightnessHandler.removeCallbacksAndMessages(null)
                seekBar?.progress?.let { progress ->
                    // 如果还有暂存值或进度有变化，发送最终值
                    val sendProgress = if (pendingBrightness >= 0) pendingBrightness else progress
                    val targetAddress = device.groupAddress ?: device.address
                    viewModel.sendBrightness(targetAddress, sendProgress)
                    lastBrightnessSendTime = System.currentTimeMillis()
                    pendingBrightness = -1

                    saveBrightness(device.address, progress)
                    device.brightness = progress
                }
            }
        })

        // 亮度减少按钮（每次 -1%）
        btnBrightnessDown.setOnClickListener {
            val currentProgress = seekBarBrightness.progress
            if (currentProgress > 0) {
                val newProgress = currentProgress - 1
                seekBarBrightness.progress = newProgress
                tvBrightnessValue.text = "$newProgress%"
                val targetAddress = device.groupAddress ?: device.address
                viewModel.sendBrightness(targetAddress, newProgress)
                saveBrightness(device.address, newProgress)
                device.brightness = newProgress
            }
        }

        // 亮度增加按钮（每次 +1%）
        btnBrightnessUp.setOnClickListener {
            val currentProgress = seekBarBrightness.progress
            if (currentProgress < 100) {
                val newProgress = currentProgress + 1
                seekBarBrightness.progress = newProgress
                tvBrightnessValue.text = "$newProgress%"
                val targetAddress = device.groupAddress ?: device.address
                viewModel.sendBrightness(targetAddress, newProgress)
                saveBrightness(device.address, newProgress)
                device.brightness = newProgress
            }
        }
        
        // 温度控制
        val tvTemperature = findViewById<TextView>(R.id.tvTemperatureValue)
        val btnRefreshTemp = findViewById<Button>(R.id.btnRefreshTemp)
        
        tvTemperature.text = "${String.format("%.1f", device.temperature)} °C"
        
        btnRefreshTemp.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.readSensors(device.address)
                Toast.makeText(this, "已发送传感器读取请求", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 光线亮度控制
        val tvLightLevel = findViewById<TextView>(R.id.tvLightLevelValue)
        val btnRefreshLightLevel = findViewById<Button>(R.id.btnRefreshLightLevel)
        
        device.lightLevel?.let { tvLightLevel.text = "${String.format("%.1f", it)} lux" }
            ?: run { tvLightLevel.text = "-- lux" }
        
        btnRefreshLightLevel.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.readSensors(device.address)
                Toast.makeText(this, "已发送传感器读取请求", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 时间同步控制
        val tvDeviceTime = findViewById<TextView>(R.id.tvDeviceTime)
        val btnReadTime = findViewById<Button>(R.id.btnReadTime)
        val btnSyncTime = findViewById<Button>(R.id.btnSyncTime)
        val btnRebindAppKey = findViewById<Button>(R.id.btnRebindAppKey)
        
        // 显示设备时间
        if (device.deviceTime != null) {
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            tvDeviceTime.text = "设备时间: ${dateFormat.format(java.util.Date(device.deviceTime!! * 1000))}"
        } else {
            tvDeviceTime.text = "设备时间: --"
        }
        
        btnReadTime.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.readDeviceTime(device.address)
                Toast.makeText(this, "已发送时间读取请求", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }
        
        btnSyncTime.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.setDeviceTime(device.address)
                Toast.makeText(this, "正在同步时间到设备...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        btnRebindAppKey.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                btnRebindAppKey.isEnabled = false
                viewModel.rebindAppKey(device.address)
                Toast.makeText(this, "正在重新绑定模型...", Toast.LENGTH_SHORT).show()
                // 5 秒后重新启用，防止重复点击
                btnRebindAppKey.postDelayed({
                    btnRebindAppKey.isEnabled = true
                }, 5000)
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 观察状态
        viewModel.statusText.observe(this) { status ->
            tvStatus.text = "状态: $status"
        }
        
        // 观察连接状态
        viewModel.isConnected.observe(this) { connected ->
            val spinnerProxyAddress = findViewById<Spinner>(R.id.spinnerProxyAddress)
            val btnAutoConnect = findViewById<Button>(R.id.btnAutoConnect)

            if (connected) {
                val mac = viewModel.connectedDeviceAddress.value
                val name = if (mac != null) viewModel.getDeviceNameForMac(mac) else null
                tvConnectionStatus.text = if (name != null) "已连接到 $name" else "已连接"
                tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                // 先显示"获取中..."，等 RSSI 更新到位后自动刷新
                tvSignalStrength.text = "📶 获取中..."
                tvSignalStrength.setTextColor(getColor(android.R.color.darker_gray))
                tvSignalStrength.visibility = View.VISIBLE
                btnConnect.text = "断开"
                btnConnect.visibility = View.VISIBLE
                btnAutoConnect.visibility = View.GONE
                btnRefreshTemp.isEnabled = true
                btnRefreshLightLevel.isEnabled = true
                btnReadTime.isEnabled = true
                btnSyncTime.isEnabled = true
                btnRebindAppKey.isEnabled = true
                findViewById<Button>(R.id.btnSaveRelay)?.isEnabled = true
                spinnerProxyAddress.isEnabled = false
            } else {
                tvSignalStrength.visibility = View.GONE
                tvConnectionStatus.text = "未连接"
                tvConnectionStatus.setTextColor(getColor(android.R.color.darker_gray))
                btnConnect.text = "断开"
                btnConnect.visibility = View.GONE
                btnAutoConnect.visibility = View.VISIBLE
                btnAutoConnect.isEnabled = true
                btnRefreshTemp.isEnabled = false
                btnRefreshLightLevel.isEnabled = false
                btnReadTime.isEnabled = false
                btnSyncTime.isEnabled = false
                btnRebindAppKey.isEnabled = false
                findViewById<Button>(R.id.btnSaveRelay)?.isEnabled = false
                spinnerProxyAddress.isEnabled = true

                // 刷新 Spinner 列表（可能有新的历史记录）
                isInitialSelection = true  // 重置标志，避免自动触发连接
                setupProxySpinner(spinnerProxyAddress)
            }
        }
        
        // 观察连接地址变化，更新 Spinner 选择
        viewModel.connectedDeviceAddress.observe(this) { address ->
            if (address != null) {
                val spinnerProxyAddress = findViewById<Spinner>(R.id.spinnerProxyAddress)
                val history = viewModel.getProxyAddressHistory()
                val index = history.indexOf(address)
                if (index >= 0) {
                    isInitialSelection = true  // 防止触发自动连接
                    spinnerProxyAddress.setSelection(index)
                }
            }
        }
        
        // 观察温度更新
        viewModel.temperatureUpdates.observe(this) { (address, temperature) ->
            if (address == device.address) {
                tvTemperature.text = "${String.format("%.1f", temperature)} °C"
                // 更新当前设备对象的缓存值
                device.temperature = temperature
                // 持久化保存到 Repository，以便主界面也能看到最新温度
                deviceRepository.updateDevice(device)
            }
        }
        
        // 观察光照度更新
        viewModel.lightLevelUpdates.observe(this) { (address, lux) ->
            if (address == device.address) {
                tvLightLevel.text = "${String.format("%.1f", lux)} lux"
                device.lightLevel = lux
                deviceRepository.updateDevice(device)
            }
        }
        
        // 观察时间更新
        viewModel.timeUpdates.observe(this) { (address, unixTime) ->
            if (address == device.address) {
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                tvDeviceTime.text = "设备时间: ${dateFormat.format(java.util.Date(unixTime * 1000))}"
                // 更新设备对象
                device.deviceTime = unixTime
                deviceRepository.updateDevice(device)
            }
        }

        // 定时开关（仅灯光设备）
        if (device.type == com.example.ble_device_mesh.data.DeviceType.LIGHT) {
            setupScheduleCard()
            setupAutoLightCard()

            // 添加定时任务管理按钮
            val btnSchedulerManager = findViewById<Button>(R.id.btnSchedulerManager)
            if (btnSchedulerManager != null) {
                btnSchedulerManager.visibility = View.VISIBLE
                btnSchedulerManager.setOnClickListener {
                    val intent = Intent(this, SchedulerListActivity::class.java).apply {
                        putExtra(SchedulerListActivity.EXTRA_DEVICE_ADDRESS, device.address)
                        putExtra(SchedulerListActivity.EXTRA_DEVICE_NAME, device.name)
                    }
                    startActivity(intent)
                }
            }

            // 添加路灯模式按钮
            val btnStreetlightMode = findViewById<Button>(R.id.btnStreetlightMode)
            if (btnStreetlightMode != null) {
                btnStreetlightMode.visibility = View.VISIBLE
                btnStreetlightMode.setOnClickListener {
                    val intent = Intent(this, StreetlightModeActivity::class.java).apply {
                        putExtra(StreetlightModeActivity.EXTRA_DEVICE_ADDRESS, device.address)
                        putExtra(StreetlightModeActivity.EXTRA_DEVICE_NAME, device.name)
                    }
                    startActivity(intent)
                }
            }
        }

        // 转发配置
        setupRelayConfigCard()

        // 手动触发一次 UI 更新，处理进入页面时已经连接的情况
        if (viewModel.isConnected.value == true) {
            tvConnectionStatus.text = "已连接"
            tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            tvSignalStrength.text = "📶 获取中..."
            tvSignalStrength.setTextColor(getColor(android.R.color.darker_gray))
            tvSignalStrength.visibility = View.VISIBLE
            btnConnect.text = "断开"
            btnConnect.visibility = View.VISIBLE
            findViewById<Button>(R.id.btnAutoConnect).visibility = View.GONE
            findViewById<Button>(R.id.btnRefreshTemp).isEnabled = true
            findViewById<Button>(R.id.btnRefreshLightLevel).isEnabled = true
            findViewById<Button>(R.id.btnReadTime).isEnabled = true
            findViewById<Button>(R.id.btnSyncTime).isEnabled = true
            findViewById<Button>(R.id.btnRebindAppKey).isEnabled = true
            findViewById<Button>(R.id.btnSaveRelay)?.isEnabled = true
            findViewById<Spinner>(R.id.spinnerProxyAddress).isEnabled = false
        }
    }
    
    private fun observeViewModel() {
        // 确保 RSSI 轮询已启动（处理重入场景）
        viewModel.ensureRssiUpdates()

        // 观察 RSSI 变化
        viewModel.getCurrentRssi().observe(this) { rssi ->
            val tvSignalStrength = findViewById<TextView>(R.id.tvSignalStrength)
            if (viewModel.isConnected.value == true && tvSignalStrength.visibility == View.VISIBLE) {
                updateSignalStrength(tvSignalStrength)
            }
        }
    }

    private fun setupScheduleCard() {
        val cardSchedule = findViewById<androidx.cardview.widget.CardView>(R.id.cardSchedule)
        cardSchedule.visibility = View.VISIBLE
        setupCollapsible(R.id.headerSchedule, R.id.bodySchedule, R.id.iconSchedule, false)

        // Scheduler 模型读取
        val btnReadScheduler = findViewById<Button>(R.id.btnReadScheduler)
        val tvSchedulerStatus = findViewById<TextView>(R.id.tvSchedulerStatus)
        val tvSchedulerDetails = findViewById<TextView>(R.id.tvSchedulerDetails)

        btnReadScheduler.setOnClickListener {
            Log.d("DeviceDetail", "读取计划按钮被点击")
            if (viewModel.isConnected.value == true) {
                Log.d("DeviceDetail", "设备已连接，开始读取计划")
                viewModel.readScheduler(device.address)
                tvSchedulerStatus.text = "读取中..."
                tvSchedulerDetails.visibility = View.GONE
            } else {
                Log.w("DeviceDetail", "设备未连接，无法读取计划")
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        // 观察 Scheduler 状态更新
        viewModel.schedulerUpdates.observe(this) { (address, schedules) ->
            if (address == device.address) {
                val setIndexes = mutableListOf<Int>()
                for (i in 0..15) {
                    if ((schedules and (1 shl i)) != 0) {
                        setIndexes.add(i)
                    }
                }

                if (setIndexes.isEmpty()) {
                    tvSchedulerStatus.text = "无计划"
                    tvSchedulerDetails.visibility = View.GONE
                } else {
                    tvSchedulerStatus.text = "读取中... (${setIndexes.size} 个计划)"
                    tvSchedulerDetails.text = "计划索引: ${setIndexes.joinToString(", ")}"
                    tvSchedulerDetails.visibility = View.VISIBLE
                }
            }
        }

        // 观察 Scheduler Action 详情更新
        viewModel.schedulerActionUpdates.observe(this) { (address, index, action) ->
            if (address == device.address) {
                // 解析时间和动作
                val hour = action.hour
                val minute = action.minute
                val dayOfWeek = action.dayOfWeek
                val actionType = action.action // 0=关, 1=开, 2=场景回调

                val timeStr = String.format("%02d:%02d", hour, minute)
                val repeatStr = when {
                    dayOfWeek == 0x7F -> "每天"
                    dayOfWeek == 0x00 -> "单次"
                    else -> {
                        val days = mutableListOf<String>()
                        if ((dayOfWeek and 0x01) != 0) days.add("一")
                        if ((dayOfWeek and 0x02) != 0) days.add("二")
                        if ((dayOfWeek and 0x04) != 0) days.add("三")
                        if ((dayOfWeek and 0x08) != 0) days.add("四")
                        if ((dayOfWeek and 0x10) != 0) days.add("五")
                        if ((dayOfWeek and 0x20) != 0) days.add("六")
                        if ((dayOfWeek and 0x40) != 0) days.add("日")
                        "周${days.joinToString(",")}"
                    }
                }

                // 更新状态显示
                val currentStatus = tvSchedulerStatus.text.toString()
                if (currentStatus.contains("读取中")) {
                    tvSchedulerStatus.text = "已读取计划详情"
                }

                Toast.makeText(this, "计划 #$index: $timeStr ($repeatStr) - ${if (actionType == 1) "开灯" else "关灯"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 设置光敏模式卡片
     * 使用标准模型 Generic OnOff (Element 1) + Generic Level (Element 1)
     */
    private fun setupAutoLightCard() {
        val cardAutoLight = findViewById<androidx.cardview.widget.CardView>(R.id.cardAutoLight)
        cardAutoLight.visibility = View.VISIBLE

        val switchAutoLight = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoLight)
        val seekBarThreshold = findViewById<SeekBar>(R.id.seekBarAutoLightThreshold)
        val tvThreshold = findViewById<TextView>(R.id.tvAutoLightThreshold)
        val seekBarBrightness = findViewById<SeekBar>(R.id.seekBarAutoLightBrightness)
        val tvBrightness = findViewById<TextView>(R.id.tvAutoLightBrightness)
        val tvState = findViewById<TextView>(R.id.tvAutoLightState)
        val btnRead = findViewById<Button>(R.id.btnReadAutoLight)

        // 光敏模式 Element 地址
        val elem1Addr = device.address + 1  // OnOff + Level(阈值)
        val elem2Addr = device.address + 2  // Level(亮度)

        // 默认状态
        switchAutoLight.isChecked = false
        seekBarThreshold.progress = 50
        tvThreshold.text = "50%"
        seekBarBrightness.progress = 100
        tvBrightness.text = "100%"
        tvState.text = "--"

        // 阈值滑块
        seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvThreshold.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (switchAutoLight.isChecked && viewModel.isConnected.value == true) {
                    sendAutoLightConfig()
                }
            }
        })

        // 开灯亮度滑块
        seekBarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val display = if (progress == 0) 1 else progress  // 至少 1%
                tvBrightness.text = "$display%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val brightness = if (seekBarBrightness.progress < 1) 1 else seekBarBrightness.progress
                if (viewModel.isConnected.value == true) {
                    viewModel.sendAutoLightBrightness(elem2Addr, brightness)
                }
            }
        })

        // 开关切换
        switchAutoLight.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.isConnected.value == true) {
                sendAutoLightConfig()
            }
        }

        // 读取状态按钮
        btnRead.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.readAutoLightMode(elem1Addr)
                Toast.makeText(this, "已发送读取请求", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        // 观察光敏模式状态（从 GenericOnOffStatus + GenericLevelStatus 合并更新）
        viewModel.getAutoLightStatus().observe(this) { (src, enabled, threshold) ->
            if (src == elem1Addr || src == device.address) {
                switchAutoLight.isChecked = enabled == 1
                seekBarThreshold.progress = threshold
                tvThreshold.text = "$threshold%"
                Log.d("DeviceDetail", "光敏模式状态已更新: enabled=$enabled, threshold=$threshold%")
            }
        }

        // 观察使能状态
        viewModel.getAutoLightEnabled().observe(this) { (src, enabled) ->
            if (src == elem1Addr) {
                switchAutoLight.isChecked = enabled == 1
            }
        }

        // 观察开灯亮度
        viewModel.getAutoLightBrightness().observe(this) { (src, brightness) ->
            if (src == elem2Addr) {
                val bri = brightness.coerceIn(1, 100)
                seekBarBrightness.progress = bri
                tvBrightness.text = "$bri%"
            }
        }

        // 连接状态改变时更新按钮启用
        viewModel.isConnected.observe(this) { connected ->
            btnRead.isEnabled = connected
            switchAutoLight.isEnabled = connected
            seekBarThreshold.isEnabled = connected
            seekBarBrightness.isEnabled = connected
        }
    }

    /**
     * 发送光敏模式配置
     * 光敏模式在 Element 1 上（地址 = 主地址 + 1）
     */
    private fun sendAutoLightConfig() {
        val switchAutoLight = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoLight)
        val seekBarThreshold = findViewById<SeekBar>(R.id.seekBarAutoLightThreshold)
        val enable = if (switchAutoLight.isChecked) 1 else 0
        val threshold = seekBarThreshold.progress
        // 光敏模式使用 Element 1 地址
        viewModel.sendAutoLightMode(device.address + 1, enable, threshold)
    }

    /**
     * 设置转发配置卡片
     */
    private fun setupRelayConfigCard() {
        setupCollapsible(R.id.headerRelay, R.id.bodyRelay, R.id.iconRelay, false)

        val seekbarCount = findViewById<SeekBar>(R.id.seekbarTransmitCount)
        val seekbarInterval = findViewById<SeekBar>(R.id.seekbarTransmitInterval)
        val tvCount = findViewById<TextView>(R.id.tvTransmitCount)
        val tvInterval = findViewById<TextView>(R.id.tvTransmitInterval)
        val btnSave = findViewById<Button>(R.id.btnSaveRelay)

        // 显示当前值
        tvCount.text = "${seekbarCount.progress + 1}"
        tvInterval.text = "${(seekbarInterval.progress + 1) * 10}ms"

        seekbarCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvCount.text = "${progress + 1}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekbarInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvInterval.text = "${(progress + 1) * 10}ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSave.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                val count = seekbarCount.progress
                val interval = seekbarInterval.progress
                val targetAddress = device.groupAddress ?: device.address

                // 设置网络发送次数和中继转发参数
                viewModel.setNetworkTransmit(targetAddress, count, interval)
                viewModel.setRelayConfig(targetAddress, 1, count, interval)  // relay=1 启用中继
                Toast.makeText(this, "转发设置已发送", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 提取 Spinner 选中项中的 MAC 地址
     * 格式可能是 "D4:8A:FC:12:34:56" 或 "客厅灯 (D4:8A:FC:12:34:56)"
     */
    private fun extractMacFromItem(item: String): String {
        val start = item.lastIndexOf('(')
        val end = item.lastIndexOf(')')
        return if (start >= 0 && end > start) {
            item.substring(start + 1, end)
        } else {
            item
        }
    }

    /**
     * 格式化 Spinner 显示项：优先显示设备名称
     */
    private fun formatSpinnerItem(mac: String): String {
        val name = viewModel.getDeviceNameForMac(mac)
        return if (name != null) "$name ($mac)" else mac
    }

    private fun setupProxySpinner(spinner: Spinner) {
        val history = viewModel.getProxyAddressHistory().toMutableList()

        // 显示设备名称，无名称时回退到 MAC
        val items = if (history.isEmpty()) {
            listOf("未连接")
        } else {
            history.map { formatSpinnerItem(it) }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // 如果当前已连接，选择当前连接的地址
        val currentAddress = viewModel.connectedDeviceAddress.value
        if (currentAddress != null && history.contains(currentAddress)) {
            val index = history.indexOf(currentAddress)
            spinner.setSelection(index)
        } else if (history.isNotEmpty()) {
            // 默认选择第一个历史地址
            spinner.setSelection(0)
        }

        // 重置标志，因为 setAdapter 会触发 onItemSelected
        isInitialSelection = true
    }

    private fun getDeviceMac(deviceAddress: Int): String? {
        // 从 DeviceRepository 查找该地址对应的 bluetoothMac
        val repoDevice = deviceRepository.getAllDevices().find { it.address == deviceAddress }
        if (repoDevice?.bluetoothMac != null) return repoDevice.bluetoothMac
        // 回退到旧的 SharedPreferences
        val prefs = getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("device_mac_0x${deviceAddress.toString(16)}", null)
    }

    private fun saveDeviceMac(deviceAddress: Int, macAddress: String) {
        device.bluetoothMac = macAddress
        deviceRepository.updateDevice(device)
        val prefs = getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("device_mac_0x${deviceAddress.toString(16)}", macAddress).apply()
    }
    
    private fun showProxyConnectionDialog() {
        val history = viewModel.getProxyAddressHistory()
        
        if (history.isEmpty()) {
            // 没有历史记录，直接扫描
            showProxyScanDialog()
            return
        }
        
        // 有历史记录，显示选择对话框
        val items = history.toTypedArray()
        val itemsWithScan = items + "扫描新设备..."
        
        AlertDialog.Builder(this)
            .setTitle("选择 Proxy 节点")
            .setItems(itemsWithScan) { _, which ->
                if (which < items.size) {
                    // 选择历史地址
                    val selectedAddress = items[which]
                    viewModel.connectToAddress(selectedAddress)
                } else {
                    // 扫描新设备
                    showProxyScanDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showProxyScanDialog() {
        Log.d("DeviceDetailActivity", "showProxyScanDialog 被调用")
        
        // 检查权限
        if (!hasAllPermissions()) {
            Log.d("DeviceDetailActivity", "权限不足，请求权限")
            checkAndRequestPermissions()
            return
        }
        
        // 检查蓝牙
        if (!checkBluetoothEnabled()) {
            Log.d("DeviceDetailActivity", "蓝牙未开启")
            return
        }

        // 检查定位（GPS）
        if (!checkLocationEnabled()) {
            Log.d("DeviceDetailActivity", "定位未开启")
            return
        }

        Log.d("DeviceDetailActivity", "权限、蓝牙、定位检查通过，显示扫描对话框")
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.item_device, null)
        val rvProxyDevices = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DeviceDetailActivity)
        }
        
        scanAdapter = DeviceAdapter(emptyList()) { scanResult ->
            viewModel.stopBleScan()
            viewModel.connectToDevice(scanResult)
            // 关闭对话框
            (rvProxyDevices.parent as? AlertDialog)?.dismiss()
        }
        rvProxyDevices.adapter = scanAdapter
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择 Mesh Proxy 节点")
            .setView(rvProxyDevices)
            .setNegativeButton("取消") { _, _ ->
                viewModel.stopBleScan()
            }
            .create()
        
        dialog.show()
        
        // 观察扫描结果
        viewModel.scannedDevices.observe(this) { devices ->
            scanAdapter.updateDevices(devices)
        }
        
        // 开始扫描
        startScanForProxy()
    }
    
    private fun startScanForProxy() {
        viewModel.startBleScan()
        Toast.makeText(this, "正在扫描 Proxy 节点...", Toast.LENGTH_SHORT).show()
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 不需要位置权限
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        
        val needRequest = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (needRequest.isNotEmpty()) {
            requestPermissions.launch(needRequest.toTypedArray())
        }
    }
    
    private fun hasAllPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 不需要位置权限
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        
        return permissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun checkBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show()
            return false
        }
        
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    /**
     * 检查手机定位（GPS）是否开启
     * BLE 扫描需要定位服务开启才能发现设备
     */
    private fun checkLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val isLocationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
        if (!isLocationEnabled) {
            AlertDialog.Builder(this)
                .setTitle("需要开启定位")
                .setMessage("BLE 扫描需要开启手机定位(GPS)功能才能发现设备。\n\n是否前往设置开启？")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return false
        }
        return true
    }

    private fun saveBrightness(address: Int, brightness: Int) {
        val prefs = getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("brightness_0x${address.toString(16)}", brightness).apply()
    }
    
    private fun getSavedBrightness(address: Int): Int {
        val prefs = getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        // 默认返回 0
        return prefs.getInt("brightness_0x${address.toString(16)}", 0)
    }
    
    private fun getDeviceTypeName(type: com.example.ble_device_mesh.data.DeviceType): String {
        return when (type) {
            com.example.ble_device_mesh.data.DeviceType.LIGHT -> "灯光"
            com.example.ble_device_mesh.data.DeviceType.SWITCH -> "开关"
            com.example.ble_device_mesh.data.DeviceType.SENSOR -> "传感器"
            com.example.ble_device_mesh.data.DeviceType.OTHER -> "其他"
        }
    }

    private fun updateSignalStrength(tvSignalStrength: TextView) {
        val rssi = viewModel.getCurrentRssi().value ?: -999

        when {
            rssi == -999 || rssi < -100 -> {
                // 无信号
                tvSignalStrength.text = "📶❌"
                tvSignalStrength.setTextColor(getColor(android.R.color.holo_red_dark))
            }
            rssi >= -50 -> {
                // 信号极强
                tvSignalStrength.text = "📶 ${rssi}dBm"
                tvSignalStrength.setTextColor(getColor(android.R.color.holo_green_dark))
            }
            rssi >= -70 -> {
                // 信号良好
                tvSignalStrength.text = "📶 ${rssi}dBm"
                tvSignalStrength.setTextColor(getColor(android.R.color.holo_green_light))
            }
            rssi >= -85 -> {
                // 信号一般
                tvSignalStrength.text = "📶 ${rssi}dBm"
                tvSignalStrength.setTextColor(getColor(android.R.color.holo_orange_light))
            }
            else -> {
                // 信号弱
                tvSignalStrength.text = "📶 ${rssi}dBm"
                tvSignalStrength.setTextColor(getColor(android.R.color.holo_red_light))
            }
        }
    }

    private fun showCreateGroupDialog(onCreated: (MeshGroup) -> Unit) {
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
                onCreated(group)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 构建 MAC 优先级列表：目标设备 → 同组设备 → 所有其他已配网设备
     */
    private fun buildPriorityMacList(): Triple<String?, List<String>, List<String>> {
        val targetMac = getDeviceMac(device.address)

        val groupMacs = mutableListOf<String>()
        val groupIds = device.groupIds
        if (groupIds != null) {
            val memberIds = groupIds.flatMap { gid ->
                groupRepository.getGroupById(gid)?.memberDeviceIds ?: emptyList()
            }.toSet()
            for (d in deviceRepository.getAllDevices()) {
                if (d.id in memberIds && d.id != device.id) {
                    val mac = getDeviceMac(d.address)
                    if (mac != null && mac !in groupMacs) groupMacs.add(mac)
                }
            }
        }

        val allOtherMacs = deviceRepository.getAllDevices()
            .filter { it.id != device.id }
            .mapNotNull { getDeviceMac(it.address) }
            .filter { it != targetMac && it !in groupMacs }
            .distinct()

        return Triple(targetMac, groupMacs, allOtherMacs)
    }

    /**
     * 自动连接：使用 MAC 优先级列表，依次尝试连接
     */
    private var isAutoConnecting = false

    private fun startAutoConnect() {
        if (isAutoConnecting) return

        // 如果已连接，检查是否就是目标设备
        val currentConnected = viewModel.connectedDeviceAddress.value
        val targetMac = getDeviceMac(device.address)
        if (currentConnected != null) {
            if (targetMac != null && currentConnected.equals(targetMac, ignoreCase = true)) {
                Toast.makeText(this, "已经连接到目标设备", Toast.LENGTH_SHORT).show()
                return
            }
            // 连接着其他设备，先断开
            Log.d("DeviceDetail", "当前连接 $currentConnected，断开后切换到目标列表")
            isAutoConnecting = true
            viewModel.disconnectDevice()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                doAutoConnect()
            }, 500)
            return
        }

        doAutoConnect()
    }

    private fun doAutoConnect() {
        isAutoConnecting = true

        val (targetMac, groupMacs, allOtherMacs) = buildPriorityMacList()
        val totalCount = (if (targetMac != null) 1 else 0) + groupMacs.size + allOtherMacs.size

        if (totalCount == 0) {
            Toast.makeText(this, "没有已配网设备的 MAC 地址", Toast.LENGTH_LONG).show()
            isAutoConnecting = false
            return
        }

        val btnAutoConnect = findViewById<Button>(R.id.btnAutoConnect)
        btnAutoConnect.isEnabled = false

        viewModel.connectToDeviceList(
            targetMac = targetMac,
            groupMacs = groupMacs,
            allOtherMacs = allOtherMacs,
            onConnected = { mac ->
                runOnUiThread {
                    isAutoConnecting = false
                    Log.d("DeviceDetail", "已连接到 $mac，开始监测目标设备 $targetMac")
                    startTargetMonitoring()
                }
            },
            onAllFailed = {
                runOnUiThread {
                    isAutoConnecting = false
                    btnAutoConnect.isEnabled = true
                    Toast.makeText(this, "所有设备均无法连接", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    /**
     * 连接成功后开启 BLE 扫描，监测目标设备是否出现。
     * 一旦扫描到目标设备 MAC，自动切换连接过去。
     */
    private fun startTargetMonitoring() {
        val mac = targetDeviceMac ?: return

        stopTargetMonitoring()

        viewModel.startBleScan()
        Log.d("DeviceDetail", "启动目标监测扫描: $mac")

        targetMonitorObserver = Observer { devices ->
            val found = devices.any { it.device.address.equals(mac, ignoreCase = true) }
            if (!found) return@Observer

            // 目标设备出现了！
            Log.d("DeviceDetail", "监测到目标设备 $mac，正在切换...")
            stopTargetMonitoring()

            val currentConnected = viewModel.connectedDeviceAddress.value
            if (currentConnected != null && currentConnected.equals(mac, ignoreCase = true)) {
                return@Observer // 已经连着目标
            }

            if (currentConnected != null) {
                viewModel.disconnectDevice()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    viewModel.connectToAddress(mac)
                }, 500)
            } else {
                viewModel.connectToAddress(mac)
            }
        }
        viewModel.scannedDevices.observe(this, targetMonitorObserver!!)
    }

    private fun stopTargetMonitoring() {
        if (targetMonitorObserver != null) {
            viewModel.scannedDevices.removeObserver(targetMonitorObserver!!)
            targetMonitorObserver = null
        }
        viewModel.stopBleScan()
    }
}
