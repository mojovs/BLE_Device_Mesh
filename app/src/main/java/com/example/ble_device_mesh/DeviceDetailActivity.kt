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
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
    
    private fun setupViews() {
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val btnBack = findViewById<TextView>(R.id.btnBack)
        val tvConnectionStatus = findViewById<TextView>(R.id.tvConnectionStatus)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val tvDeviceInfo = findViewById<TextView>(R.id.tvDeviceInfo)
        val tvBrightnessValue = findViewById<TextView>(R.id.tvBrightnessValue)
        val seekBarBrightness = findViewById<SeekBar>(R.id.seekBarBrightness)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        
        // 设置标题
        tvTitle.text = device.name
        
        // 返回按钮
        btnBack.setOnClickListener {
            finish()
        }
        
        // 连接按钮
        btnConnect.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.disconnectDevice()
            } else {
                if (viewModel.hasSavedProxyAddress()) {
                    AlertDialog.Builder(this)
                        .setTitle("连接方式")
                        .setMessage("发现上次连接的记录，是否直接连接？")
                        .setPositiveButton("直接连接") { _, _ ->
                            viewModel.connectToSavedProxy()
                        }
                        .setNegativeButton("扫描新设备") { _, _ ->
                            showProxyScanDialog()
                        }
                        .show()
                } else {
                    showProxyScanDialog()
                }
            }
        }
        
        // 设备信息
        tvDeviceInfo.text = """
            名称: ${device.name}
            地址: 0x${device.address.toString(16).uppercase()}
            类型: ${getDeviceTypeName(device.type)}
        """.trimIndent()
        
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
        
        // 温度控制
        val tvTemperature = findViewById<TextView>(R.id.tvTemperatureValue)
        val btnRefreshTemp = findViewById<Button>(R.id.btnRefreshTemp)
        
        tvTemperature.text = "${String.format("%.1f", device.temperature)} °C"
        
        btnRefreshTemp.setOnClickListener {
            if (viewModel.isConnected.value == true) {
                viewModel.readTemperature(device.address)
                Toast.makeText(this, "已发送温度读取请求", Toast.LENGTH_SHORT).show()
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
            if (connected) {
                tvConnectionStatus.text = "已连接到 Proxy 节点"
                tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnConnect.text = "断开连接"
                btnRefreshTemp.isEnabled = true
            } else {
                tvConnectionStatus.text = "未连接"
                tvConnectionStatus.setTextColor(getColor(android.R.color.darker_gray))
                btnConnect.text = "连接 Proxy"
                btnRefreshTemp.isEnabled = false
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
    }
    
    private fun observeViewModel() {
    }
    
    private fun showProxyScanDialog() {
        // 检查权限
        if (!hasAllPermissions()) {
            checkAndRequestPermissions()
            return
        }
        
        // 检查蓝牙
        if (!checkBluetoothEnabled()) {
            return
        }
        
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
