package com.example.ble_device_mesh

import android.app.Application
import android.bluetooth.le.ScanResult
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import no.nordicsemi.android.mesh.MeshManagerApi
import no.nordicsemi.android.mesh.MeshManagerCallbacks
import no.nordicsemi.android.mesh.MeshNetwork
import no.nordicsemi.android.mesh.MeshStatusCallbacks
import no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode
import no.nordicsemi.android.mesh.transport.ControlMessage
import no.nordicsemi.android.mesh.transport.GenericLevelSetUnacknowledged
import no.nordicsemi.android.mesh.transport.MeshMessage
import no.nordicsemi.android.mesh.transport.SensorGet
import no.nordicsemi.android.mesh.transport.SensorStatus

class MeshViewModel(application: Application): AndroidViewModel(application) {

    // 全局单例状态持有者
    // 使用 Object 来保存 Application 级别的状态，确保在不同 Activity 之间共享
    private object MeshState {
        var isInitialized = false
        lateinit var meshManagerApi: MeshManagerApi
        lateinit var bleConnection: BleConnectionManager
        lateinit var bleScanner: BleScannerManager
        
        // LiveData 状态
        val statusText = MutableLiveData<String>()
        val isConnected = MutableLiveData<Boolean>(false)
        val isNetworkLoaded = MutableLiveData<Boolean>(false)
        val temperatureUpdates = MutableLiveData<Pair<Int, Float>>()
        val scannedDevices = MutableLiveData<List<ScanResult>>(emptyList())
        val isScanning = MutableLiveData<Boolean>(false)
        val connectedDeviceAddress = MutableLiveData<String?>(null)
        val currentProvisionerAddress = MutableLiveData<Int>()
        
        var meshNetWork: MeshNetwork? = null
        var currentTid = 0
        var connectionRetryCount = 0
        val maxRetries = 3
        var currentDevice: ScanResult? = null
    }

    // 暴露给 View 的属性 (代理到 MeshState)
    val statusText get() = MeshState.statusText
    val isConnected get() = MeshState.isConnected
    val isNetworkLoaded get() = MeshState.isNetworkLoaded
    val temperatureUpdates get() = MeshState.temperatureUpdates
    val scannedDevices get() = MeshState.scannedDevices
    val isScanning get() = MeshState.isScanning
    val connectedDeviceAddress get() = MeshState.connectedDeviceAddress
    val currentProvisionerAddress get() = MeshState.currentProvisionerAddress
    
    var meshNetWork: MeshNetwork?
        get() = MeshState.meshNetWork
        private set(value) { MeshState.meshNetWork = value }

    init {
        initializeGlobalState(application)
    }

    private fun initializeGlobalState(app: Application) {
        if (MeshState.isInitialized) return
        
        Log.d("MeshViewModel", "初始化全局 MeshState")
        MeshState.meshManagerApi = MeshManagerApi(app)
        MeshState.bleConnection = BleConnectionManager(app)
        MeshState.bleScanner = BleScannerManager(app)
        MeshState.statusText.postValue("正在初始化MESH...")
        
        setupCallbacks()
        
        Log.d("MeshApp", "开始调用 loadMeshNetwork()")
        MeshState.meshManagerApi.loadMeshNetwork()
        Log.d("MeshApp", "loadMeshNetwork() 调用完成")
        
        MeshState.isInitialized = true
    }
    
