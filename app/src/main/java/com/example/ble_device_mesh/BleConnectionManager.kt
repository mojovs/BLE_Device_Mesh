package com.example.ble_device_mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID

class BleConnectionManager(private val context: Context) {
    
    private var bluetoothGatt: BluetoothGatt? = null
    var mtuSize = 23
        private set
    private var proxyDataInCharacteristic: BluetoothGattCharacteristic? = null
    private var proxyDataOutCharacteristic: BluetoothGattCharacteristic? = null
    
    companion object {
        // Mesh Proxy Service 和 Characteristic UUIDs
        private val MESH_PROXY_SERVICE_UUID = UUID.fromString("00001828-0000-1000-8000-00805f9b34fb")
        private val MESH_PROXY_DATA_IN_UUID = UUID.fromString("00002add-0000-1000-8000-00805f9b34fb")
        private val MESH_PROXY_DATA_OUT_UUID = UUID.fromString("00002ade-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    
    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onServicesDiscovered()
        fun onDataReceived(data: ByteArray)
        fun onError(error: String)
    }
    
    private var listener: ConnectionListener? = null
    
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, listener: ConnectionListener) {
        this.listener = listener
        Log.d("BleConnection", "开始连接设备: ${device.address}")
        
        // 先断开之前的连接（如果有）
        if (bluetoothGatt != null) {
            Log.d("BleConnection", "断开之前的连接")
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        
        try {
            // 使用 TRANSPORT_LE 确保使用低功耗蓝牙
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            Log.d("BleConnection", "connectGatt 调用成功")
        } catch (e: Exception) {
            Log.e("BleConnection", "连接异常: ${e.message}")
            listener.onError("连接异常: ${e.message}")
        }
    }
    
    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d("BleConnection", "断开连接")
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        proxyDataInCharacteristic = null
        proxyDataOutCharacteristic = null
    }
    
    @SuppressLint("MissingPermission")
    fun sendData(data: ByteArray): Boolean {
        val characteristic = proxyDataInCharacteristic
        if (characteristic == null) {
            Log.e("BleConnection", "Proxy Data In 特征值未找到")
            return false
        }
        
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.e("BleConnection", "GATT 未连接")
            return false
        }
        
        characteristic.value = data
        // 显式设置为无应答写入，提高 Mesh 传输效率
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val success = gatt.writeCharacteristic(characteristic)
        Log.d("BleConnection", "发送数据 (${data.size} 字节, MTU=$mtuSize): ${data.joinToString(" ") { "%02X".format(it) }}, 结果: $success")
        return success
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("BleConnection", "onConnectionStateChange - status: $status, newState: $newState")
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d("BleConnection", "已连接到 GATT 服务器")
                        listener?.onConnected()
                        
                        // 连接成功后请求更大的 MTU
                        Log.d("BleConnection", "请求 MTU 517...")
                        if (!gatt.requestMtu(517)) {
                            Log.e("BleConnection", "MTU 请求发起失败，直接发现服务")
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                gatt.discoverServices()
                            }, 600)
                        }
                    } else {
                        Log.e("BleConnection", "连接失败，status: $status")
                        listener?.onError("连接失败 (status: $status)")
                        gatt.close()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BleConnection", "已断开 GATT 连接")
                    listener?.onDisconnected()
                    gatt.close()
                }
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BleConnection", "onMtuChanged: $mtu, status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuSize = mtu
            }
            // MTU 协商完成后（或失败后）继续发现服务
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                gatt.discoverServices()
            }, 300)
        }
        
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BleConnection", "服务发现成功")
                
                val proxyService = gatt.getService(MESH_PROXY_SERVICE_UUID)
                if (proxyService != null) {
                    Log.d("BleConnection", "找到 Mesh Proxy Service")
                    
                    proxyDataInCharacteristic = proxyService.getCharacteristic(MESH_PROXY_DATA_IN_UUID)
                    proxyDataOutCharacteristic = proxyService.getCharacteristic(MESH_PROXY_DATA_OUT_UUID)
                    
                    // 启用 Data Out 通知
                    proxyDataOutCharacteristic?.let { characteristic ->
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        Log.d("BleConnection", "已启用 Data Out 通知")
                    }
                    
                    listener?.onServicesDiscovered()
                } else {
                    Log.e("BleConnection", "未找到 Mesh Proxy Service")
                    listener?.onError("未找到 Mesh Proxy Service")
                }
            } else {
                Log.e("BleConnection", "服务发现失败: $status")
                listener?.onError("服务发现失败")
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == MESH_PROXY_DATA_OUT_UUID) {
                val data = characteristic.value
                Log.d("BleConnection", "收到数据 (${data.size} 字节)")
                listener?.onDataReceived(data)
            }
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BleConnection", "数据写入成功")
            } else {
                Log.e("BleConnection", "数据写入失败: $status")
            }
        }
    }
    
    fun isConnected(): Boolean {
        return bluetoothGatt != null && proxyDataInCharacteristic != null
    }
}
