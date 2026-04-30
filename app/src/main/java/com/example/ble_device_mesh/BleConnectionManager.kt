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
    private var pendingServicesDiscoveredCallback = false  // descriptor 写入完成后回调
    private var pendingWriteData: ByteArray? = null  // 用于 onCharacteristicWrite 回调的数据缓存

    companion object {
        // Mesh Proxy Service 和 Characteristic UUIDs
        private val MESH_PROXY_SERVICE_UUID = UUID.fromString("00001828-0000-1000-8000-00805f9b34fb")
        private val MESH_PROXY_DATA_IN_UUID = UUID.fromString("00002add-0000-1000-8000-00805f9b34fb")
        private val MESH_PROXY_DATA_OUT_UUID = UUID.fromString("00002ade-0000-1000-8000-00805f9b34fb")

        // Mesh Provisioning Service 和 Characteristic UUIDs
        private val MESH_PROVISIONING_SERVICE_UUID = UUID.fromString("00001827-0000-1000-8000-00805f9b34fb")
        private val MESH_PROVISIONING_DATA_IN_UUID = UUID.fromString("00002adb-0000-1000-8000-00805f9b34fb")
        private val MESH_PROVISIONING_DATA_OUT_UUID = UUID.fromString("00002adc-0000-1000-8000-00805f9b34fb")

        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    
    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onServicesDiscovered()
        fun onDataReceived(data: ByteArray)
        fun onDataSent(data: ByteArray)  // 新增：数据发送成功回调
        fun onMeshMessageReceived(src: Int, data: ByteArray)
        fun onRssiRead(rssi: Int)
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
        // 重置特征值引用，避免使用旧连接的 BLE 特征值
        proxyDataInCharacteristic = null
        proxyDataOutCharacteristic = null
        pendingServicesDiscoveredCallback = false
        pendingWriteData = null

        try {
            // 使用 TRANSPORT_LE 确保使用低功耗蓝牙
            // autoConnect = false 表示直接连接，速度快但要求设备在广播
            // autoConnect = true 可以用于重连，但速度慢
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            Log.d("BleConnection", "connectGatt 调用成功")
        } catch (e: Exception) {
            Log.e("BleConnection", "连接异常: ${e.message}")
            listener.onError("连接异常: ${e.message}")
        }
    }
    
    @SuppressLint("MissingPermission")
    fun connect(macAddress: String, listener: ConnectionListener) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = bluetoothManager?.adapter

        if (adapter == null) {
            listener.onError("蓝牙适配器不可用")
            return
        }
        
        try {
            val device = adapter.getRemoteDevice(macAddress)
            connect(device, listener)
        } catch (e: Exception) {
            listener.onError("无效的 MAC 地址: $macAddress")
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
    fun sendData(data: ByteArray, forceReliable: Boolean = false): Boolean {
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

        val writeType = if (forceReliable) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        // 缓存数据用于 onCharacteristicWrite 回调（API < 33 无法从回调获取数据）
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
            pendingWriteData = data
        }

        val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                data,
                writeType
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        Log.d("BleConnection", "发送数据 (char=${characteristic.uuid}, ${data.size} 字节, writeType=${if (forceReliable) "DEFAULT" else "NO_RESPONSE"}): ${data.joinToString(" ") { "%02X".format(it) }}, 结果: $success")
        return success
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        /**
         * 检查事件是否来自当前活动的 GATT 连接
         */
        private fun isCurrentGatt(gatt: BluetoothGatt): Boolean {
            return bluetoothGatt != null && bluetoothGatt === gatt
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("BleConnection", "onConnectionStateChange - status: $status, newState: $newState")

            if (!isCurrentGatt(gatt)) {
                Log.d("BleConnection", "忽略旧连接的连接状态事件")
                return
            }

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
            if (!isCurrentGatt(gatt)) return
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
            if (!isCurrentGatt(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BleConnection", "服务发现成功")

                // 列出所有服务
                val services = gatt.services
                Log.d("BleConnection", "设备共有 ${services.size} 个服务:")
                for (s in services) {
                    Log.d("BleConnection", "  Service: ${s.uuid}")
                    for (c in s.characteristics) {
                        Log.d("BleConnection", "    Char: ${c.uuid} (props: ${c.properties})")
                    }
                }

                // 优先查找 Proxy Service（已配网设备控制必须走 Proxy）
                val proxyService = gatt.getService(MESH_PROXY_SERVICE_UUID)
                if (proxyService != null) {
                    Log.d("BleConnection", "找到 Mesh Proxy Service (0x1828)")

                    proxyDataInCharacteristic = proxyService.getCharacteristic(MESH_PROXY_DATA_IN_UUID)
                    proxyDataOutCharacteristic = proxyService.getCharacteristic(MESH_PROXY_DATA_OUT_UUID)

                    Log.d("BleConnection", "  Data In (0x2ADD): ${if (proxyDataInCharacteristic != null) "存在" else "null"}")
                    Log.d("BleConnection", "  Data Out (0x2ADE): ${if (proxyDataOutCharacteristic != null) "存在" else "null"}")

                    // 启用 Data Out 通知
                    proxyDataOutCharacteristic?.let { characteristic ->
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)

                        if (descriptor != null) {
                            pendingServicesDiscoveredCallback = true
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                            Log.d("BleConnection", "已启用 Proxy Data Out 通知，等待 descriptor 写入完成")
                        } else {
                            Log.w("BleConnection", "Proxy Data Out 无 CCCD，直接回调")
                            listener?.onServicesDiscovered()
                        }
                    }

                    // 有 descriptor 写入时在 onDescriptorWrite 中回调
                    if (!pendingServicesDiscoveredCallback) {
                        listener?.onServicesDiscovered()
                    }
                    return
                }

                // 没有 Proxy Service 时再尝试 Provisioning Service（未配网设备）
                val provisioningService = gatt.getService(MESH_PROVISIONING_SERVICE_UUID)
                if (provisioningService != null) {
                    Log.d("BleConnection", "找到 Mesh Provisioning Service (0x1827)")

                    proxyDataInCharacteristic = provisioningService.getCharacteristic(MESH_PROVISIONING_DATA_IN_UUID)
                    proxyDataOutCharacteristic = provisioningService.getCharacteristic(MESH_PROVISIONING_DATA_OUT_UUID)

                    Log.d("BleConnection", "  Data In (0x2ADB): ${if (proxyDataInCharacteristic != null) "存在" else "null"}")
                    Log.d("BleConnection", "  Data Out (0x2ADC): ${if (proxyDataOutCharacteristic != null) "存在" else "null"}")

                    // 启用 Data Out 通知
                    proxyDataOutCharacteristic?.let { characteristic ->
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)

                        if (descriptor != null) {
                            pendingServicesDiscoveredCallback = true
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                            Log.d("BleConnection", "已启用 Provisioning Data Out 通知，等待 descriptor 写入完成")
                        } else {
                            Log.w("BleConnection", "Provisioning Data Out 无 CCCD，直接回调")
                            listener?.onServicesDiscovered()
                        }
                    }

                    // 有 descriptor 写入时在 onDescriptorWrite 中回调
                    if (!pendingServicesDiscoveredCallback) {
                        listener?.onServicesDiscovered()
                    }
                } else {
                    Log.e("BleConnection", "未找到 Mesh Proxy Service 或 Provisioning Service")
                    listener?.onError("未找到 Mesh Proxy Service")
                }
            } else {
                Log.e("BleConnection", "服务发现失败: $status")
                listener?.onError("服务发现失败")
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // 支持 Proxy 和 Provisioning 两种服务的数据接收
            if (characteristic.uuid == MESH_PROXY_DATA_OUT_UUID || characteristic.uuid == MESH_PROVISIONING_DATA_OUT_UUID) {
                Log.d("BleConnection", "=== 收到 BLE 数据 ===")
                Log.d("BleConnection", "  数据长度: ${value.size} 字节")
                Log.d("BleConnection", "  数据内容: ${value.joinToString(" ") { "%02X".format(it) }}")
                Log.d("BleConnection", "========================")

                listener?.onDataReceived(value)

                // 尝试解析源地址（简化版本，实际需要完整的 Mesh PDU 解析）
                if (value.size >= 9) {
                    // Mesh Network PDU 格式：IVI(1bit) + NID(7bits) + CTL(1bit) + TTL(7bits) + SEQ(24bits) + SRC(16bits) + DST(16bits) + ...
                    // 简化：假设 SRC 在偏移 6-7 位置
                    val src = ((value[6].toInt() and 0xFF) shl 8) or (value[7].toInt() and 0xFF)
                    Log.d("BleConnection", "  推测源地址: 0x${src.toString(16)}")
                    listener?.onMeshMessageReceived(src, value)
                }
            }
        }
        
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // 兼容旧版本 API
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                onCharacteristicChanged(gatt, characteristic, characteristic.value)
            }
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BleConnection", "数据写入成功")
                pendingWriteData?.let { data ->
                    listener?.onDataSent(data)
                }
            } else {
                Log.e("BleConnection", "数据写入失败: $status")
                listener?.onError("数据写入失败: $status")
            }
            pendingWriteData = null
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BleConnection", "Descriptor 写入成功，通知已启用")
            } else {
                Log.e("BleConnection", "Descriptor 写入失败: $status")
            }

            // 如果有延迟的 onServicesDiscovered 回调，现在触发
            if (pendingServicesDiscoveredCallback) {
                pendingServicesDiscoveredCallback = false
                Log.d("BleConnection", "Descriptor 完成，触发延迟的 onServicesDiscovered 回调")
                listener?.onServicesDiscovered()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener?.onRssiRead(rssi)
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    fun readRssi() {
        bluetoothGatt?.readRemoteRssi()
    }

    fun isConnected(): Boolean {
        return bluetoothGatt != null && proxyDataInCharacteristic != null
    }

    /**
     * 设置新的连接监听器（替换当前的）
     * 用于配网完成后切换到配置监听器
     */
    fun setListener(newListener: ConnectionListener) {
        this.listener = newListener
    }

    /**
     * 在不断开连接的情况下重新发现服务
     * 用于配网后刷新 GATT 服务列表以发现新增的 Proxy Service
     */
    fun rediscoverServices(): Boolean {
        return bluetoothGatt?.discoverServices() ?: false
    }

    /**
     * 检查当前是否已连接到 Proxy Service (0x1828) 的 Data In 特征
     * 如果返回 false，说明当前使用的是 Provisioning Service 或未连接
     */
    fun isUsingProxyService(): Boolean {
        return proxyDataInCharacteristic?.uuid == MESH_PROXY_DATA_IN_UUID
    }

    /**
     * 获取当前使用的 Data In 特征值 UUID（用于诊断）
     */
    fun getCurrentCharacteristicUuid(): String? {
        return proxyDataInCharacteristic?.uuid?.toString()
    }
}