    private fun setupCallbacks() {
        val meshManagerApi = MeshState.meshManagerApi
        val statusText = MeshState.statusText
        val isNetworkLoaded = MeshState.isNetworkLoaded
        val bleConnection = MeshState.bleConnection
        val temperatureUpdates = MeshState.temperatureUpdates
        
        // 设置 MeshStatusCallbacks
        meshManagerApi.setMeshStatusCallbacks(object : MeshStatusCallbacks {
            override fun onTransactionFailed(dst: Int, hasIncompleteTimerExpired: Boolean) {
                Log.e("MeshApp", "事务失败: dst=$dst")
            }

            override fun onUnknownPduReceived(src: Int, accessPayload: ByteArray?) {
                Log.d("MeshApp", "收到未知 PDU: src=$src")
            }

            override fun onBlockAcknowledgementProcessed(dst: Int, source: ControlMessage) {}
            override fun onBlockAcknowledgementReceived(src: Int, wrapper: ControlMessage) {}
            override fun onMeshMessageProcessed(dst: Int, meshMessage: MeshMessage) {}

            override fun onMessageDecryptionFailed(meshLayer: String?, errorMessage: String?) {
                Log.e("MeshApp", "消息解密失败: layer=$meshLayer, error=$errorMessage")
            }

            override fun onMeshMessageReceived(src: Int, meshMessage: MeshMessage) {
                if (meshMessage is SensorStatus) {
                    val data = meshMessage.parameters
                    if (data != null && data.isNotEmpty()) {
                        Log.d("MeshApp", "收到 SensorStatus (Src: $src): ${data.joinToString("") { "%02X".format(it) }}")
                        parseSensorStatus(src, data)
                    }
                }
            }
        })



        meshManagerApi.setMeshManagerCallbacks(object : MeshManagerCallbacks{
            override fun onNetworkLoaded(network: MeshNetwork?) {
                Log.d("MeshApp", "onNetworkLoaded 被调用, network = $network")
                MeshState.meshNetWork = network
                
                if (network == null) {
                    statusText.postValue("没有发现网络，请导入 nRF Mesh 配置")
                } else {
                    // === 自动修复逻辑：检查当前 Provisioner 地址是否有效 ===
                    // 之前的随机地址尝试可能导致地址指向不存在的 Node，引发 Crash
                    try {
                        val provisioner = network.selectedProvisioner
                        val currentAddr = provisioner.provisionerAddress ?: 0
                        
                        // 尝试查找当前地址对应的 Node
                        var nodeExists = false
                        try {
                            // 使用反射调用 getProvisionedNode(int address)
                            val method = network.javaClass.getMethod("getProvisionedNode", Int::class.javaPrimitiveType)
                            val node = method.invoke(network, currentAddr)
                            nodeExists = (node != null)
                        } catch (e: Exception) {
                            Log.w("MeshApp", "无法验证节点存在性: $e")
                            // 如果方法不存在，可能是旧版本库，暂时假设存在以避免误判
                            nodeExists = true 
                        }
                        
                        if (!nodeExists) {
                            Log.e("MeshApp", "当前地址 0x${currentAddr.toString(16)} 无效！正在尝试寻找可用地址...")
                            statusText.postValue("检测到配置异常，正在修复...")
                            
                            // 暴力尝试修复：寻找 0x0001, 0x0002 等常见地址
                            val candidates = listOf(0x0001, 0x0002, 0x0056, 0x0099, 0x1234)
                            var validAddr: Int? = null
                            
                            val getMethod = network.javaClass.getMethod("getProvisionedNode", Int::class.javaPrimitiveType)
                            
                            for (addr in candidates) {
                                val n = getMethod.invoke(network, addr)
                                if (n != null) {
                                    validAddr = addr
                                    break
                                }
                            }
                            
                            if (validAddr != null) {
                                Log.w("MeshApp", "修复至地址: 0x${validAddr.toString(16)}")
                                
                                // 设置回有效地址
                                val setAddrMethod = try {
                                    provisioner.javaClass.getDeclaredMethod("setProvisionerAddress", Integer::class.java)
                                } catch (e: Exception) {
                                    provisioner.javaClass.getDeclaredMethod("setProvisionerAddress", Int::class.javaPrimitiveType)
                                }
                                setAddrMethod.isAccessible = true
                                setAddrMethod.invoke(provisioner, validAddr)
                                statusText.postValue("已自动修复配置 (ID: 0x${validAddr.toString(16)})")
                                
                                // === 修复后重建网络映射 ===
                                Log.w("MeshApp", "修复后重建网络映射...")
                                val json = MeshState.meshManagerApi.exportMeshNetwork()
                                if (json != null) {
                                    MeshState.meshManagerApi.importMeshNetworkJson(json)
                                    return // 退出当前流程，由 onNetworkImported 完成加载
                                }
                            } else {
                                Log.e("MeshApp", "无法找到任何有效节点！")
                                statusText.postValue("配置严重损坏：请重新导入")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MeshApp", "修复检查出错: $e")
                    }

                    MeshState.meshNetWork = network
                    MeshState.currentProvisionerAddress.postValue(network.selectedProvisioner.provisionerAddress)
                    isNetworkLoaded.postValue(true)
                    statusText.postValue("Mesh 网络已就绪: ${network.meshName}")
                    Log.d("MeshApp", "网络加载成功: ${network.meshName}")
                }
            }

            override fun onNetworkUpdated(meshNetwork: MeshNetwork?) {
                MeshState.meshNetWork = meshNetwork
                Log.d("MeshApp", "网络已更新")
            }
// ...

            override fun onNetworkLoadFailed(error: String?) {
                statusText.postValue("加载失败: $error")
            }

            override fun onNetworkImported(meshNetwork: MeshNetwork?) {
                Log.d("MeshApp", "onNetworkImported 被调用")
                MeshState.meshNetWork = meshNetwork
                
                val selectedAddr = meshNetwork?.selectedProvisioner?.provisionerAddress ?: 0
                MeshState.currentProvisionerAddress.postValue(selectedAddr)
                
                isNetworkLoaded.postValue(true)
                statusText.postValue("Mesh 网络已加载 (ID: 0x${selectedAddr.toString(16)})")
            }

            override fun onNetworkImportFailed(error: String?) {
                Log.e("MeshApp", "网络导入失败: $error")
                statusText.postValue("网络导入失败: $error")
            }

            override fun sendProvisioningPdu(meshNode: UnprovisionedMeshNode?, pdu: ByteArray?) {
            }

            override fun onMeshPduCreated(pdu: ByteArray?) {
                Log.d("MeshApp", "Mesh PDU 已创建，长度: ${pdu?.size}")
                
                if (pdu == null) return
                
                if (!bleConnection.isConnected()) {
                    Log.w("MeshApp", "未连接到设备，无法发送 PDU")
                    statusText.postValue("未连接到设备！请先扫描并连接 Proxy 节点")
                    return
                }
                
                val success = bleConnection.sendData(pdu)
                if (success) {
                    Log.d("MeshApp", "PDU 已发送到设备")
                } else {
                    Log.e("MeshApp", "PDU 发送失败")
                    statusText.postValue("PDU 发送失败")
                }
            }

            override fun getMtu(): Int {
                return bleConnection.mtuSize
            }
        })
    }
    
    // 解析传感器数据
    private fun parseSensorStatus(src: Int, data: ByteArray) {
        var offset = 0
        Log.d("MeshApp", "Parsing Sensor Status Data: ${data.joinToString(" ") { "%02X".format(it) }}")
        
        while (offset < data.size) {
            try {
                val byte0 = data[offset].toInt()
                
                // 检测 CH592 固件自定义格式
                // 固件逻辑: byte0 = (1 << 1) | ((propId >> 10) & 1)
                // 假如 PropID = 0x004F (温度), 1000001001111
                // propId >> 10 = 0
                // byte0 = 2 | 0 = 2
                if ((byte0 and 0xFE) == 0x02 && offset + 3 < data.size) {
                    val byte1 = data[offset + 1].toInt()
                    val byte2 = data[offset + 2].toInt()
                    
                    val propIdMsb = (byte0 and 0x01)
                    val propIdMid = (byte1 and 0xFF)
                    val propIdLsb = (byte2 shr 6) and 0x03
                    
                    val customPropId = (propIdMsb shl 10) or (propIdMid shl 2) or propIdLsb
                    
                    Log.d("MeshApp", "Custom PropID Check: 0x${customPropId.toString(16)}")
                    
                    if (customPropId == 0x004F) {
                        val byte3 = data[offset + 3].toInt()
                        val valHigh = (byte2 and 0x3F)
                        val valLow = (byte3 shr 6) and 0x03
                        val rawValue = (valHigh shl 2) or valLow
                        val tempVal = rawValue.toByte() * 0.5f
                        
                        Log.d("MeshApp", "解析到温度 (自定义): $tempVal (Src: 0x${src.toString(16)})")
                        MeshState.temperatureUpdates.postValue(Pair(src, tempVal))
                        
                        offset += 4
                        continue
                    }
                }

                // 标准格式解析
                val format = (byte0 shr 7) and 1
                var length = 0
                var propertyId = 0
                var valueOffset = 0
                
                if (format == 0) {
                    // Format A
                    if (offset + 1 >= data.size) break
                    val lenCode = (byte0 shr 3) and 0xF
                    length = if (lenCode == 0xF) 1 else lenCode + 1 
                    val propIdMsb = byte0 and 0x7
                    val propIdLsb = data[offset + 1].toInt() and 0xFF
                    propertyId = (propIdMsb shl 8) or propIdLsb
                    valueOffset = offset + 2
                    offset += 2 + length
                } else {
                    // Format B
                    if (offset + 2 >= data.size) break
                    val lenCode = byte0 and 0x7F
                    length = if (lenCode == 0x7F) 1 else lenCode + 1
                    propertyId = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    valueOffset = offset + 3
                    offset += 3 + length
                }
                
                Log.d("MeshApp", "Standard PropID: 0x${propertyId.toString(16)}, Length: $length")
                
                // 温度属性 0x004F
                if (propertyId == 0x004F) {
                    if (valueOffset + length <= data.size) {
                       var tempVal = 0.0f
                       if (length == 1) {
                           val raw = data[valueOffset].toByte()
                           tempVal = raw * 0.5f 
                       } else if (length == 2) {
                           val raw = ((data[valueOffset + 1].toInt() and 0xFF) shl 8) or (data[valueOffset].toInt() and 0xFF)
                           tempVal = raw.toShort() * 0.01f
                       }
                       Log.d("MeshApp", "解析到温度: $tempVal (Src: 0x${src.toString(16)})")
                       MeshState.temperatureUpdates.postValue(Pair(src, tempVal))
                    }
                }
            } catch (e: Exception) {
                Log.e("MeshApp", "Parse error: $e")
                break
            }
        }
    }

    fun sendBrightness(address: Int, brightness: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }
        
        val level = ((brightness - 50) * 655.35).toInt()
        
        // 尝试通过反射获取源地址用于日志（可选）
        var srcAddress = 0
        try {
             val method = network.selectedProvisioner.javaClass.getDeclaredMethod("getProvisionerAddress")
             method.isAccessible = true
             srcAddress = method.invoke(network.selectedProvisioner) as Int
        } catch (e: Exception) {}

        val info = "Src:0x${srcAddress.toString(16)} Dst:0x${address.toString(16)} TID:${MeshState.currentTid}"
        Log.d("MeshApp", "发送亮度控制: $info, brightness=$brightness%, level=$level")
        
        val message = GenericLevelSetUnacknowledged(appKey, level, MeshState.currentTid)
        MeshState.currentTid++
        
        try {
            MeshState.meshManagerApi.createMeshPdu(address, message)
            MeshState.statusText.postValue("发送: $info")
        } catch (e: Exception) {
             Log.e("MeshApp", "创建亮度 PDU 失败: ${e.message}")
             MeshState.statusText.postValue("发送失败: ${e.message}")
        }
    }
    
    fun readTemperature(address: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        
        val appKey = network.appKeys.firstOrNull() ?: run {
             Log.e("MeshApp", "未找到 App Key")
             return
        }
        
        Log.d("MeshApp", "读取温度: address=0x${address.toString(16)}")
        val message = SensorGet(appKey, null)
        
        try {
            MeshState.meshManagerApi.createMeshPdu(address, message)
        } catch (e: Exception) {
             Log.e("MeshApp", "创建温度 PDU 失败: ${e.message}")
             MeshState.statusText.postValue("读取温度失败: ${e.message}")
        }
    }
    
    fun startBleScan() {
        if (!MeshState.bleScanner.isBluetoothEnabled()) {
            MeshState.statusText.postValue("蓝牙未开启")
            return
        }
        MeshState.isScanning.postValue(true)
        MeshState.statusText.postValue("正在扫描 BLE Mesh 设备...")
        
        MeshState.bleScanner.startScan(object : BleScannerManager.ScanListener {
            override fun onDeviceFound(device: ScanResult) {
                val currentList = MeshState.scannedDevices.value ?: emptyList()
                if (currentList.none { it.device.address == device.device.address }) {
                    MeshState.scannedDevices.postValue(currentList + device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                MeshState.isScanning.postValue(false)
                MeshState.statusText.postValue("扫描失败: $errorCode")
            }
        })
    }
    
    fun stopBleScan() {
        MeshState.bleScanner.stopScan()
        MeshState.isScanning.postValue(false)
        MeshState.statusText.postValue("扫描已停止")
    }
    
    fun autoConnectToProxy() {
        MeshState.statusText.postValue("正在搜索 Proxy 节点...")
        var foundProxy: ScanResult? = null
        
        MeshState.bleScanner.startScan(object : BleScannerManager.ScanListener {
            override fun onDeviceFound(device: ScanResult) {
                if (foundProxy == null) {
                    foundProxy = device
                    MeshState.bleScanner.stopScan()
                    connectToDevice(device)
                }
            }
            override fun onScanFailed(errorCode: Int) {}
        })
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (foundProxy == null) {
                MeshState.bleScanner.stopScan()
                MeshState.statusText.postValue("未找到 Proxy 节点")
            }
        }, 10000)
    }
    
    fun connectToDevice(device: ScanResult) {
        MeshState.currentDevice = device
        saveProxyAddress(device.device.address)
        MeshState.connectionRetryCount = 0
        attemptConnection()
    }
    
    fun connectToSavedProxy() {
        val savedAddress = getSavedProxyAddress()
        if (savedAddress == null) return
        
        MeshState.statusText.postValue("正在连接到 $savedAddress...")
        MeshState.bleConnection.connect(savedAddress, createConnectionListener(savedAddress))
    }
    
    private fun attemptConnection() {
        val device = MeshState.currentDevice ?: return
        MeshState.statusText.postValue("正在连接到 ${device.device.address}... (${MeshState.connectionRetryCount + 1}/${MeshState.maxRetries})")
        MeshState.bleConnection.connect(device.device, createConnectionListener(device.device.address))
    }
    
    private fun createConnectionListener(address: String) = object : BleConnectionManager.ConnectionListener {
        override fun onConnected() {
            MeshState.statusText.postValue("设备已连接，正在发现服务...")
        }

        override fun onDisconnected() {
            MeshState.isConnected.postValue(false)
            MeshState.connectedDeviceAddress.postValue(null)
            MeshState.statusText.postValue("设备已断开，请重新连接")
        }

        override fun onServicesDiscovered() {
            MeshState.isConnected.postValue(true)
            MeshState.connectedDeviceAddress.postValue(address)
            MeshState.statusText.postValue("已连接到 $address")
            MeshState.connectionRetryCount = 0
        }

        override fun onDataReceived(data: ByteArray) {
            MeshState.meshManagerApi.handleNotifications(23, data)
        }

        override fun onMeshMessageReceived(src: Int, data: ByteArray) {}

        override fun onError(error: String) {
            if (MeshState.connectionRetryCount < MeshState.maxRetries - 1) {
                MeshState.connectionRetryCount++
                MeshState.statusText.postValue("连接失败，2秒后重试...")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    attemptConnection()
                }, 2000)
            } else {
                MeshState.statusText.postValue("连接失败: $error")
                MeshState.isConnected.postValue(false)
            }
        }
    }
    
    fun disconnectDevice() {
        MeshState.bleConnection.disconnect()
        MeshState.isConnected.postValue(false)
        MeshState.connectedDeviceAddress.postValue(null)
        MeshState.statusText.postValue("已断开连接")
    }
    

    
    fun importMeshNetwork(jsonData: String) {
        try {
            MeshState.meshManagerApi.importMeshNetworkJson(jsonData)
            MeshState.statusText.postValue("正在导入网络配置...")
        } catch (e: Exception) {
            MeshState.statusText.postValue("导入异常: ${e.message}")
        }
    }
    
    fun exportMeshNetwork(): String? {
        return try {
            MeshState.meshManagerApi.exportMeshNetwork()
        } catch (e: Exception) { null }
    }
    
    private fun saveProxyAddress(address: String) {
        val prefs = getApplication<Application>().getSharedPreferences("MeshPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("last_proxy_address", address).apply()
    }
    
    private fun getSavedProxyAddress(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("MeshPrefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("last_proxy_address", null)
    }
    
    fun hasSavedProxyAddress(): Boolean {
        return getSavedProxyAddress() != null
    }

    override fun onCleared() {
        super.onCleared()
        // Override default behavior to keep connection alive across Activities
    }
}