package com.example.ble_device_mesh

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    private val viewModel: MeshViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter
    
    // 权限请求
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要蓝牙权限才能扫描设备", Toast.LENGTH_LONG).show()
        }
    }
    
    // 蓝牙开启请求
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "蓝牙已开启", Toast.LENGTH_SHORT).show()
        }
    }
    
    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            importMeshConfig(it)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 请求权限
        checkAndRequestPermissions()
        
        // 初始化 RecyclerView
        val rvDevices = findViewById<RecyclerView>(R.id.rvDevices)
        deviceAdapter = DeviceAdapter(emptyList()) { device ->
            // 点击设备时连接
            viewModel.stopBleScan()
            viewModel.connectToDevice(device)
        }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter
        
        // 状态文本
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        viewModel.statusText.observe(this) { status ->
            tvStatus.text = "状态: $status"
        }
        
        // 扫描按钮
        val btnStartScan = findViewById<Button>(R.id.btnStartScan)
        val btnStopScan = findViewById<Button>(R.id.btnStopScan)
        val btnImportConfig = findViewById<Button>(R.id.btnImportConfig)
        
        btnImportConfig.setOnClickListener {
            filePickerLauncher.launch("application/json")
        }
        
        btnStartScan.setOnClickListener {
            if (!hasAllPermissions()) {
                Toast.makeText(this, "请先授予所有必要权限", Toast.LENGTH_LONG).show()
                checkAndRequestPermissions()
                return@setOnClickListener
            }
            
            if (checkBluetoothEnabled()) {
                viewModel.startBleScan()
            }
        }
        
        btnStopScan.setOnClickListener {
            viewModel.stopBleScan()
        }
        
        // 观察扫描状态
        viewModel.isScanning.observe(this) { scanning ->
            btnStartScan.isEnabled = !scanning
            btnStopScan.isEnabled = scanning
        }
        
        // 观察扫描到的设备
        viewModel.scannedDevices.observe(this) { devices ->
            deviceAdapter.updateDevices(devices)
        }
        
        // 观察连接状态
        viewModel.isConnected.observe(this) { connected ->
            if (connected) {
                Toast.makeText(this, "设备已连接，可以控制了", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 亮度滑动条
        val brightnessSeekBar = findViewById<SeekBar>(R.id.brightnessSeekBar)
        val tvBrightnessValue = findViewById<TextView>(R.id.tvBrightnessValue)
        
        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBrightnessValue.text = "$progress%"
                if (fromUser) {
                    viewModel.sendBrightness(0x0005, progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
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
            Toast.makeText(this, "需要授予蓝牙和位置权限", Toast.LENGTH_LONG).show()
            requestPermissions.launch(needRequest.toTypedArray())
        }
    }
    
    private fun hasAllPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
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
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show()
            return false
        }
        
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
            return false
        }
        
        return true
    }
    
    private fun importMeshConfig(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonData = reader.readText()
            reader.close()
            
            viewModel.importMeshNetwork(jsonData)
            Toast.makeText(this, "正在导入配置...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}