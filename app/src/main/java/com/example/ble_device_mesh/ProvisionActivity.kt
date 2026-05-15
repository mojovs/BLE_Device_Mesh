package com.example.ble_device_mesh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ProvisionActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private lateinit var adapter: UnprovisionedDeviceAdapter

    // 批量配网相关
    private var isBatchProvisioning = false
    private var batchDeviceList = mutableListOf<no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode>()
    private var currentBatchIndex = 0

    // 权限请求
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startScan()
        } else {
            Toast.makeText(this, "需要蓝牙权限才能扫描设备", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provision)

        setupViews()
        observeViewModel()

        // 检查权限后再启动扫描
        if (hasAllPermissions()) {
            startScan()
        } else {
            checkAndRequestPermissions()
        }
    }

    /** 配网中禁止返回 */
    override fun onBackPressed() {
        if (viewModel.isProvisioning.value == true) {
            Toast.makeText(this, "配网中，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }

    private fun setupViews() {
        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            if (viewModel.isProvisioning.value == true) {
                Toast.makeText(this, "配网中，请稍候...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isBatchProvisioning) {
                stopBatchProvisioning()
            }
            finish()
        }

        val rvDevices = findViewById<RecyclerView>(R.id.rvUnprovisionedDevices)
        rvDevices.layoutManager = LinearLayoutManager(this)

        adapter = UnprovisionedDeviceAdapter { device ->
            if (!isBatchProvisioning) {
                showProvisionConfigDialog(device)
            }
        }
        rvDevices.adapter = adapter

        findViewById<Button>(R.id.btnRescan).setOnClickListener {
            if (isBatchProvisioning) {
                stopBatchProvisioning()
            } else if (hasAllPermissions()) {
                startScan()
            } else {
                checkAndRequestPermissions()
            }
        }

        findViewById<Button>(R.id.btnBatchProvision).setOnClickListener {
            if (isBatchProvisioning) {
                stopBatchProvisioning()
            } else {
                startBatchProvisioning()
            }
        }

        findViewById<Button>(R.id.btnAddProvisioned).setOnClickListener {
            showAddProvisionedDeviceDialog()
        }
    }

    private fun startScan() {
        if (!checkLocationEnabled()) return
        viewModel.startUnprovisionedScan()
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
            android.app.AlertDialog.Builder(this)
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

    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    private fun observeViewModel() {
        viewModel.unprovisionedDevices.observe(this) { devices ->
            adapter.updateDevices(devices)
            findViewById<TextView>(R.id.tvDeviceCount).text = "发现 ${devices.size} 个未配网设备"
        }

        viewModel.provisioningStatus.observe(this) { status ->
            findViewById<TextView>(R.id.tvProvisionStatus).text = status
            if (viewModel.isProvisioning.value == true) {
                findViewById<TextView>(R.id.tvLoadingStatus).text = status
            }
        }

        viewModel.isProvisioning.observe(this) { isProvisioning ->
            findViewById<View>(R.id.loadingOverlay).visibility =
                if (isProvisioning) View.VISIBLE else View.GONE
            if (isProvisioning) {
                findViewById<TextView>(R.id.tvLoadingStatus).text =
                    viewModel.provisioningStatus.value ?: "正在配网中..."
            }
        }

        viewModel.provisioningComplete.observe(this) { event ->
            val (success, address) = event.getContentIfNotHandled() ?: return@observe
            if (success) {
                val message = "配网成功！地址: 0x${address.toString(16)}"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

                // 批量配网模式：自动配网下一个设备
                if (isBatchProvisioning) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        provisionNextDevice()
                    }, 2000) // 等待 2 秒后配网下一个
                } else {
                    finish()
                }
            } else {
                Toast.makeText(this, "配网失败", Toast.LENGTH_SHORT).show()

                // 批量配网模式：失败后继续下一个
                if (isBatchProvisioning) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        provisionNextDevice()
                    }, 1000)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopUnprovisionedScan()
    }

    private fun showAddProvisionedDeviceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_device, null)
        val etDeviceName = dialogView.findViewById<android.widget.EditText>(R.id.etDeviceName)
        val etDeviceAddress = dialogView.findViewById<android.widget.EditText>(R.id.etDeviceAddress)
        val spinnerDeviceType = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerDeviceType)

        // 设置设备类型选择器
        val deviceTypes = arrayOf("灯光", "开关", "传感器", "其他")
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDeviceType.adapter = adapter

        val dialog = android.app.AlertDialog.Builder(this)
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

            // 检查 Mesh 网络是否已加载，以及该地址是否存在于网络中
            val meshNetwork = viewModel.meshNetWork
            if (meshNetwork == null) {
                // 网络未加载，提示用户先导入配置
                android.app.AlertDialog.Builder(this)
                    .setTitle("网络未加载")
                    .setMessage("未检测到 Mesh 网络配置。\n\n请先在「设置」中导入其他设备分享的配置文件，\n或先配网第一个设备。")
                    .setPositiveButton("去设置") { _, _ ->
                        startActivity(android.content.Intent(this, SettingsActivity::class.java))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                dialog.dismiss()
                return@setOnClickListener
            }

            // 检查网络中是否存在该地址的节点
            val node = meshNetwork.getNode(address)
            if (node == null) {
                Toast.makeText(this, "网络中不存在地址 0x${address.toString(16)} 的节点，请检查地址是否正确", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val deviceType = when (spinnerDeviceType.selectedItemPosition) {
                0 -> com.example.ble_device_mesh.data.DeviceType.LIGHT
                1 -> com.example.ble_device_mesh.data.DeviceType.SWITCH
                2 -> com.example.ble_device_mesh.data.DeviceType.SENSOR
                else -> com.example.ble_device_mesh.data.DeviceType.OTHER
            }

            val device = com.example.ble_device_mesh.data.MeshDevice(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                address = address,
                type = deviceType
            )

            val deviceRepository = com.example.ble_device_mesh.data.DeviceRepository(this)
            deviceRepository.addDevice(device)
            dialog.dismiss()

            Toast.makeText(this, "设备添加成功", Toast.LENGTH_SHORT).show()
            finish()
        }

        dialog.show()
    }

    /**
     * 显示配网配置对话框，让用户设置名称、地址和 AppKey
     */
    private fun showProvisionConfigDialog(device: no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_provision_config, null)
        val etDeviceName = dialogView.findViewById<android.widget.EditText>(R.id.etDeviceName)
        val etDeviceAddress = dialogView.findViewById<android.widget.EditText>(R.id.etDeviceAddress)
        val spinnerAppKey = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerAppKey)

        // 默认地址：下一个可用地址
        val nextAddr = viewModel.getNextAvailableAddress()
        etDeviceAddress.setText("0x${nextAddr.toString(16).padStart(4, '0')}")

        // 设备类型选择器
        val spinnerDeviceType = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerDeviceType)
        val deviceTypeNames = arrayOf("灯光", "开关", "传感器", "其他")
        val typeAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceTypeNames)
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDeviceType.adapter = typeAdapter

        // 统计已有设备数量，用于自动命名
        val deviceRepo = com.example.ble_device_mesh.data.DeviceRepository(this)
        val existingDevices = deviceRepo.getAllDevices()
        fun getDeviceTypeFromPosition(pos: Int): com.example.ble_device_mesh.data.DeviceType {
            return when (pos) {
                0 -> com.example.ble_device_mesh.data.DeviceType.LIGHT
                1 -> com.example.ble_device_mesh.data.DeviceType.SWITCH
                2 -> com.example.ble_device_mesh.data.DeviceType.SENSOR
                else -> com.example.ble_device_mesh.data.DeviceType.OTHER
            }
        }
        fun getTypeName(type: com.example.ble_device_mesh.data.DeviceType): String {
            return when (type) {
                com.example.ble_device_mesh.data.DeviceType.LIGHT -> "灯"
                com.example.ble_device_mesh.data.DeviceType.SWITCH -> "开关"
                com.example.ble_device_mesh.data.DeviceType.SENSOR -> "传感器"
                com.example.ble_device_mesh.data.DeviceType.OTHER -> "其他"
            }
        }
        fun generateName(typePos: Int): String {
            val deviceType = getDeviceTypeFromPosition(typePos)
            val prefix = getTypeName(deviceType)
            val existingNames = existingDevices.filter { it.type == deviceType }.map { it.name }.toSet()
            var num = 1
            while ("$prefix $num" in existingNames) {
                num++
            }
            return "$prefix $num"
        }

        // 默认名称：根据设备类型数量自动生成
        etDeviceName.setText(generateName(0))

        // 类型切换时更新名称
        spinnerDeviceType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                etDeviceName.setText(generateName(position))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 设置 AppKey 选择器
        val appKeyNames = viewModel.getAppKeyNames()
        if (appKeyNames.isNotEmpty()) {
            val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, appKeyNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerAppKey.adapter = adapter
        } else {
            // 没有 AppKey，只显示一个默认选项
            val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("默认 AppKey"))
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerAppKey.adapter = adapter
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("配网配置")
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            val name = etDeviceName.text.toString().trim()
            val addressStr = etDeviceAddress.text.toString().trim()

            if (name.isEmpty()) {
                android.widget.Toast.makeText(this, "请输入设备名称", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (addressStr.isEmpty()) {
                android.widget.Toast.makeText(this, "请输入 Mesh 地址", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 解析地址
            val address = try {
                if (addressStr.startsWith("0x", ignoreCase = true)) {
                    addressStr.substring(2).toInt(16)
                } else {
                    addressStr.toInt(16)
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "地址格式错误（例如：0x0005）", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 验证地址范围
            if (address < 0x0001 || address > 0x7FFF) {
                android.widget.Toast.makeText(this, "地址必须在 0x0001~0x7FFF 范围内", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val deviceType = getDeviceTypeFromPosition(spinnerDeviceType.selectedItemPosition)
            val mac = viewModel.getMacForUnprovisionedNode(device.deviceUuid)
            val config = MeshViewModel.ProvisionConfig(
                deviceName = name,
                unicastAddress = address,
                appKeyIndex = spinnerAppKey.selectedItemPosition,
                deviceType = deviceType,
                bluetoothMac = mac
            )

            Log.d("ProvisionActivity", "配网配置: name=$name, address=0x${address.toString(16)}, appKey=${spinnerAppKey.selectedItemPosition}, type=$deviceType")
            dialog.dismiss()
            viewModel.provisionDevice(device, config)
        }

        dialog.show()
    }

    // 批量配网相关函数
    private fun startBatchProvisioning() {
        val devices = viewModel.unprovisionedDevices.value
        if (devices.isNullOrEmpty()) {
            Toast.makeText(this, "没有发现未配网设备", Toast.LENGTH_SHORT).show()
            return
        }

        isBatchProvisioning = true
        batchDeviceList.clear()
        batchDeviceList.addAll(devices)
        currentBatchIndex = 0

        // 更新按钮状态
        findViewById<Button>(R.id.btnBatchProvision).apply {
            text = "停止批量配网"
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF44336.toInt()) // 红色
        }
        findViewById<Button>(R.id.btnRescan).isEnabled = false

        Toast.makeText(this, "开始批量配网，共 ${batchDeviceList.size} 个设备", Toast.LENGTH_SHORT).show()
        provisionNextDevice()
    }

    private fun stopBatchProvisioning() {
        isBatchProvisioning = false
        batchDeviceList.clear()
        currentBatchIndex = 0

        // 恢复按钮状态
        findViewById<Button>(R.id.btnBatchProvision).apply {
            text = "批量配网"
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt()) // 橙色
        }
        findViewById<Button>(R.id.btnRescan).isEnabled = true

        Toast.makeText(this, "已停止批量配网", Toast.LENGTH_SHORT).show()
    }

    private fun provisionNextDevice() {
        if (!isBatchProvisioning || currentBatchIndex >= batchDeviceList.size) {
            // 批量配网完成
            if (isBatchProvisioning) {
                Toast.makeText(this, "批量配网完成！", Toast.LENGTH_LONG).show()
                stopBatchProvisioning()
            }
            return
        }

        val device = batchDeviceList[currentBatchIndex]
        val progress = "${currentBatchIndex + 1}/${batchDeviceList.size}"
        findViewById<TextView>(R.id.tvProvisionStatus).text = "正在配网第 $progress 个设备..."

        Log.d("ProvisionActivity", "批量配网进度: $progress")
        viewModel.provisionDevice(device)
        currentBatchIndex++
    }
}
