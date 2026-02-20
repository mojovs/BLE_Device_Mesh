package com.example.ble_device_mesh

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.DeviceRepository
import com.example.ble_device_mesh.data.DeviceType
import com.example.ble_device_mesh.data.MeshDevice
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val viewModel: MeshViewModel by viewModels()
    private lateinit var deviceAdapter: MeshDeviceAdapter
    private lateinit var deviceRepository: DeviceRepository
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            
            deviceRepository = DeviceRepository(this)
            
            // 清除旧的温度数据，避免显示过期的信息
            try {
                deviceRepository.clearAllTemperatures()
            } catch (e: Exception) {
                Log.e("MainActivity", "清除温度失败: ${e.message}")
            }
            
            setupViews()
            loadDevices()
            startTemperaturePolling()
        } catch (e: Exception) {
            Log.e("MainActivity", "onCreate 严重错误: ${e.message}")
            e.printStackTrace()
            // 尝试显示 Toast
            try {
                 Toast.makeText(this, "应用启动异常: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {}
        }
    }
    
    private fun setupViews() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnAddDevice = findViewById<Button>(R.id.btnAddDevice)
        val rvDevices = findViewById<RecyclerView>(R.id.rvDevices)
        val layoutEmpty = findViewById<LinearLayout>(R.id.layoutEmpty)
        
        // 添加设置按钮（如果布局中有的话）
        findViewById<Button>(R.id.btnSettings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        

        
        // 设备列表
        deviceAdapter = MeshDeviceAdapter(
            emptyList(),
            onDeviceClick = { device ->
                openDeviceDetail(device)
            },
            onDeleteClick = { device ->
                showDeleteConfirmDialog(device)
            }
        )
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter
        
        // 添加设备按钮
        btnAddDevice.setOnClickListener {
            showAddDeviceDialog()
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
            }
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
        
        // 观察网络加载状态，加载完成后自动连接
        viewModel.isNetworkLoaded.observe(this) { loaded ->
            try {
                if (loaded) {
                    Log.d("MainActivity", "Mesh网络加载完毕，尝试自动连接...")
                    val connected = viewModel.isConnected.value ?: false
                    if (!connected) {
                        try {
                            if (viewModel.hasSavedProxyAddress()) {
                                Log.d("MainActivity", "尝试连接保存的接入点")
                                viewModel.connectToSavedProxy()
                            } else {
                                Log.d("MainActivity", "没有保存的接入点，扫描附近的 Proxy")
                                viewModel.autoConnectToProxy()
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "自动连接过程异常: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "isNetworkLoaded 观察出错: ${e.message}")
            }
        }
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
            .setMessage("确定要删除 ${device.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                deviceRepository.deleteDevice(device.id)
                loadDevices()
                Toast.makeText(this, "设备已删除", Toast.LENGTH_SHORT).show()
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
    }
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val temperatureRunnable = object : Runnable {
        override fun run() {
            try {
                if (isFinishing || isDestroyed) return
                
                val connected = viewModel.isConnected.value == true
                // Log.d("MainActivity", "温度轮询检查 - 连接状态: $connected")
                
                if (connected) {
                    val devices = deviceRepository.getAllDevices()
                    if (devices.isNotEmpty()) {
                        Log.d("MainActivity", "读取 ${devices.size} 个设备的温度...")
                        devices.forEach { device ->
                            viewModel.readTemperature(device.address)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "温度轮询出错: ${e.message}")
            } finally {
                handler.postDelayed(this, 30000) // 30秒后再次执行
            }
        }
    }

    private fun startTemperaturePolling() {
        Log.d("MainActivity", "启动温度轮询")
        handler.removeCallbacks(temperatureRunnable) // 避免重复
        
        // 5秒后每次执行
        handler.postDelayed(temperatureRunnable, 5000)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(temperatureRunnable)
    }
}