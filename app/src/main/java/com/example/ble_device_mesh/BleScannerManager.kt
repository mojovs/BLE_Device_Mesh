package com.example.ble_device_mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.UUID

class BleScannerManager(private val context: Context) {
    
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    
    private var isScanning = false
    private var scanCallback: ScanCallback? = null
    
    // Mesh Proxy Service UUID
    companion object {
        private const val MESH_PROXY_UUID = "00001828-0000-1000-8000-00805f9b34fb"
        private const val MESH_PROVISIONING_UUID = "00001827-0000-1000-8000-00805f9b34fb"
    }
    
    interface ScanListener {
        fun onDeviceFound(device: ScanResult)
        fun onScanFailed(errorCode: Int)
    }
    
    @SuppressLint("MissingPermission")
    fun startScan(listener: ScanListener) {
        Log.d("BleScannerManager", "startScan 被调用")
        
        if (isScanning) {
            Log.w("BleScannerManager", "扫描已在进行中")
            return
        }
        
        Log.d("BleScannerManager", "检查权限...")
        if (!hasPermissions()) {
            Log.e("BleScannerManager", "缺少蓝牙权限")
            listener.onScanFailed(-1) // 自定义错误码表示权限问题
            return
        }
        Log.d("BleScannerManager", "权限检查通过")
        
        if (bluetoothLeScanner == null) {
            Log.e("BleScannerManager", "蓝牙扫描器不可用")
            return
        }
        
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.d("BleScannerManager", "发现设备: ${result.device.address}")
                listener.onDeviceFound(result)
            }
            
            override fun onBatchScanResults(results: List<ScanResult>) {
                Log.d("BleScannerManager", "批量发现 ${results.size} 个设备")
                results.forEach { result ->
                    listener.onDeviceFound(result)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e("BleScannerManager", "扫描失败: $errorCode")
                isScanning = false
                listener.onScanFailed(errorCode)
            }
        }
        
        // 设置扫描过滤器，只扫描 Mesh 设备
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(MESH_PROXY_UUID)))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(MESH_PROVISIONING_UUID)))
                .build()
        )
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        try {
            bluetoothLeScanner.startScan(filters, settings, scanCallback)
            isScanning = true
            Log.d("BleScannerManager", "开始扫描 BLE Mesh 设备")
        } catch (e: SecurityException) {
            Log.e("BleScannerManager", "安全异常: ${e.message}")
            listener.onScanFailed(-2)
        }
    }
    
    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning || scanCallback == null) {
            return
        }
        
        bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        scanCallback = null
        Log.d("BleScannerManager", "停止扫描")
    }
    
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    private fun hasPermissions(): Boolean {
        val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 12 以下不需要 BLUETOOTH_SCAN
        }
        
        val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 12 以下不需要 BLUETOOTH_CONNECT
        }
        
        val hasLocation = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        Log.d("BleScannerManager", "权限检查 - SCAN: $hasScan, CONNECT: $hasConnect, LOCATION: $hasLocation")
        
        return hasScan && hasConnect && hasLocation
    }
}
