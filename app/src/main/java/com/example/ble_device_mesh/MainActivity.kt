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
        setContentView(R.layout.activity_main)
        
        deviceRepository = DeviceRepository(this)
        
        // 清除旧的温度数据，避免显示过期的信息
        deviceRepository.clearAllTemperatures()
        
        setupViews()
        loadDevices()
        startTemperaturePolling()
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
        
        // 自动连接到 Proxy（如果已有配置）
        if (viewModel.meshNetWork != null) {
            viewModel.autoConnectToProxy()
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
        startActivity(intent)
    }
    
    override fun onResume() {
        super.onResume()
        loadDevices()
    }
    
    private fun startTemperaturePolling() {
        Log.d("MainActivity", "启动温度轮询")
        
        // 每30秒读取一次温度
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val connected = viewModel.isConnected.value == true
                Log.d("MainActivity", "温度轮询检查 - 连接状态: $connected")
                
                if (connected) {
                    val devices = deviceRepository.getAllDevices()
                    Log.d("MainActivity", "准备读取 ${devices.size} 个设备的温度")
                    
                    devices.forEach { device ->
                        Log.d("MainActivity", "读取设备 ${device.name} (0x${device.address.toString(16)}) 的温度")
                        // 读取所有设备的温度
                        viewModel.readTemperature(device.address)
                        

                    }
                } else {
                    Log.w("MainActivity", "未连接到 Proxy，跳过温度读取")
                }
                
                handler.postDelayed(this, 30000) // 30秒后再次执行
            }
        }
        
        Log.d("MainActivity", "5秒后开始第一次温度读取")
        handler.postDelayed(runnable, 5000) // 5秒后开始第一次读取
    }
}