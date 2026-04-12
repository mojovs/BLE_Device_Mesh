package com.example.ble_device_mesh

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.MeshDevice

class DeviceDetailActivity : ComponentActivity() {
    
    private val viewModel: MeshViewModel by viewModels()
    private lateinit var device: MeshDevice
    private lateinit var scanAdapter: DeviceAdapter
    private val deviceRepository by lazy { com.example.ble_device_mesh.data.DeviceRepository(this) }
    private var isInitialSelection = true  // 标记是否是初始化时的选择
    
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
        
        // 自动连接上次的 Proxy
        if (viewModel.isConnected.value != true && viewModel.hasSavedProxyAddress()) {
            Toast.makeText(this, "正在自动连接上次设备...", Toast.LENGTH_SHORT).show()
            viewModel.connectToSavedProxy()
        }
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

        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val btnBack = findViewById<TextView>(R.id.btnBack)
        val tvConnectionStatus = findViewById<TextView>(R.id.tvConnectionStatus)
        val spinnerProxyAddress = findViewById<Spinner>(R.id.spinnerProxyAddress)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnAutoConnect = findViewById<Button>(R.id.btnAutoConnect)
        val tvDeviceInfo = findViewById<TextView>(R.id.tvDeviceInfo)
        val spinnerDeviceMac = findViewById<Spinner>(R.id.spinnerDeviceMac)
        val tvBrightnessValue = findViewById<TextView>(R.id.tvBrightnessValue)
        val seekBarBrightness = findViewById<SeekBar>(R.id.seekBarBrightness)
        val btnBrightnessDown = findViewById<Button>(R.id.btnBrightnessDown)
        val btnBrightnessUp = findViewById<Button>(R.id.btnBrightnessUp)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        
        // 设置标题
        val savedMac = getDeviceMac(device.address)
        tvTitle.text = if (savedMac != null) "${device.name}  $savedMac" else device.name
        
        // 返回按钮
        btnBack.setOnClickListener {
            finish()
        }
        
        // 设置 Spinner 数据
        setupProxySpinner(spinnerProxyAddress)
        
        // 设置设备 MAC 地址 Spinner
        setupDeviceMacSpinner(spinnerDeviceMac)
        
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
                
                // 如果选择的是当前已连接的地址，不重复连接
                val currentAddress = viewModel.connectedDeviceAddress.value
                if (selectedItem == currentAddress) {
                    Log.d("DeviceDetailActivity", "已经连接到该地址")
                    return
                }
                
