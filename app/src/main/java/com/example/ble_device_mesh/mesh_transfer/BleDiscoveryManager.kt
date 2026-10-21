package com.example.ble_device_mesh.mesh_transfer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * BLE 发现 + 配对信息交换。
 * - 接收端：BLE 广播 + GATT Server，等待发送端连接，接收配对码/群组信息
 * - 发送端：BLE 扫描发现接收端，GATT Client 连接，发送配对码/群组信息
 */
class BleDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "BleDiscoveryManager"

        val SERVICE_UUID: UUID = UUID.fromString("a3c87500-8bed-4930-87ea-526d0a1c0d11")
        val CHAR_UUID: UUID = UUID.fromString("a3c87501-8bed-4930-87ea-526d0a1c0d11")

        fun hasBlePermissions(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    interface Listener {
        fun onDeviceFound(device: ScanResult)
        fun onScanFailed(message: String)
        fun onMessage(message: String)
        fun onAdvertiseFailed(message: String)
        fun onServerFailed(message: String)
        fun onConnected()
        fun onDisconnected()
        fun onLog(message: String)
        fun onError(message: String)
    }

    private val bluetoothManager =
        context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var listener: Listener? = null
    private var isReceiver = false

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private var gattServer: BluetoothGattServer? = null
    private var serverDevice: BluetoothDevice? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null

    private var clientGatt: BluetoothGatt? = null
    private var clientCharacteristic: BluetoothGattCharacteristic? = null

    // ============ 接收端：广播 + GATT Server ============

    @SuppressLint("MissingPermission")
    fun startReceiver(listener: Listener) {
        this.listener = listener
        this.isReceiver = true
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onServerFailed("请先开启蓝牙")
            return
        }
        startGattServer(listener)
        startAdvertising(listener)
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer(listener: Listener) {
        try {
            val characteristic = BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            serverCharacteristic = characteristic
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(characteristic)
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            if (gattServer?.addService(service) == false) {
                listener.onServerFailed("GATT 服务添加失败")
            }
        } catch (e: SecurityException) {
            listener.onServerFailed("蓝牙权限不足")
        } catch (e: Exception) {
            listener.onServerFailed("GATT Server 启动失败: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(listener: Listener) {
        try {
            val adapter = bluetoothAdapter ?: return listener.onAdvertiseFailed("蓝牙不可用")
            val leAdvertiser = adapter.bluetoothLeAdvertiser
            if (leAdvertiser == null) {
                listener.onAdvertiseFailed("设备不支持 BLE 广播")
                return
            }
            advertiser = leAdvertiser
            advertisingRetryCount = 0
            advertiseCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    Log.d(TAG, "BLE 广播已开启")
                }

                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "BLE 广播失败 code=$errorCode")
                    if (advertisingRetryCount < 2) {
                        retryAdvertisingWithConservativeParams(listener)
                    } else {
                        listener.onAdvertiseFailed("BLE 广播失败 (错误码 $errorCode)")
                    }
                }
            }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            leAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            listener.onAdvertiseFailed("蓝牙权限不足")
        } catch (e: Exception) {
            listener.onAdvertiseFailed("BLE 广播启动异常: ${e.message}")
        }
    }

    private var advertisingRetryCount = 0

    @SuppressLint("MissingPermission")
    private fun retryAdvertisingWithConservativeParams(listener: Listener) {
        advertisingRetryCount++
        try {
            val leAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            leAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            listener.onAdvertiseFailed("BLE 广播启动异常: ${e.message}")
        }
    }

    // ============ 发送端：扫描 + GATT Client ============

    @SuppressLint("MissingPermission")
    fun startScanning(listener: Listener) {
        this.listener = listener
        this.isReceiver = false
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onScanFailed("请先开启蓝牙")
            return
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            listener.onScanFailed("BLE 扫描不可用")
            return
        }
        scanner = leScanner
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                listener.onDeviceFound(result)
            }

            override fun onScanFailed(errorCode: Int) {
                listener.onScanFailed("扫描失败 (错误码 $errorCode)")
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            leScanner.startScan(listOf(filter), settings, scanCallback)
            listener.onLog("正在搜索附近设备...")
        } catch (e: SecurityException) {
            listener.onScanFailed("蓝牙权限不足")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            scanCallback?.let { scanner?.stopScan(it) }
        } catch (_: Exception) {
        }
        scanCallback = null
        scanner = null
    }

    @SuppressLint("MissingPermission")
    fun connectClient(device: BluetoothDevice, listener: Listener) {
        this.listener = listener
        try {
            clientGatt = device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            listener.onError("蓝牙权限不足")
        } catch (e: Exception) {
            listener.onError("连接失败: ${e.message}")
        }
    }

    // ============ 消息发送 ============

    /** MIUI 兼容：写入前延迟 300ms，避免 writeCharacteristic 不回调不发数据 */
    @SuppressLint("MissingPermission")
    fun sendMessage(message: String) {
        val data = message.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        handler.postDelayed({
            try {
                if (isReceiver) {
                    val device = serverDevice
                    val server = gattServer
                    val characteristic = serverCharacteristic
                    if (device != null && server != null && characteristic != null) {
                        server.notifyCharacteristicChanged(device, characteristic, true, data)
                    }
                } else {
                    val gatt = clientGatt
                    val char = clientCharacteristic
                    if (gatt != null && char != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        } else {
                            @Suppress("DEPRECATION")
                            char.value = data
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(char)
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "sendMessage 权限异常: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage 异常: ${e.message}")
            }
        }, 300)
    }

    // ============ GATT 回调 ============

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverDevice = device
                listener?.onLog("已连接发送端")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverDevice = null
                listener?.onDisconnected()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            gattServer?.sendResponse(device, requestId, 0, 0, null)
            if (value != null) {
                val msg = String(value, java.nio.charset.StandardCharsets.UTF_8)
                Log.d(TAG, "Server 收到: $msg")
                listener?.onMessage(msg)
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener?.onDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onError("服务发现失败")
                return
            }
            val service = gatt.getService(SERVICE_UUID) ?: return
            val char = service.getCharacteristic(CHAR_UUID) ?: return
            clientCharacteristic = char
            try {
                gatt.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (descriptor != null) {
                    descriptor.value = byteArrayOf(0x01, 0x00)
                    gatt.writeDescriptor(descriptor)
                }
                listener?.onConnected()
            } catch (e: SecurityException) {
                listener?.onError("蓝牙权限不足")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            onClientData(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onClientData(value)
        }

        private fun onClientData(value: ByteArray?) {
            if (value != null) {
                val msg = String(value, java.nio.charset.StandardCharsets.UTF_8)
                Log.d(TAG, "Client 收到: $msg")
                listener?.onMessage(msg)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun release() {
        try {
            stopScan()
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
            advertiser = null
            advertiseCallback = null
            gattServer?.close()
            gattServer = null
            serverDevice = null
            clientGatt?.disconnect()
            clientGatt?.close()
            clientGatt = null
            clientCharacteristic = null
        } catch (_: Exception) {
        }
    }
}
