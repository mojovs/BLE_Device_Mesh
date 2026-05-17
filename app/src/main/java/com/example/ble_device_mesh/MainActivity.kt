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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
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
                // 启动时间同步服务
                TimeSyncService.start(this)
                // 连接后立即读取所有设备的温度
                readAllTemperatures()
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
        Log.d("MainActivity", "自动连接已有设备...")
        viewModel.autoConnectFromHistory(onAllFailed = {
            Log.d("MainActivity", "历史设备均无法连接，开始扫描...")
            viewModel.autoConnectToProxy()
        })
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
    }
}