                // 选择了历史 MAC 地址，自动连接
                viewModel.connectToAddress(selectedItem)
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
            if (viewModel.isConnected.value == true) {
                Toast.makeText(this, "已经连接到设备", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val history = viewModel.getProxyAddressHistory()
            if (history.isEmpty()) {
                Toast.makeText(this, "没有历史连接记录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            Toast.makeText(this, "开始自动连接，尝试 ${history.size} 个设备...", Toast.LENGTH_SHORT).show()
            btnAutoConnect.isEnabled = false
            
            viewModel.autoConnectFromHistory {
                // 所有设备都连接失败
                runOnUiThread {
                    btnAutoConnect.isEnabled = true
                    Toast.makeText(this, "所有历史设备均无法连接", Toast.LENGTH_LONG).show()
                }
            }
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
                    // 实时发送控制指令
                    viewModel.sendBrightness(device.address, progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 滑动结束时保存亮度值
                seekBar?.progress?.let { progress ->
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
                viewModel.sendBrightness(device.address, newProgress)
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
                viewModel.sendBrightness(device.address, newProgress)
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
        
        // 观察状态
        viewModel.statusText.observe(this) { status ->
            tvStatus.text = "状态: $status"
        }
        
        // 观察连接状态
        viewModel.isConnected.observe(this) { connected ->
            val spinnerProxyAddress = findViewById<Spinner>(R.id.spinnerProxyAddress)
            val btnAutoConnect = findViewById<Button>(R.id.btnAutoConnect)
            
            if (connected) {
                tvConnectionStatus.text = "已连接"
                tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnConnect.text = "断开"
                btnConnect.visibility = View.VISIBLE
                btnAutoConnect.visibility = View.GONE
                btnRefreshTemp.isEnabled = true
                btnRefreshLightLevel.isEnabled = true
                btnReadTime.isEnabled = true
                btnSyncTime.isEnabled = true
                spinnerProxyAddress.isEnabled = false
            } else {
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
        }
    }
    
    private fun observeViewModel() {
    }
    

    private fun setupScheduleCard() {
        val cardSchedule = findViewById<androidx.cardview.widget.CardView>(R.id.cardSchedule)
        cardSchedule.visibility = View.VISIBLE
        setupCollapsible(R.id.headerSchedule, R.id.bodySchedule, R.id.iconSchedule, false)

        val tvOnTime = findViewById<TextView>(R.id.tvOnTime)
        val tvOffTime = findViewById<TextView>(R.id.tvOffTime)
        val tvOnRepeat = findViewById<TextView>(R.id.tvOnRepeat)
        val tvOffRepeat = findViewById<TextView>(R.id.tvOffRepeat)
        val prefs = getSharedPreferences("SchedulePrefs", android.content.Context.MODE_PRIVATE)
        val key = "device_${device.address}"

        // 恢复已保存的时间和模式
        tvOnTime.text = prefs.getString("${key}_on", null) ?: "未设置"
        tvOffTime.text = prefs.getString("${key}_off", null) ?: "未设置"
        tvOnRepeat.text = if (prefs.getBoolean("${key}_on_repeat", true)) "每天" else "单次"
        tvOffRepeat.text = if (prefs.getBoolean("${key}_off_repeat", true)) "每天" else "单次"

        // 点击切换模式
        tvOnRepeat.setOnClickListener {
            val repeat = prefs.getBoolean("${key}_on_repeat", true)
            prefs.edit().putBoolean("${key}_on_repeat", !repeat).apply()
            tvOnRepeat.text = if (!repeat) "每天" else "单次"
        }
        tvOffRepeat.setOnClickListener {
            val repeat = prefs.getBoolean("${key}_off_repeat", true)
            prefs.edit().putBoolean("${key}_off_repeat", !repeat).apply()
            tvOffRepeat.text = if (!repeat) "每天" else "单次"
        }

        findViewById<android.widget.Button>(R.id.btnSetOnTime).setOnClickListener {
            showTimePicker(true) { hour, minute ->
                val timeStr = String.format("%02d:%02d", hour, minute)
                tvOnTime.text = timeStr
                prefs.edit().putString("${key}_on", timeStr).apply()
                val repeat = prefs.getBoolean("${key}_on_repeat", true)
                scheduleAlarm(true, hour, minute, repeat)
            }
        }
        findViewById<android.widget.Button>(R.id.btnClearOnTime).setOnClickListener {
            tvOnTime.text = "未设置"
            prefs.edit().remove("${key}_on").apply()
            cancelAlarm(true)
        }
        findViewById<android.widget.Button>(R.id.btnSetOffTime).setOnClickListener {
            showTimePicker(false) { hour, minute ->
                val timeStr = String.format("%02d:%02d", hour, minute)
                tvOffTime.text = timeStr
                prefs.edit().putString("${key}_off", timeStr).apply()
                val repeat = prefs.getBoolean("${key}_off_repeat", true)
                scheduleAlarm(false, hour, minute, repeat)
            }
        }
        findViewById<android.widget.Button>(R.id.btnClearOffTime).setOnClickListener {
            tvOffTime.text = "未设置"
            prefs.edit().remove("${key}_off").apply()
            cancelAlarm(false)
        }
    }

    private fun showTimePicker(isOn: Boolean, onSet: (Int, Int) -> Unit) {
        val cal = java.util.Calendar.getInstance()
        android.app.TimePickerDialog(this, { _, hour, minute ->
            onSet(hour, minute)
            val label = if (isOn) "开机" else "关机"
            Toast.makeText(this, "定时${label}已设置: ${String.format("%02d:%02d", hour, minute)}", Toast.LENGTH_SHORT).show()
        }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
    }

    private fun scheduleAlarm(turnOn: Boolean, hour: Int, minute: Int, repeat: Boolean) {
        val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = android.content.Intent(this, ScheduleReceiver::class.java).apply {
            putExtra("device_address", device.address)
            putExtra("turn_on", turnOn)
            putExtra("brightness", device.brightness)
        }
        val requestCode = if (turnOn) device.address * 2 else device.address * 2 + 1
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this, requestCode, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        if (repeat) {
            alarmManager.setRepeating(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, android.app.AlarmManager.INTERVAL_DAY, pendingIntent)
        } else {
            alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
        }
    }

    private fun cancelAlarm(turnOn: Boolean) {
        val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = android.content.Intent(this, ScheduleReceiver::class.java)
        val requestCode = if (turnOn) device.address * 2 else device.address * 2 + 1
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this, requestCode, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

        private fun setupProxySpinner(spinner: Spinner) {
        val history = viewModel.getProxyAddressHistory().toMutableList()
        
        // 只显示历史地址
        val items = if (history.isEmpty()) {
            listOf("未连接")
        } else {
            history
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
    
    private fun setupDeviceMacSpinner(spinner: Spinner) {
        val history = viewModel.getProxyAddressHistory().toMutableList()
        
        if (history.isEmpty()) {
            spinner.visibility = View.GONE
            return
        }
        
        spinner.visibility = View.VISIBLE
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, history)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        
        // 读取该设备保存的 MAC 地址
        val savedMac = getDeviceMac(device.address)
        if (savedMac != null && history.contains(savedMac)) {
            val index = history.indexOf(savedMac)
            spinner.setSelection(index)
        } else {
            val defaultMac = history[0]
            saveDeviceMac(device.address, defaultMac)
            spinner.setSelection(0)
            findViewById<android.widget.TextView>(R.id.tvTitle).text = "${device.name}  $defaultMac"
        }

        // 选择监听 - 保存设备 MAC 地址
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var isFirstSelection = true
            
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isFirstSelection) {
                    isFirstSelection = false
                    return
                }
                
                val selectedMac = spinner.selectedItem?.toString()
                if (!selectedMac.isNullOrEmpty()) {
                    // 保存该设备的 MAC 地址
                    saveDeviceMac(device.address, selectedMac)
                    findViewById<TextView>(R.id.tvTitle).text = "${device.name}  $selectedMac"
                    Toast.makeText(this@DeviceDetailActivity, "已保存设备 MAC: $selectedMac", Toast.LENGTH_SHORT).show()
                    
                    // 更新设备信息显示
                    val tvDeviceInfo = findViewById<TextView>(R.id.tvDeviceInfo)
                    tvDeviceInfo.text = """
                        名称: ${device.name}
                        地址: 0x${device.address.toString(16).uppercase()}
                        类型: ${getDeviceTypeName(device.type)}
                        MAC: $selectedMac
                    """.trimIndent()
                }
            }
            
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
    
    private fun saveDeviceMac(deviceAddress: Int, macAddress: String) {
        val prefs = getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("device_mac_0x${deviceAddress.toString(16)}", macAddress).apply()
    }
    
    private fun getDeviceMac(deviceAddress: Int): String? {
        val prefs = getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("device_mac_0x${deviceAddress.toString(16)}", null)
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
        
        Log.d("DeviceDetailActivity", "权限和蓝牙检查通过，显示扫描对话框")
        
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
}
