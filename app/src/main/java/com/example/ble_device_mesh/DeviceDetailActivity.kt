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
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.GroupRepository
import com.example.ble_device_mesh.data.MeshDevice
import com.example.ble_device_mesh.data.MeshGroup
import com.example.ble_device_mesh.data.SchedulerRepository
import com.example.ble_device_mesh.data.SchedulerTask

class DeviceDetailActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private lateinit var device: MeshDevice
    private lateinit var scanAdapter: DeviceAdapter
    private val deviceRepository by lazy { com.example.ble_device_mesh.data.DeviceRepository(this) }
    private val groupRepository by lazy { GroupRepository(this) }
    private val schedulerRepository by lazy { SchedulerRepository(this) }
    private val schedulerReadTasks = mutableMapOf<Int, SchedulerTask>()
    private val pendingSchedulerIndexes = mutableSetOf<Int>()
    private var isProxyInitialSelection = true
    private var ignoreGroupSelection = false
    private var isGroupSelectionFromUser = false
    private val timeSyncHandler = Handler(Looper.getMainLooper())
    private var timeSyncTimeoutRunnable: Runnable? = null
    private var isTimeSyncInProgress = false

    // 亮度拖动节流
    private val brightnessHandler = Handler(Looper.getMainLooper())
    private var lastBrightnessSendTime = 0L
    private val brightnessThrottleMs = 120L
    private var pendingBrightness = -1  // 暂存的亮度值，用于延迟发送
    private var pendingNewPassword: String? = null  // PWD_SET 成功后写入本地存储
    private var devicePasswordIsSet = false  // 来自设备 PWD_STATUS 的 is_set
    private var devicePasswordStatusKnown = false  // 是否已收到过 PWD_STATUS
    private var autoLightConfigReady = false  // 是否已从设备读到光敏配置（未读到前禁止下发，避免覆盖阈值）
    private var syncingWorkModeUi = false     // 程序化同步工作模式 UI 时阻断子卡片的回发
    private var deviceWorkMode = 0            // 当前工作模式: 0=关闭, 1=雷达, 2=定时, 3=光敏
    private var lastModeActionAt = 0L         // 用户最近一次切换模式的时间（用于丢弃迟到的旧状态回包）
    private val resetFlowHandler = Handler(Looper.getMainLooper())
    private var pendingResetProbe = false  // 长按清除时正在探测固件版本
    private var firmwareProbeFailed = false  // 本次会话已确认设备不回版本（老固件）

    /** 探测固件版本超时：没有回应按老固件处理 */
    private val resetProbeTimeout = Runnable {
        if (pendingResetProbe) {
            pendingResetProbe = false
            firmwareProbeFailed = true
            confirmLegacyReset(device.firmwareVersion)
        }
    }
    
    companion object {
        const val EXTRA_DEVICE = "extra_device"

        /** 密码保护（以及 DEV_INFO 模型）从这个固件版本开始提供 */
        private const val MIN_FIRMWARE_WITH_PASSWORD = "1.0.0"
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
        timeSyncTimeoutRunnable?.let { timeSyncHandler.removeCallbacks(it) }
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

        // 整点报时卡片
        try { findViewById<View>(R.id.headerBuzzer) } catch (e: Exception) { null }?.let {
            setupCollapsible(R.id.headerBuzzer, R.id.bodyBuzzer, R.id.iconBuzzer, false)
        }

        // 雷达检测卡片
        try { findViewById<View>(R.id.headerRadar) } catch (e: Exception) { null }?.let {
            setupCollapsible(R.id.headerRadar, R.id.bodyRadar, R.id.iconRadar, false)
        }

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

            val targetPosition = if (selectGroupId != null) {
                val idx = groups.indexOfFirst { it.id == selectGroupId }
                if (idx >= 0) idx + 1 else 0
            } else {
                val currentGroups = groupRepository.getGroupsForDevice(device.id)
                val idx = currentGroups.firstOrNull()?.let { group ->
                    groups.indexOfFirst { it.id == group.id }
                } ?: -1
                if (idx >= 0) idx + 1 else 0
            }

            ignoreGroupSelection = true
            spinnerGroup.setSelection(targetPosition)
            spinnerGroup.post { ignoreGroupSelection = false }
        }

        ignoreGroupSelection = true
        refreshGroupSpinner()
        spinnerGroup.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                isGroupSelectionFromUser = true
            }
            false
        }
        spinnerGroup.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (ignoreGroupSelection || !isGroupSelectionFromUser) return
                isGroupSelectionFromUser = false
                val groups = groupRepository.getAllGroups()
                val prevGroups = groupRepository.getGroupsForDevice(device.id)
                val currentPosition = prevGroups.firstOrNull()?.let { group ->
                    val idx = groups.indexOfFirst { it.id == group.id }
                    if (idx >= 0) idx + 1 else 0
                } ?: 0
                if (position == currentPosition) return

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
                val updatedGroup = newGroup.copy(
                    memberDeviceIds = (newGroup.memberDeviceIds + device.id).distinct()
                )
                groupRepository.updateGroup(updatedGroup)
                device.groupIds = mutableListOf(newGroup.id)
                device.groupAddress = newGroup.address
                deviceRepository.updateDevice(device)
                ignoreGroupSelection = true
                refreshGroupSpinner(newGroup.id)
                viewModel.subscribeDeviceToGroup(device.address, newGroup.address)
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

                // 新固件设备端校验密码；老固件没有密码模型，直接走标准 Node Reset
                beginNodeReset()
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

        // ===== 设备密码 / 本机角色 =====
        val btnDevicePassword = findViewById<Button>(R.id.btnDevicePassword)
        val tvRoleNotice = findViewById<TextView>(R.id.tvRoleNotice)

        if (viewModel.isControlOnly()) {
            // 控制端：保留开关灯/调光，隐藏所有需要 Device Key 的操作
            layoutNodeReset.visibility = View.GONE
            btnDevicePassword.visibility = View.GONE
            findViewById<Button>(R.id.btnRebindAppKey).visibility = View.GONE
            tvRoleNotice.visibility = View.VISIBLE
        } else {
            tvRoleNotice.visibility = View.GONE
        }

        // 已知是老固件：没有密码模型，隐藏密码入口（点了也只会超时）
        device.firmwareVersion?.let { updatePasswordUiForFirmware(it) }

        btnDevicePassword.setOnClickListener {
            if (viewModel.isConnected.value != true) {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            } else if (device.firmwareVersion == null && firmwareProbeFailed) {
                Toast.makeText(this, "该设备固件较旧，不支持设备密码", Toast.LENGTH_SHORT).show()
            } else {
                showDevicePasswordDialog()
            }
        }

        // 设备回复密码状态：[is_set]
        viewModel.getPasswordStatus().observe(this) { (src, isSet) ->
            if (src == device.address) {
                devicePasswordIsSet = (isSet == 1)
                devicePasswordStatusKnown = true
                btnDevicePassword.text = if (isSet == 1)
                    "设备密码：已设置（点击修改）"
                else
                    "设备密码：默认 0000（点击修改）"
            }
        }

        // 密码命令结果（SET / RESET）
        viewModel.getPasswordOpResult().observe(this) { event ->
            val (src, op, rc) = event.getContentIfNotHandled() ?: return@observe
            if (src != device.address) return@observe
            when (op) {
                MeshViewModel.PWD_OP_RESET -> {
                    when (rc) {
                        MeshViewModel.PWD_RC_OK -> {
                            // 设备确认已清配网：此时才删除本地设备
                            deviceRepository.deleteDeviceByAddress(device.address)
                            deviceRepository.purgeDeletedByAddress(device.address)
                            PasswordStore.clear(this, device.address)
                            Toast.makeText(this, "设备已清除配网，可重新配网", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        MeshViewModel.PWD_RC_WRONG ->
                            Toast.makeText(this, "密码错误（连续 5 次错误将锁定 60 秒）", Toast.LENGTH_LONG).show()
                        MeshViewModel.PWD_RC_LOCKED ->
                            Toast.makeText(this, "密码错误次数过多，请 60 秒后再试", Toast.LENGTH_LONG).show()
                        MeshViewModel.PWD_RC_FORMAT ->
                            Toast.makeText(this, "密码格式错误：需 4 位数字/字母", Toast.LENGTH_SHORT).show()
                        MeshViewModel.PWD_RC_FORBIDDEN ->
                            Toast.makeText(this, "本机为控制端，不能清除配网", Toast.LENGTH_SHORT).show()
                        MeshViewModel.PWD_RC_TIMEOUT ->
                            Toast.makeText(this, "设备无响应，请靠近设备后重试", Toast.LENGTH_SHORT).show()
                        else ->
                            Toast.makeText(this, "清除失败（错误码 $rc）", Toast.LENGTH_SHORT).show()
                    }
                }
                MeshViewModel.PWD_OP_SET -> {
                    when (rc) {
                        MeshViewModel.PWD_RC_OK -> {
                            pendingNewPassword?.let { PasswordStore.save(this, device.address, it) }
                            pendingNewPassword = null
                            val isSet = PasswordStore.get(this, device.address) != null
                            devicePasswordIsSet = isSet
                            devicePasswordStatusKnown = true
                            btnDevicePassword.text = if (isSet)
                                "设备密码：已设置（点击修改）"
                            else
                                "设备密码：默认 0000（点击修改）"
                            Toast.makeText(this, "设备密码已更新", Toast.LENGTH_SHORT).show()
                        }
                        MeshViewModel.PWD_RC_WRONG -> {
                            pendingNewPassword = null
                            Toast.makeText(this, "当前密码错误", Toast.LENGTH_SHORT).show()
                        }
                        MeshViewModel.PWD_RC_LOCKED -> {
                            pendingNewPassword = null
                            Toast.makeText(this, "密码错误次数过多，请 60 秒后再试", Toast.LENGTH_LONG).show()
                        }
                        MeshViewModel.PWD_RC_FORMAT -> {
                            pendingNewPassword = null
                            Toast.makeText(this, "密码格式错误：需 4 位数字/字母", Toast.LENGTH_SHORT).show()
                        }
                        MeshViewModel.PWD_RC_TIMEOUT -> {
                            pendingNewPassword = null
                            Toast.makeText(this, "设备无响应，请重试", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            pendingNewPassword = null
                            Toast.makeText(this, "修改失败（错误码 $rc）", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // 设置 Spinner 数据
        setupProxySpinner(spinnerProxyAddress)
        
        // 设置设备 MAC 地址 Spinner
        
        // Spinner 选择监听 - 自动连接
        spinnerProxyAddress.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                Log.d("DeviceDetailActivity", "onItemSelected 被调用: position=$position, isProxyInitialSelection=$isProxyInitialSelection")

                // 跳过初始化时的自动触发
                if (isProxyInitialSelection) {
                    isProxyInitialSelection = false
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
                isProxyInitialSelection = true
                viewModel.disconnectDevice()
            }
        }
        
        // 自动连接按钮
        btnAutoConnect.setOnClickListener {
            startAutoConnect()
        }
        
        // 设备信息（含固件版本，收到设备回包后自动刷新）
        fun renderDeviceInfo() {
            val mac = getDeviceMac(device.address)
            val fw = device.firmwareVersion
            tvDeviceInfo.text = buildString {
                append("名称: ${device.name}")
                append("\n地址: 0x${device.address.toString(16).uppercase()}")
                append("\n类型: ${getDeviceTypeName(device.type)}")
                if (mac != null) append("\nMAC: $mac")
                append("\n固件: ${if (fw.isNullOrBlank()) "—" else "v$fw"}")
            }
        }
        renderDeviceInfo()
        

        // 菜单按钮
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { anchor ->
            val popup = android.widget.PopupMenu(this, anchor)
            popup.menu.add(0, 1, 0, "修改名称")
            popup.menu.add(0, 2, 1, "从列表移除（配网保留）")
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
                                    renderDeviceInfo()
                                    Toast.makeText(this, "名称已更新", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                        true
                    }
                    2 -> {
                        AlertDialog.Builder(this)
                            .setTitle("从列表移除")
                            .setMessage("只把 ${device.name} 从本地设备列表移除，设备仍留在 Mesh 网络中，\n可在「设置 → 设备管理 → 恢复已删除设备」里一键找回，无需重新配网。")
                            .setPositiveButton("移除") { _, _ ->
                                deviceRepository.deleteDevice(device.id)
                                Toast.makeText(this, "已移出列表，可在设置里找回", Toast.LENGTH_SHORT).show()
                                finish()
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

        // 顶部开关按钮（橘色=开 灰色=关）
        val btnPowerToggle = findViewById<TextView>(R.id.btnPowerToggle)
        var lastOnBrightness = if (savedBrightness > 0) savedBrightness else 100

        fun renderPowerToggle(isOn: Boolean) {
            btnPowerToggle.text = if (isOn) "开" else "关"
            btnPowerToggle.setBackgroundColor(
                android.graphics.Color.parseColor(if (isOn) "#FF6B35" else "#9E9E9E")
            )
        }
        renderPowerToggle(savedBrightness > 0)

        btnPowerToggle.setOnClickListener {
            val current = getSavedBrightness(device.address)
            val targetAddress = device.groupAddress ?: device.address
            if (current > 0) {
                // 记住关闭前的亮度，下次开启时恢复
                lastOnBrightness = current
                viewModel.sendOnOff(targetAddress, false)
                saveBrightness(device.address, 0)
                device.brightness = 0
                seekBarBrightness.progress = 0
                tvBrightnessValue.text = "0%"
                renderPowerToggle(false)
                Toast.makeText(this, "已关闭", Toast.LENGTH_SHORT).show()
            } else {
                val restore = lastOnBrightness.coerceIn(1, 100)
                viewModel.sendOnOff(targetAddress, true, restore)
                saveBrightness(device.address, restore)
                device.brightness = restore
                seekBarBrightness.progress = restore
                tvBrightnessValue.text = "$restore%"
                renderPowerToggle(true)
                Toast.makeText(this, "已开启 $restore%", Toast.LENGTH_SHORT).show()
            }
            deviceRepository.updateDevice(device)
        }
        
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
                    renderPowerToggle(progress > 0)
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
                renderPowerToggle(newProgress > 0)
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
                renderPowerToggle(newProgress > 0)
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
        
        device.lightLevel?.let { tvLightLevel.text = "${String.format("%.0f", it)}%" }
            ?: run { tvLightLevel.text = "--%" }
        
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
            if (viewModel.isConnected.value != true) {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            } else if (viewModel.isControlOnly()) {
                AlertDialog.Builder(this)
                    .setTitle("本机为控制端")
                    .setMessage("设备时间由管理员手机统一同步。\n用本机时间覆盖可能与其他手机冲突，确定继续吗？")
                    .setPositiveButton("仍要同步") { _, _ -> startTimeSync() }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                startTimeSync()
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
                updateOperationEnabled(connected = true)

                // 打开页面只读一次设备时间（不写）；写时间交给后台定时同步
                if (isConnectedToPageDevice()) {
                    viewModel.readDeviceTime(device.address)
                }

                // 连接成功后蜂鸣器响一声提示
                Handler(Looper.getMainLooper()).postDelayed({
                    viewModel.sendBuzzerBeep(device.address + 3, 50)
                }, 600)

                // 查询设备密码状态（是否已改过密码）
                Handler(Looper.getMainLooper()).postDelayed({
                    if (viewModel.isConnected.value == true) {
                        viewModel.sendPasswordGet(device.address)
                    }
                }, 1200)

                // 查询固件版本（设备信息厂商模型）
                Handler(Looper.getMainLooper()).postDelayed({
                    if (viewModel.isConnected.value == true) {
                        viewModel.sendDeviceInfoGet(device.address)
                    }
                }, 1400)

                // 查询工作模式（雷达/定时/光敏互斥状态）
                Handler(Looper.getMainLooper()).postDelayed({
                    if (viewModel.isConnected.value == true) {
                        viewModel.readWorkMode(device.address)
                    }
                }, 1600)
            } else {
                tvSignalStrength.visibility = View.GONE
                tvConnectionStatus.text = "未连接"
                tvConnectionStatus.setTextColor(getColor(android.R.color.darker_gray))
                btnConnect.text = "断开"
                btnConnect.visibility = View.GONE
                btnAutoConnect.visibility = View.VISIBLE
                finishTimeSync(showToast = false)
                updateOperationEnabled(connected = false)

                // 刷新 Spinner 列表（可能有新的历史记录）
                isProxyInitialSelection = true  // 重置标志，避免自动触发连接
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
                    isProxyInitialSelection = true  // 防止触发自动连接
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

        // 观察固件版本回包（写入设备信息栏）
        viewModel.getFirmwareVersionUpdates().observe(this) { (address, version) ->
            if (address == device.address) {
                device.firmwareVersion = version
                deviceRepository.updateDevice(device)
                renderDeviceInfo()
                updatePasswordUiForFirmware(version)

                // 长按清除时在等版本：按版本决定走密码还是标准 Node Reset
                if (pendingResetProbe) {
                    pendingResetProbe = false
                    resetFlowHandler.removeCallbacks(resetProbeTimeout)
                    if (firmwareSupportsPassword(version)) {
                        showResetPasswordDialog()
                    } else {
                        confirmLegacyReset(version)
                    }
                }
            }
        }
        
        // 观察光照度更新
        viewModel.lightLevelUpdates.observe(this) { (address, lux) ->
            if (address == device.address) {
                tvLightLevel.text = "${String.format("%.0f", lux)}%"
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
                if (isTimeSyncInProgress) {
                    finishTimeSync(showToast = true)
                }
            }
        }

        // 定时开关（仅灯光设备）
        if (device.type == com.example.ble_device_mesh.data.DeviceType.LIGHT) {
            setupScheduleCard()
            setupAutoLightCard()
            setupBuzzerCard()
            setupRadarCard()
            // 各子卡片的开关互相联动：打开一个模式自动关闭另外两个
            setupModeSync()

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
            updateOperationEnabled(connected = true)
            // 打开页面只读一次设备时间（不写）
            if (isConnectedToPageDevice()) {
                viewModel.readDeviceTime(device.address)
                viewModel.readWorkMode(device.address)
            }
        }
    }
    
    private fun isConnectedToPageDevice(): Boolean {
        val pageDeviceMac = getDeviceMac(device.address) ?: return false
        val connectedMac = viewModel.connectedDeviceAddress.value ?: return false
        return connectedMac.equals(pageDeviceMac, ignoreCase = true)
    }

    private fun startTimeSync() {
        if (isTimeSyncInProgress) return
        if (viewModel.isConnected.value != true) return
        if (!isConnectedToPageDevice()) {
            viewModel.statusText.postValue("当前连接的不是本页面设备，跳过时间同步")
            return
        }

        isTimeSyncInProgress = true
        findViewById<FrameLayout>(R.id.timeSyncOverlay).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvTimeSyncOverlayStatus).text = "正在同步时间..."
        updateOperationEnabled(connected = true)
        viewModel.setDeviceTime(device.address)
        Toast.makeText(this, "正在同步时间到设备...", Toast.LENGTH_SHORT).show()

        timeSyncTimeoutRunnable?.let { timeSyncHandler.removeCallbacks(it) }
        timeSyncTimeoutRunnable = Runnable {
            if (isTimeSyncInProgress) {
                finishTimeSync(showToast = false)
                viewModel.statusText.postValue("时间同步超时，可手动重试")
                Toast.makeText(this, "时间同步超时，可手动重试", Toast.LENGTH_SHORT).show()
            }
        }
        timeSyncHandler.postDelayed(timeSyncTimeoutRunnable!!, 5000)
    }

    private fun finishTimeSync(showToast: Boolean) {
        timeSyncTimeoutRunnable?.let { timeSyncHandler.removeCallbacks(it) }
        timeSyncTimeoutRunnable = null
        isTimeSyncInProgress = false
        findViewById<FrameLayout>(R.id.timeSyncOverlay).visibility = View.GONE
        updateOperationEnabled(connected = viewModel.isConnected.value == true)
        if (showToast) {
            Toast.makeText(this, "时间同步完成", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOperationEnabled(connected: Boolean) {
        val enabled = connected && !isTimeSyncInProgress
        findViewById<Button>(R.id.btnScanProxy).isEnabled = !connected && !isTimeSyncInProgress
        findViewById<Button>(R.id.btnConnect).isEnabled = enabled
        findViewById<Button>(R.id.btnAutoConnect).isEnabled = !connected && !isTimeSyncInProgress
        findViewById<Spinner>(R.id.spinnerProxyAddress).isEnabled = !connected && !isTimeSyncInProgress
        findViewById<Spinner>(R.id.spinnerGroupSelect).isEnabled = enabled
        findViewById<Button>(R.id.btnCreateGroup).isEnabled = enabled
        findViewById<SeekBar>(R.id.seekBarBrightness).isEnabled = enabled
        findViewById<Button>(R.id.btnBrightnessDown).isEnabled = enabled
        findViewById<Button>(R.id.btnBrightnessUp).isEnabled = enabled
        findViewById<Button>(R.id.btnRefreshTemp).isEnabled = enabled
        findViewById<Button>(R.id.btnRefreshLightLevel).isEnabled = enabled
        findViewById<Button>(R.id.btnReadTime).isEnabled = enabled
        findViewById<Button>(R.id.btnSyncTime).isEnabled = enabled
        findViewById<Button>(R.id.btnRebindAppKey).isEnabled = enabled
        findViewById<Button>(R.id.btnReadScheduler)?.isEnabled = enabled
        findViewById<Button>(R.id.btnSchedulerManager)?.isEnabled = enabled
        findViewById<Button>(R.id.btnStreetlightMode)?.isEnabled = enabled
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoLight)?.isEnabled = enabled
        findViewById<SeekBar>(R.id.seekBarAutoLightThreshold)?.isEnabled = enabled
        findViewById<SeekBar>(R.id.seekBarAutoLightBrightness)?.isEnabled = enabled
        findViewById<Button>(R.id.btnReadAutoLight)?.isEnabled = enabled
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchBuzzerChime)?.isEnabled = enabled
        findViewById<SeekBar>(R.id.seekBarBuzzerVolume)?.isEnabled = enabled
        findViewById<Button>(R.id.btnTestBuzzer)?.isEnabled = enabled
        findViewById<Button>(R.id.btnReadBuzzer)?.isEnabled = enabled
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchRadarEnable)?.isEnabled = enabled
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchScheduleEnable)?.isEnabled = enabled
        findViewById<SeekBar>(R.id.seekBarRadarDuration)?.isEnabled = enabled
        findViewById<SeekBar>(R.id.seekBarRadarBrightness)?.isEnabled = enabled
        findViewById<Button>(R.id.btnRadarNightStart)?.isEnabled = enabled
        findViewById<Button>(R.id.btnRadarNightEnd)?.isEnabled = enabled
        findViewById<Button>(R.id.btnRadarReadTimes)?.isEnabled = enabled
        findViewById<Button>(R.id.btnRadarReadConfig)?.isEnabled = enabled
        findViewById<SeekBar>(R.id.seekbarTransmitCount)?.isEnabled = enabled
        findViewById<SeekBar>(R.id.seekbarTransmitInterval)?.isEnabled = enabled
        findViewById<Button>(R.id.btnSaveRelay)?.isEnabled = enabled
        findViewById<TextView>(R.id.btnNodeReset).isEnabled = enabled
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

    /**
     * 工作模式联动：雷达 / 定时 / 光敏 三选一互斥
     *
     * 不额外增加“工作模式”卡片，直接复用各卡片自己的开关：
     * 打开任意一个模式开关，另外两个自动关闭（参数保留）。
     */
    private fun setupModeSync() {
        deviceWorkMode = device.workMode.coerceIn(0, 3)

        // 设备回报（切换模式、重连后读取）
        viewModel.getWorkMode().observe(this) { (src, mode) ->
            if (src == device.address) {
                if (isModeStatusStale()) {
                    // 切换前发出的读取请求，回包可能晚于用户操作，丢弃
                    Log.d("DeviceDetail", "丢弃迟到的工作模式回包: $mode")
                    return@observe
                }
                Log.d("DeviceDetail", "设备工作模式回报: $mode")
                syncModeSwitches(mode)
            }
        }

        // 先用本地缓存渲染一次，避免等待回包期间显示错误状态
        syncModeSwitches(deviceWorkMode)
    }

    /**
     * 同步两个基础模式开关的显示状态（不发送任何 Mesh 命令）。
     * 定时和光敏互斥；雷达是独立叠加开关，这里不动它。
     */
    private fun syncModeSwitches(mode: Int) {
        // 旧的 1（雷达模式）归一化为“基础关闭”，雷达由独立开关表示
        var m = mode.coerceIn(0, 3)
        if (m == MeshViewModel.WORK_MODE_RADAR) m = MeshViewModel.WORK_MODE_OFF
        val timerOn = (m == MeshViewModel.WORK_MODE_TIMER)
        val lightOn = (m == MeshViewModel.WORK_MODE_AUTO_LIGHT)

        deviceWorkMode = m
        val changed = device.workMode != m ||
                device.autoLightEnabled != lightOn
        device.workMode = m
        device.autoLightEnabled = lightOn
        if (changed) deviceRepository.updateDevice(device)

        syncingWorkModeUi = true
        try {
            findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchScheduleEnable)?.isChecked =
                timerOn
            findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoLight)?.isChecked =
                lightOn
        } finally {
            syncingWorkModeUi = false
        }
    }

    /**
     * 用户主动切换基础模式后的统一处理：
     * 1. 记录操作时间，此后一段时间内丢弃迟到的旧状态回包
     * 2. 本地定时/光敏开关立即联动
     * 3. 切到定时时显式关闭光敏使能（双保险，兼容旧固件）
     * 4. 1.8s 后回读基础模式，以设备实际状态再校正一次界面
     * 雷达是独立叠加层，不在这里处理。
     */
    private fun applyUserMode(mode: Int) {
        lastModeActionAt = SystemClock.elapsedRealtime()
        Log.d("DeviceDetail", "用户切换模式: $mode -> 同步开关并关闭其他模式")
        syncModeSwitches(mode)
        sendOtherModesOff(mode)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed && viewModel.isConnected.value == true) {
                viewModel.readWorkMode(device.address)
            }
        }, 1800)
    }

    /**
     * 基础模式互斥的显式兜底：切到“定时”时关闭光敏使能。
     * 雷达是独立叠加层，不受基础模式影响，所以这里不碰雷达。
     */
    private fun sendOtherModesOff(mode: Int) {
        if (mode == MeshViewModel.WORK_MODE_TIMER) {
            // 只关使能，不改阈值，避免覆盖设备里已保存的光敏阈值
            Log.d("DeviceDetail", "模式互斥: 关闭光敏使能")
            viewModel.sendAutoLightDisable(device.address + 1)
        }
    }

    /** 是否应该丢弃这条状态回包：刚切换过模式时，旧的回包还在路上 */
    private fun isModeStatusStale(): Boolean {
        return SystemClock.elapsedRealtime() - lastModeActionAt < 1500
    }

    private fun setupScheduleCard() {
        val cardSchedule = findViewById<androidx.cardview.widget.CardView>(R.id.cardSchedule)
        cardSchedule.visibility = View.VISIBLE
        setupCollapsible(R.id.headerSchedule, R.id.bodySchedule, R.id.iconSchedule, false)

        // 定时模式开关：打开后选中“定时”模式，自动关闭雷达/光敏
        val switchScheduleEnable =
            findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchScheduleEnable)
        switchScheduleEnable.setOnCheckedChangeListener { _, isChecked ->
            if (syncingWorkModeUi) return@setOnCheckedChangeListener
            if (viewModel.isConnected.value != true) {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
                syncModeSwitches(deviceWorkMode)
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                viewModel.sendWorkMode(device.address, MeshViewModel.WORK_MODE_TIMER)
                applyUserMode(MeshViewModel.WORK_MODE_TIMER)
            } else if (deviceWorkMode == MeshViewModel.WORK_MODE_TIMER) {
                viewModel.sendWorkMode(device.address, MeshViewModel.WORK_MODE_OFF)
                applyUserMode(MeshViewModel.WORK_MODE_OFF)
            }
        }

        // Scheduler 模型读取
        val btnReadScheduler = findViewById<Button>(R.id.btnReadScheduler)
        val tvSchedulerStatus = findViewById<TextView>(R.id.tvSchedulerStatus)
        val tvSchedulerDetails = findViewById<TextView>(R.id.tvSchedulerDetails)

        btnReadScheduler.setOnClickListener {
            Log.d("DeviceDetail", "读取计划按钮被点击")
            if (viewModel.isConnected.value == true) {
                Log.d("DeviceDetail", "设备已连接，开始读取计划")
                schedulerReadTasks.clear()
                pendingSchedulerIndexes.clear()
                viewModel.readAllSchedulerTasks(device.address)
                tvSchedulerStatus.text = "正在读取计划..."
                tvSchedulerDetails.visibility = View.GONE
            } else {
                Log.w("DeviceDetail", "设备未连接，无法读取计划")
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        // 观察 Scheduler 状态更新
        viewModel.schedulerUpdates.observe(this) { (address, schedules) ->
            if (address == device.address) {
                val setIndexes = (0..15).filter { (schedules and (1 shl it)) != 0 }
                pendingSchedulerIndexes.clear()
                pendingSchedulerIndexes.addAll(setIndexes)
                schedulerReadTasks.keys.retainAll(setIndexes.toSet())

                if (setIndexes.isEmpty()) {
                    tvSchedulerStatus.text = "设备暂无定时任务"
                    tvSchedulerDetails.visibility = View.GONE
                } else {
                    tvSchedulerStatus.text = "正在读取 ${setIndexes.size} 个任务详情..."
                    tvSchedulerDetails.text = "计划索引: ${setIndexes.joinToString(", ") { "#$it" }}"
                    tvSchedulerDetails.visibility = View.VISIBLE
                    updateSchedulerTaskDetails(tvSchedulerStatus, tvSchedulerDetails)
                }
            }
        }

        // 观察 Scheduler Action 详情更新
        viewModel.schedulerTaskUpdates.observe(this) { task ->
            if (task.deviceAddress == device.address || task.deviceAddress == 0) {
                val updatedTask = task.copy(deviceAddress = device.address)
                schedulerReadTasks[updatedTask.index] = updatedTask
                pendingSchedulerIndexes.remove(updatedTask.index)
                schedulerRepository.upsertTask(device.address, updatedTask)
                updateSchedulerTaskDetails(tvSchedulerStatus, tvSchedulerDetails)
            }
        }
    }

    private fun updateSchedulerTaskDetails(tvSchedulerStatus: TextView, tvSchedulerDetails: TextView) {
        if (schedulerReadTasks.isEmpty()) return

        val tasks = schedulerReadTasks.values.sortedBy { it.index }
        tvSchedulerDetails.text = tasks.joinToString("\n") { task ->
            val enabledText = if (task.enabled) "已启用" else "已禁用"
            "#${task.index} ${task.getTimeString()} ${task.getRepeatDescription()} ${task.getActionDescription()} $enabledText"
        }
        tvSchedulerDetails.visibility = View.VISIBLE

        tvSchedulerStatus.text = if (pendingSchedulerIndexes.isEmpty()) {
            "已读取 ${tasks.size} 个任务"
        } else {
            "已读取 ${tasks.size} 个任务，等待 ${pendingSchedulerIndexes.size} 个详情..."
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

        // 默认状态：优先使用上次从设备读到的缓存
        autoLightConfigReady = device.autoLightConfigured
        val initialThreshold = if (autoLightConfigReady) device.autoLightThreshold.coerceIn(0, 100) else 50
        val initialBrightness = if (autoLightConfigReady) device.autoLightBrightness.coerceIn(1, 100) else 100
        switchAutoLight.isChecked = autoLightConfigReady && device.autoLightEnabled
        seekBarThreshold.progress = initialThreshold
        tvThreshold.text = "$initialThreshold%"
        seekBarBrightness.progress = initialBrightness
        tvBrightness.text = "$initialBrightness%"
        tvState.text = "--"

        // 未成功读到设备配置前禁止修改：否则 App 会用默认 50% 阈值覆盖设备实际配置
        fun refreshAutoLightEnabledState() {
            val connected = viewModel.isConnected.value == true
            val configurable = connected && !isTimeSyncInProgress && autoLightConfigReady
            switchAutoLight.isEnabled = configurable
            seekBarThreshold.isEnabled = configurable
            seekBarBrightness.isEnabled = configurable
            btnRead.isEnabled = connected && !isTimeSyncInProgress
        }

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
                    // 缓存的是 UI 百分比；设备里存的是 OC6701 映射后的值
                    device.autoLightBrightness = brightness
                    deviceRepository.updateDevice(device)
                    viewModel.sendAutoLightBrightness(elem2Addr, brightness)
                }
            }
        })

        // 开关切换（用 flag 防止程序化更新触发重复发送）
        var updatingAutoLightSwitch = false
        switchAutoLight.setOnCheckedChangeListener { _, isChecked ->
            if (!updatingAutoLightSwitch && !syncingWorkModeUi && viewModel.isConnected.value == true) {
                val sent = sendAutoLightConfig()
                // 打开光敏 = 选中光敏模式；关闭时若当前就是光敏模式则回到"关闭"
                if (sent && isChecked) {
                    applyUserMode(MeshViewModel.WORK_MODE_AUTO_LIGHT)
                } else if (sent && deviceWorkMode == MeshViewModel.WORK_MODE_AUTO_LIGHT) {
                    applyUserMode(MeshViewModel.WORK_MODE_OFF)
                }
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

        // 观察光敏模式状态：仅更新阈值（开关由 getAutoLightEnabled 独立控制）
        // 只认 Element 1（主地址+1）：主灯(Element 0)的 Level Status 不能当成阈值。
        viewModel.getAutoLightStatus().observe(this) { (src, enabled, threshold) ->
            if (src == elem1Addr) {
                autoLightConfigReady = true
                val t = threshold.coerceIn(0, 100)
                device.autoLightThreshold = t
                device.autoLightConfigured = true
                seekBarThreshold.progress = t
                tvThreshold.text = "$t%"
                deviceRepository.updateDevice(device)
                refreshAutoLightEnabledState()
                Log.d("DeviceDetail", "光敏模式阈值已更新: threshold=$t%")
            }
        }

        // 观察使能状态
        viewModel.getAutoLightEnabled().observe(this) { (src, enabled) ->
            if (src == elem1Addr) {
                if (isModeStatusStale()) {
                    // 刚切换过模式，这条可能是切换前发出的旧回包，丢弃以免把开关改回去
                    Log.d("DeviceDetail", "丢弃迟到的光敏状态回包: enabled=$enabled")
                    return@observe
                }
                updatingAutoLightSwitch = true
                switchAutoLight.isChecked = enabled == 1
                updatingAutoLightSwitch = false
                device.autoLightEnabled = enabled == 1
                deviceRepository.updateDevice(device)
                // 设备侧光敏使能就是工作模式镜像：同步另外两个模式开关
                if (enabled == 1 && deviceWorkMode != MeshViewModel.WORK_MODE_AUTO_LIGHT) {
                    syncModeSwitches(MeshViewModel.WORK_MODE_AUTO_LIGHT)
                } else if (enabled == 0 && deviceWorkMode == MeshViewModel.WORK_MODE_AUTO_LIGHT) {
                    syncModeSwitches(MeshViewModel.WORK_MODE_OFF)
                }
                com.example.ble_device_mesh.MeshViewModel.MeshState.statusText.postValue("光敏模式: ${if (enabled == 1) "已开启" else "已关闭"}")
            }
        }

        // 观察开灯亮度
        viewModel.getAutoLightBrightness().observe(this) { (src, brightness) ->
            if (src == elem2Addr) {
                val devicePct = brightness.coerceIn(1, 100)
                // 设备里存的是 OC6701 补偿后的百分比，界面显示的是 UI 百分比。
                // 若设备值与当前 UI 的映射一致就保持 UI 不变，避免读取时滑块跳变。
                val cachedUi = device.autoLightBrightness.coerceIn(1, 100)
                val ui = if (autoLightConfigReady && MeshViewModel.mapBrightnessForOC6701(cachedUi) == devicePct) {
                    cachedUi
                } else {
                    MeshViewModel.unmapBrightnessForOC6701(devicePct)
                }
                seekBarBrightness.progress = ui
                tvBrightness.text = "$ui%"
                device.autoLightBrightness = ui
                deviceRepository.updateDevice(device)
            }
        }

        // 连接状态改变时更新按钮启用
        viewModel.isConnected.observe(this) { connected ->
            refreshAutoLightEnabledState()
            if (connected && !autoLightConfigReady) {
                // 首次打开/无本地缓存：自动读取一次设备配置，
                // 读到阈值后才允许操作，避免用默认值覆盖设备配置。
                Handler(Looper.getMainLooper()).postDelayed({
                    if (viewModel.isConnected.value == true && !autoLightConfigReady) {
                        viewModel.readAutoLightMode(elem1Addr)
                    }
                }, 600)
            }
        }
    }

    /**
     * 发送光敏模式配置
     * 光敏模式在 Element 1 上（地址 = 主地址 + 1）
     */
    private fun sendAutoLightConfig(): Boolean {
        val switchAutoLight = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoLight)
        val seekBarThreshold = findViewById<SeekBar>(R.id.seekBarAutoLightThreshold)
        val enable = if (switchAutoLight.isChecked) 1 else 0
        val threshold = seekBarThreshold.progress
        // 未读到设备配置前不要下发，避免把默认阈值写到设备
        if (!autoLightConfigReady) {
            Toast.makeText(this, "正在读取设备配置，请稍后再试", Toast.LENGTH_SHORT).show()
            viewModel.readAutoLightMode(device.address + 1)
            return false
        }
        device.autoLightEnabled = switchAutoLight.isChecked
        device.autoLightThreshold = threshold
        deviceRepository.updateDevice(device)
        // 光敏模式使用 Element 1 地址
        viewModel.sendAutoLightMode(device.address + 1, enable, threshold)
        return true
    }

    /**
     * 设置整点报时卡片
     * Element 3（地址 = 主地址 + 3）：
     *   Generic OnOff Server: 报时开关
     *   Generic Level Server: 音量 0-100
     */
    private fun setupBuzzerCard() {
        val cardBuzzer = try { findViewById<androidx.cardview.widget.CardView>(R.id.cardBuzzer) } catch (e: Exception) { null } ?: return
        cardBuzzer.visibility = View.VISIBLE

        val switchChime = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchBuzzerChime)
        val seekBarVolume = findViewById<SeekBar>(R.id.seekBarBuzzerVolume)
        val tvVolume = findViewById<TextView>(R.id.tvBuzzerVolume)
        val btnTest = findViewById<Button>(R.id.btnTestBuzzer)
        val btnRead = findViewById<Button>(R.id.btnReadBuzzer)

        val elem3Addr = device.address + 3  // 蜂鸣器 Element 地址

        // 默认状态
        switchChime.isChecked = device.hourlyChimeEnabled
        seekBarVolume.progress = device.buzzerVolume.coerceIn(0, 100)
        tvVolume.text = "${device.buzzerVolume}%"

        // 音量滑块
        seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolume.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (viewModel.isConnected.value == true) {
                    viewModel.sendBuzzerVolume(elem3Addr, seekBarVolume.progress)
                    device.buzzerVolume = seekBarVolume.progress
                    deviceRepository.updateDevice(device)
                }
            }
        })

        // 报时开关
        switchChime.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.isConnected.value == true) {
                viewModel.sendBuzzerChimeMode(elem3Addr, if (isChecked) 1 else 0)
                device.hourlyChimeEnabled = isChecked
                deviceRepository.updateDevice(device)
            }
        }

        // 测试蜂鸣按钮
        btnTest.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                // 先设置音量再触发测试蜂鸣
                viewModel.sendBuzzerVolume(elem3Addr, seekBarVolume.progress)
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        // 读取状态按钮
        btnRead.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.readBuzzerConfig(elem3Addr)
                Toast.makeText(this, "已发送读取请求", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        // 观察整点报时状态
        viewModel.getBuzzerChimeEnabled().observe(this) { (src, enabled) ->
            if (src == elem3Addr) {
                switchChime.isChecked = enabled == 1
                device.hourlyChimeEnabled = enabled == 1
                deviceRepository.updateDevice(device)
                com.example.ble_device_mesh.MeshViewModel.MeshState.statusText.postValue("整点报时: ${if (enabled == 1) "已开启" else "已关闭"}")
            }
        }

        // 观察音量状态
        viewModel.getBuzzerVolume().observe(this) { (src, volume) ->
            if (src == elem3Addr) {
                val vol = volume.coerceIn(0, 100)
                seekBarVolume.progress = vol
                tvVolume.text = "$vol%"
                device.buzzerVolume = vol
                deviceRepository.updateDevice(device)
            }
        }

        // 连接状态改变时更新按钮启用
        viewModel.isConnected.observe(this) { connected ->
            btnTest.isEnabled = connected && !isTimeSyncInProgress
            btnRead.isEnabled = connected && !isTimeSyncInProgress
            switchChime.isEnabled = connected && !isTimeSyncInProgress
            seekBarVolume.isEnabled = connected && !isTimeSyncInProgress
        }
    }

    /**
     * 设置雷达检测卡片
     * Element 4（地址 = 主地址 + 4）：
     *   Generic OnOff Server: 雷达检测开关
     *   Generic Level Server: 夜晚亮灯时长 0-200 (×0.1分钟)
     *   Vendor Model: 检测时间列表、夜晚时段配置
     */
    private fun setupRadarCard() {
        val cardRadar = try { findViewById<androidx.cardview.widget.CardView>(R.id.cardRadar) } catch (e: Exception) { null } ?: return
        cardRadar.visibility = View.VISIBLE

        val switchEnable = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchRadarEnable)
        val seekBarDuration = findViewById<SeekBar>(R.id.seekBarRadarDuration)
        val tvDuration = findViewById<TextView>(R.id.tvRadarDuration)
        val btnDurationDown = findViewById<Button>(R.id.btnRadarDurationDown)
        val btnDurationUp = findViewById<Button>(R.id.btnRadarDurationUp)
        val seekBarBrightness = findViewById<SeekBar>(R.id.seekBarRadarBrightness)
        val tvBrightness = findViewById<TextView>(R.id.tvRadarBrightness)
        val btnStart = findViewById<Button>(R.id.btnRadarNightStart)
        val btnEnd = findViewById<Button>(R.id.btnRadarNightEnd)
        val tvTimes = findViewById<TextView>(R.id.tvRadarTimes)
        val btnReadTimes = findViewById<Button>(R.id.btnRadarReadTimes)
        val btnReadConfig = findViewById<Button>(R.id.btnRadarReadConfig)
        val switchSound = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchRadarSound)
        val seekBarVolume = findViewById<SeekBar>(R.id.seekBarRadarVolume)
        val tvVolume = findViewById<TextView>(R.id.tvRadarVolume)

        val radarRepo = com.example.ble_device_mesh.data.RadarDetectionRepository(applicationContext)
        val elem4Addr = device.address + 4  // 雷达 Element 地址
        val elem5Addr = device.address + 5  // 雷达亮灯亮度 Element 地址

        fun updateNightHourButtons(start: Int, end: Int) {
            btnStart.text = "%02d:00".format(start.coerceIn(0, 23))
            btnEnd.text = "%02d:00".format(end.coerceIn(0, 23))
        }

        // 时长显示：不足 1 分钟时直接显示秒数，方便细调到十几秒
        fun formatRadarDuration(durationX10: Int): String =
            if (durationX10 < 10) "${durationX10 * 6} 秒"
            else "%.1f 分钟".format(durationX10 / 10.0)

        // 统一处理时长变化：更新 UI、本地记忆，并按需下发到设备
        fun applyRadarDuration(value: Int, send: Boolean) {
            val v = value.coerceIn(0, 200)
            seekBarDuration.progress = v
            tvDuration.text = formatRadarDuration(v)
            device.radarNightDurationX10 = v
            deviceRepository.updateDevice(device)
            if (send && viewModel.isConnected.value == true) {
                viewModel.sendRadarNightDuration(elem4Addr, v)
            }
        }

        fun showRadarNightHourDialog(title: String, current: Int, onSelected: (Int) -> Unit) {
            val values = Array(24) { "%02d:00".format(it) }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(values, current.coerceIn(0, 23)) { dialog, which ->
                    onSelected(which)
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        fun showRadarTimesDialog(times: List<Pair<Int, Int>>) {
            val message = if (times.isEmpty()) {
                "当天暂无检测记录"
            } else {
                times.joinToString("\n") { "%02d:%02d".format(it.first, it.second) }
            }
            AlertDialog.Builder(this)
                .setTitle("检测到人的时间列表")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show()
        }

        // 默认状态
        switchEnable.isChecked = device.radarEnabled
        seekBarDuration.progress = device.radarNightDurationX10.coerceIn(0, 200)
        tvDuration.text = formatRadarDuration(device.radarNightDurationX10.coerceIn(0, 200))
        seekBarBrightness.progress = device.radarNightBrightness.coerceIn(1, 100)
        tvBrightness.text = "${device.radarNightBrightness.coerceIn(1, 100)}%"
        switchSound.isChecked = device.radarSoundEnabled
        seekBarVolume.progress = device.radarSoundVolume.coerceIn(0, 100)
        tvVolume.text = "${device.radarSoundVolume.coerceIn(0, 100)}%"
        updateNightHourButtons(device.radarNightStartHour, device.radarNightEndHour)

        // 显示本地已有记录数
        val localRecords = radarRepo.getRecords(device.address)
        tvTimes.text = if (localRecords.isEmpty()) "尚未读取" else "本地共 ${localRecords.size} 条记录"

        // 时长滑块
        seekBarDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvDuration.text = formatRadarDuration(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (viewModel.isConnected.value == true) {
                    viewModel.sendRadarNightDuration(elem4Addr, seekBarDuration.progress)
                    device.radarNightDurationX10 = seekBarDuration.progress
                    deviceRepository.updateDevice(device)
                }
            }
        })

        // 时长细调按钮（每次 ±1 = 0.1 分钟）
        btnDurationDown.setOnClickListener {
            applyRadarDuration(seekBarDuration.progress - 1, send = true)
        }
        btnDurationUp.setOnClickListener {
            applyRadarDuration(seekBarDuration.progress + 1, send = true)
        }

        // 亮度滑块
        seekBarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val brightness = progress.coerceAtLeast(1)
                tvBrightness.text = "$brightness%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val brightness = seekBarBrightness.progress.coerceAtLeast(1)
                seekBarBrightness.progress = brightness
                if (viewModel.isConnected.value == true) {
                    viewModel.sendRadarNightBrightness(elem5Addr, brightness)
                    device.radarNightBrightness = brightness
                    deviceRepository.updateDevice(device)
                }
            }
        })

        // 启用开关（独立叠加层：不改变定时/光敏基础模式）
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            if (syncingWorkModeUi) return@setOnCheckedChangeListener
            if (viewModel.isConnected.value == true) {
                viewModel.sendRadarEnable(elem4Addr, if (isChecked) 1 else 0)
                device.radarEnabled = isChecked
                deviceRepository.updateDevice(device)
                Log.d("DeviceDetail", "雷达叠加开关: $isChecked (基础模式=$deviceWorkMode)")
            }
        }

        // 检测提示音开关
        switchSound.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.isConnected.value == true) {
                viewModel.sendRadarSoundSet(elem4Addr, if (isChecked) 1 else 0, device.radarSoundVolume)
                device.radarSoundEnabled = isChecked
                deviceRepository.updateDevice(device)
            }
        }

        // 提示音音量滑块
        seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolume.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (viewModel.isConnected.value == true) {
                    val enabledInt = if (device.radarSoundEnabled) 1 else 0
                    viewModel.sendRadarSoundSet(elem4Addr, enabledInt, seekBarVolume.progress)
                    device.radarSoundVolume = seekBarVolume.progress
                    deviceRepository.updateDevice(device)
                }
            }
        })

        // 夜晚时段选择
        btnStart.setOnClickListener {
            showRadarNightHourDialog("开始时间", device.radarNightStartHour) { selected ->
                val end = device.radarNightEndHour.coerceIn(0, 23)
                updateNightHourButtons(selected, end)
                if (viewModel.isConnected.value == true) {
                    viewModel.sendRadarNightHoursSet(elem4Addr, selected, end)
                    device.radarNightStartHour = selected
                    device.radarNightEndHour = end
                    deviceRepository.updateDevice(device)
                }
            }
        }
        btnEnd.setOnClickListener {
            showRadarNightHourDialog("结束时间", device.radarNightEndHour) { selected ->
                val start = device.radarNightStartHour.coerceIn(0, 23)
                updateNightHourButtons(start, selected)
                if (viewModel.isConnected.value == true) {
                    viewModel.sendRadarNightHoursSet(elem4Addr, start, selected)
                    device.radarNightStartHour = start
                    device.radarNightEndHour = selected
                    deviceRepository.updateDevice(device)
                }
            }
        }

        // 读取检测时间
        btnReadTimes.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.sendRadarTimesGet(elem4Addr)
                Toast.makeText(this, "已发送读取请求", Toast.LENGTH_SHORT).show()
            } else {
                val records = radarRepo.getRecords(device.address)
                val times = records.map { Pair(it.hour, it.minute) }
                showRadarTimesDialog(times)
            }
        }

        // 读取配置
        btnReadConfig.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.readRadarConfig(elem4Addr)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    viewModel.sendRadarNightHoursGet(elem4Addr)
                }, 900)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    viewModel.sendRadarSoundGet(elem4Addr)
                }, 1300)
                Toast.makeText(this, "已发送读取请求", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        // 观察雷达启用状态
        viewModel.getRadarStatus().observe(this) { (src, enabled) ->
            if (src == elem4Addr) {
                if (isModeStatusStale()) {
                    // 刚切换过模式，这条可能是切换前发出的旧回包，丢弃以免把雷达开关改回开
                    Log.d("DeviceDetail", "丢弃迟到的雷达状态回包: enabled=$enabled")
                    return@observe
                }
                // 程序化同步开关状态，避免触发 switch 监听回发命令
                val prevSyncing = syncingWorkModeUi
                syncingWorkModeUi = true
                switchEnable.isChecked = enabled == 1
                syncingWorkModeUi = prevSyncing
                device.radarEnabled = enabled == 1
                deviceRepository.updateDevice(device)
            }
        }

        // 观察夜晚亮灯时长
        viewModel.getRadarNightDuration().observe(this) { (src, durationX10) ->
            if (src == elem4Addr) {
                val d = durationX10.coerceIn(0, 200)
                seekBarDuration.progress = d
                tvDuration.text = formatRadarDuration(d)
                device.radarNightDurationX10 = d
                deviceRepository.updateDevice(device)
            }
        }

        // 观察雷达亮灯亮度
        viewModel.getRadarNightBrightness().observe(this) { (src, brightness) ->
            if (src == elem5Addr) {
                val devicePct = brightness.coerceIn(1, 100)
                // 设备里存的是 OC6701 补偿后的百分比，界面显示的是 UI 百分比。
                // 若设备值与当前 UI 的映射值一致就保持 UI 不变，避免滑块跳变。
                val ui = if (MeshViewModel.mapBrightnessForOC6701(
                        device.radarNightBrightness.coerceIn(1, 100)) == devicePct) {
                    device.radarNightBrightness.coerceIn(1, 100)
                } else {
                    MeshViewModel.unmapBrightnessForOC6701(devicePct)
                }
                seekBarBrightness.progress = ui
                tvBrightness.text = "$ui%"
                device.radarNightBrightness = ui
                deviceRepository.updateDevice(device)
            }
        }

        // 观察检测时间列表（固件当天记录）
        viewModel.getRadarTimes().observe(this) { (src, times) ->
            if (src == elem4Addr) {
                if (times.isEmpty()) {
                    tvTimes.text = "当天暂无检测记录"
                } else {
                    val timeStr = times.joinToString("\n") { "%02d:%02d".format(it.first, it.second) }
                    tvTimes.text = "当天检测 (${times.size}次):\n$timeStr"
                }
                showRadarTimesDialog(times)
                // 导入到本地持久化
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                radarRepo.importDeviceRecords(device.address, today, times)
            }
        }

        // 观察夜晚时段
        viewModel.getRadarNightHours().observe(this) { (src, hours) ->
            if (src == elem4Addr) {
                val startH = hours.first.coerceIn(0, 23)
                val endH = hours.second.coerceIn(0, 23)
                updateNightHourButtons(startH, endH)
                device.radarNightStartHour = startH
                device.radarNightEndHour = endH
                deviceRepository.updateDevice(device)
            }
        }

        // 观察检测提示音配置
        viewModel.getRadarSound().observe(this) { (src, sound) ->
            if (src == elem4Addr) {
                val enabled = sound.first == 1
                val vol = sound.second.coerceIn(0, 100)
                switchSound.isChecked = enabled
                seekBarVolume.progress = vol
                tvVolume.text = "$vol%"
                device.radarSoundEnabled = enabled
                device.radarSoundVolume = vol
                deviceRepository.updateDevice(device)
            }
        }

        // 连接状态改变时更新按钮启用
        viewModel.isConnected.observe(this) { connected ->
            btnReadTimes.isEnabled = connected && !isTimeSyncInProgress
            btnReadConfig.isEnabled = connected && !isTimeSyncInProgress
            switchEnable.isEnabled = connected && !isTimeSyncInProgress
            seekBarDuration.isEnabled = connected && !isTimeSyncInProgress
            btnDurationDown.isEnabled = connected && !isTimeSyncInProgress
            btnDurationUp.isEnabled = connected && !isTimeSyncInProgress
            seekBarBrightness.isEnabled = connected && !isTimeSyncInProgress
            btnStart.isEnabled = connected && !isTimeSyncInProgress
            btnEnd.isEnabled = connected && !isTimeSyncInProgress
            switchSound.isEnabled = connected && !isTimeSyncInProgress
            seekBarVolume.isEnabled = connected && !isTimeSyncInProgress
        }
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
        isProxyInitialSelection = true
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

    /**
     * 清除配网入口：按固件版本决定要不要密码。
     * - 新固件（>= 1.0.0）：弹密码框，由设备端校验
     * - 老固件（没有密码模型）：直接发标准 Node Reset
     */
    private fun beginNodeReset() {
        if (viewModel.isControlOnly()) {
            Toast.makeText(this, "本机为控制端，不能清除配网", Toast.LENGTH_SHORT).show()
            return
        }
        if (viewModel.isConnected.value != true) {
            Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            return
        }

        val version = device.firmwareVersion
        if (version != null) {
            if (firmwareSupportsPassword(version)) {
                showResetPasswordDialog()
            } else {
                confirmLegacyReset(version)
            }
            return
        }

        // 版本还没查到：现场探测一次，避免把新固件误判成老固件
        if (firmwareProbeFailed) {
            confirmLegacyReset(version)
            return
        }
        pendingResetProbe = true
        resetFlowHandler.removeCallbacks(resetProbeTimeout)
        resetFlowHandler.postDelayed(resetProbeTimeout, 2500)
        viewModel.sendDeviceInfoGet(device.address)
        Toast.makeText(this, "正在识别设备固件版本...", Toast.LENGTH_SHORT).show()
    }

    /** 固件版本比较：>= [MIN_FIRMWARE_WITH_PASSWORD] 才有密码保护 */
    private fun firmwareSupportsPassword(version: String): Boolean {
        val parts = version.substringBefore('+').trim()
            .split('.')
            .mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return false
        val required = MIN_FIRMWARE_WITH_PASSWORD.split('.').map { it.toInt() }
        for (i in required.indices) {
            val value = parts.getOrElse(i) { 0 }
            if (value != required[i]) return value > required[i]
        }
        return true
    }

    /** 老固件没有密码模型，标准 Node Reset 直接生效 */
    private fun confirmLegacyReset(version: String?) {
        val versionText = version?.let { "固件 v$it" } ?: "固件版本低于 v$MIN_FIRMWARE_WITH_PASSWORD"
        AlertDialog.Builder(this)
            .setTitle("清除配网信息")
            .setMessage(
                "设备 ${device.name} 的$versionText，不支持密码保护。\n\n" +
                    "将直接清除配网信息，设备恢复未配网状态，需要重新配网才能使用。"
            )
            .setPositiveButton("清除") { _, _ -> doLegacyReset() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doLegacyReset() {
        viewModel.resetNode(device.address)
        Toast.makeText(this, "正在清除配网信息...", Toast.LENGTH_SHORT).show()

        // 老固件收到标准 Node Reset 会立刻复位、退出网络，稍等再清理本地记录
        resetFlowHandler.postDelayed({
            deviceRepository.deleteDeviceByAddress(device.address)
            deviceRepository.purgeDeletedByAddress(device.address)
            PasswordStore.clear(this, device.address)
            Toast.makeText(this, "设备已清除配网，可重新配网", Toast.LENGTH_SHORT).show()
            finish()
        }, 4000)
    }

    /** 老固件没有密码模型：隐藏密码入口 */
    private fun updatePasswordUiForFirmware(version: String) {
        if (!firmwareSupportsPassword(version)) {
            findViewById<Button>(R.id.btnDevicePassword).visibility = View.GONE
        }
    }

    /** 清除配网：输入设备密码，由设备端校验 */
    private fun showResetPasswordDialog() {
        if (viewModel.isControlOnly()) {
            Toast.makeText(this, "本机为控制端，不能清除配网", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            hint = "4 位设备密码"
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            val saved = PasswordStore.get(this@DeviceDetailActivity, device.address)
            if (saved != null) {
                setText(saved)
            } else if (devicePasswordStatusKnown && !devicePasswordIsSet) {
                // 只在确认设备仍使用默认密码时预填
                setText("0000")
            }
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("清除配网信息")
            .setMessage("设备 ${device.name} 需要验证密码。\n\n清除后设备恢复未配网状态，需要重新配网才能使用。\n忘记密码？长按设备按键 10 秒可恢复出厂。")
            .setView(container)
            .setPositiveButton("清除") { _, _ ->
                val pwd = input.text.toString()
                if (!MeshViewModel.isValidPassword(pwd)) {
                    Toast.makeText(this, "请输入 4 位数字/字母", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.resetNodeWithPassword(device.address, pwd)
                    Toast.makeText(this, "正在验证密码...", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 设置/修改设备密码（需要旧密码） */
    private fun showDevicePasswordDialog() {
        if (viewModel.isControlOnly()) {
            Toast.makeText(this, "本机为控制端，不能修改设备密码", Toast.LENGTH_SHORT).show()
            return
        }

        val stored = PasswordStore.get(this, device.address)
        val saved = stored ?: if (devicePasswordStatusKnown && !devicePasswordIsSet) "0000" else ""

        val oldInput = EditText(this).apply {
            hint = "当前密码"
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(saved)
            setSelection(text.length)
        }
        val newInput = EditText(this).apply {
            hint = "新密码（设为 0000 取消密码）"
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(this@DeviceDetailActivity).apply { text = "当前密码" })
            addView(oldInput)
            addView(TextView(this@DeviceDetailActivity).apply {
                text = "新密码"
                setPadding(0, pad, 0, 0)
            })
            addView(newInput)
        }

        AlertDialog.Builder(this)
            .setTitle("设备密码")
            .setMessage("密码用于保护“清除配网”。开关灯、调光等日常控制不需要密码。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val oldPwd = oldInput.text.toString()
                val newPwd = newInput.text.toString()
                if (!MeshViewModel.isValidPassword(oldPwd) || !MeshViewModel.isValidPassword(newPwd)) {
                    Toast.makeText(this, "密码需为 4 位数字/字母", Toast.LENGTH_SHORT).show()
                } else if (viewModel.setDevicePassword(device.address, oldPwd, newPwd)) {
                    pendingNewPassword = newPwd
                    Toast.makeText(this, "正在修改密码...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "发送失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
