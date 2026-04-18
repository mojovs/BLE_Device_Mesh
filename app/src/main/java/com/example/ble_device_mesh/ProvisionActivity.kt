package com.example.ble_device_mesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
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

    private fun setupViews() {
        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val rvDevices = findViewById<RecyclerView>(R.id.rvUnprovisionedDevices)
        rvDevices.layoutManager = LinearLayoutManager(this)

        adapter = UnprovisionedDeviceAdapter { device ->
            viewModel.provisionDevice(device)
        }
        rvDevices.adapter = adapter

        findViewById<Button>(R.id.btnRescan).setOnClickListener {
            if (hasAllPermissions()) {
                startScan()
            } else {
                checkAndRequestPermissions()
            }
        }

        findViewById<Button>(R.id.btnAddProvisioned).setOnClickListener {
            showAddProvisionedDeviceDialog()
        }
    }

    private fun startScan() {
        viewModel.startUnprovisionedScan()
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
        }

        viewModel.isProvisioning.observe(this) { isProvisioning ->
            findViewById<ProgressBar>(R.id.progressBar).visibility =
                if (isProvisioning) View.VISIBLE else View.GONE
        }

        viewModel.provisioningComplete.observe(this) { (success, address) ->
            if (success) {
                Toast.makeText(this, "配网成功！地址: 0x${address.toString(16)}", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this, "配网失败", Toast.LENGTH_SHORT).show()
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
}
