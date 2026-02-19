package com.example.ble_device_mesh
import android.app.Application
import android.bluetooth.le.ScanResult
import no.nordicsemi.android.mesh.MeshNetwork
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import no.nordicsemi.android.mesh.MeshManagerApi
import no.nordicsemi.android.mesh.MeshManagerCallbacks
import no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode
import no.nordicsemi.android.mesh.transport.GenericLevelSet
import no.nordicsemi.android.mesh.transport.GenericLevelSetUnacknowledged
import no.nordicsemi.android.mesh.transport.SensorGet
import no.nordicsemi.android.mesh.transport.MeshMessage
import no.nordicsemi.android.mesh.transport.SensorStatus
import no.nordicsemi.android.mesh.MeshStatusCallbacks
import no.nordicsemi.android.mesh.transport.ControlMessage

class MeshViewModel(application: Application): AndroidViewModel(application) {
    private val meshManagerApi = MeshManagerApi(application)
    var meshNetWork: MeshNetwork?=null
        private set
    val statusText = MutableLiveData<String>()
    
    // BLE 扫描管理器
    private val bleScanner = BleScannerManager(application)
    val scannedDevices = MutableLiveData<List<ScanResult>>(emptyList())
    val isScanning = MutableLiveData<Boolean>(false)
    
    // BLE 连接管理器
    private val bleConnection = BleConnectionManager(application)
    val isConnected = MutableLiveData<Boolean>(false)
    val connectedDeviceAddress = MutableLiveData<String?>(null)
    
    // 温度数据更新通知 (设备地址 -> 温度值)
    val temperatureUpdates = MutableLiveData<Pair<Int, Float>>()
    private var currentDevice: ScanResult? = null
    private var connectionRetryCount = 0
    private val maxRetries = 3
    private var currentTid = 0

    init {
        statusText.postValue("正在初始化MESH...")
        
        // 设置 MeshStatusCallbacks 以接收传感器数据等消息
        meshManagerApi.setMeshStatusCallbacks(object : MeshStatusCallbacks {
            override fun onTransactionFailed(dst: Int, hasIncompleteTimerExpired: Boolean) {
                Log.e("MeshApp", "事务失败: dst=$dst")
            }

            override fun onUnknownPduReceived(src: Int, accessPayload: ByteArray?) {
                Log.d("MeshApp", "收到未知 PDU: src=$src")
            }

            override fun onBlockAcknowledgementProcessed(dst: Int, source: ControlMessage) {
            }

            override fun onBlockAcknowledgementReceived(src: Int, wrapper: ControlMessage) {
            }

            override fun onMeshMessageProcessed(dst: Int, meshMessage: MeshMessage) {
            }

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
                if (network == null) {
                    // 2. 如果是第一次打开，没有网络，需要创建一个
                    statusText.postValue("没有发现网络，请导入 nRF Mesh 配置")
                    Log.d("MeshApp", "没有网络配置")
                } else {
                    // 强制修改本地地址，避开重放保护
                    try {
                         val provisioner = network.selectedProvisioner
                         // 只有当地址是 0x0001 时才修改，防止覆盖用户自定义配置
                         // 使用 method.invoke 读取目前的值
                         val getMethod = provisioner.javaClass.getDeclaredMethod("getProvisionerAddress")
                         getMethod.isAccessible = true
                         val currentAddr = getMethod.invoke(provisioner) as? Int
                         
                         if (currentAddr == 1) {
                             Log.w("MeshApp", "检测到默认地址 0x0001，正在强制修改为 0x0099...")
                             val setMethod = provisioner.javaClass.getDeclaredMethod("setProvisionerAddress", Integer::class.java)
                             setMethod.isAccessible = true
                             setMethod.invoke(provisioner, 0x0099)
                             statusText.postValue("已自动修复地址冲突 (0x1 -> 0x99)")
                         }
                     } catch (e: Exception) {
                        Log.e("MeshApp", "强制修改地址失败: $e")
                    }
                    
                    meshNetWork = network
                    statusText.postValue("Mesh 网络已就绪: ${network.meshName}")
                    Log.d("MeshApp", "网络加载成功: ${network.meshName}")
                    Log.d("MeshApp", "NetKey 数量: ${network.netKeys.size}")
                    Log.d("MeshApp", "AppKey 数量: ${network.appKeys.size}")
                }
            }

            override fun onNetworkUpdated(meshNetwork: MeshNetwork?) {
                meshNetWork = meshNetwork
                Log.d("MeshApp", "网络已更新")
            }

            override fun onNetworkLoadFailed(error: String?) {
                statusText.postValue("加载失败: $error")
            }

            override fun onNetworkImported(meshNetwork: MeshNetwork?) {
                Log.d("MeshApp", "onNetworkImported 被调用, meshNetwork = $meshNetwork")
                meshNetWork = meshNetwork
                statusText.postValue("Mesh 网络创建成功: ${meshNetwork?.meshName}")
            }

            override fun onNetworkImportFailed(error: String?) {
                Log.e("MeshApp", "网络导入失败: $error")
                statusText.postValue("网络导入失败: $error")
            }

            override fun sendProvisioningPdu(
                meshNode: UnprovisionedMeshNode?,
                pdu: ByteArray?
            ) {
                // 这个方法用于配网过程，暂时不需要实现
                Log.d("MeshApp", "sendProvisioningPdu 被调用")
            }

            override fun onMeshPduCreated(pdu: ByteArray?) {
                // PDU 创建成功，通过蓝牙发送
                Log.d("MeshApp", "Mesh PDU 已创建，长度: ${pdu?.size}")
                
                if (pdu == null) {
                    Log.e("MeshApp", "PDU 为空")
                    return
                }
                
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
        // 在设置完回调后再加载网络
        Log.d("MeshApp", "开始调用 loadMeshNetwork()")
        meshManagerApi.loadMeshNetwork()
        Log.d("MeshApp", "loadMeshNetwork() 调用完成")
    }
    
    // 解析传感器数据 (Present Ambient Temperature 0x004F)
    private fun parseSensorStatus(src: Int, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            try {
                val byte0 = data[offset].toInt()
                
                // ----------------------------------------------------
                // 1. 尝试检测 CH592 固件的自定义格式
                // 固件逻辑: byte0 = (1 << 1) | ((propId >> 10) & 1)
                // ----------------------------------------------------
                if ((byte0 and 0xFE) == 0x02 && offset + 3 < data.size) {
                    val byte1 = data[offset + 1].toInt()
                    val byte2 = data[offset + 2].toInt()
                    
                    // 重组 Property ID
                    // bits 10: byte0 bit 0
                    // bits 9-2: byte1
                    // bits 1-0: byte2 bits 7-6
                    val propIdMsb = (byte0 and 0x01)
                    val propIdMid = (byte1 and 0xFF)
                    val propIdLsb = (byte2 shr 6) and 0x03
                    
                    val customPropId = (propIdMsb shl 10) or (propIdMid shl 2) or propIdLsb
                    
                    if (customPropId == 0x004F) {
                        Log.d("MeshApp", "检测到 CH592 自定义格式 (PropID: 0x004F)")
                        
                        val byte3 = data[offset + 3].toInt()
                        
                        // 重组 Value (Temperature 8 format currently)
                        // Value high 6 bits: byte2 bits 5-0
                        // Value low 2 bits: byte3 bits 7-6
                        val valHigh = (byte2 and 0x3F)
                        val valLow = (byte3 shr 6) and 0x03
                        
                        val rawValue = (valHigh shl 2) or valLow
                        
                        // 转为带符号 byte 处理 (0.5°C steps)
                        val signedValue = rawValue.toByte()
                        val tempVal = signedValue * 0.5f
                        
                        Log.d("MeshApp", "解析到温度 (自定义): $tempVal (Src: 0x${src.toString(16)})")
                        temperatureUpdates.postValue(Pair(src, tempVal))
                        
                        offset += 4
                        continue
                    }
                }

                // ----------------------------------------------------
                // 2. 标准 Format A / B 解析
                // ----------------------------------------------------
                val format = (byte0 shr 7) and 1
                var length = 0
                var propertyId = 0
                var valueOffset = 0
                
                if (format == 0) {
                    // Format A
                    // 格式: Format(1) + Length(4) + PropID_High(3) | PropID_Low(8) | Value...
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
                    // 格式: Format(1) + Length(7) | PropID(8) | PropID(8) | Value...
                    if (offset + 2 >= data.size) break
                    
                    val lenCode = byte0 and 0x7F
                    length = if (lenCode == 0x7F) 1 else lenCode + 1
                    
                    propertyId = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    
                    valueOffset = offset + 3
                    offset += 3 + length
                }
                
                Log.d("MeshApp", "解析属性: ID=0x${propertyId.toString(16)}, Length=$length")
                
                // 检查是否是温度属性 (0x004F: Present Ambient Temperature)
                if (propertyId == 0x004F) {
                    if (valueOffset + length <= data.size) {
                       var tempVal = 0.0f
                       // 根据数据长度解析
                       if (length == 1) {
                           // 8位带符号整数，单位 0.5摄氏度 (Format 8)
                           val raw = data[valueOffset].toByte()
                           tempVal = raw * 0.5f 
                       } else if (length == 2) {
                           // 16位带符号整数，单位 0.01摄氏度 (Format 67)
                           val raw = ((data[valueOffset + 1].toInt() and 0xFF) shl 8) or (data[valueOffset].toInt() and 0xFF)
                           val signedRaw = raw.toShort()
                           tempVal = signedRaw * 0.01f
                       } else if (length == 4) {
                           // 可能是浮点数或其他格式，暂不支持
                           Log.w("MeshApp", "暂不支持 4 字节温度格式")
                           // 尝试按 float 解析
                           // tempVal = ByteBuffer.wrap(data, valueOffset, 4).order(ByteOrder.LITTLE_ENDIAN).float
                       }
                       
                       Log.d("MeshApp", "解析到温度: $tempVal (Src: 0x${src.toString(16)})")
                       temperatureUpdates.postValue(Pair(src, tempVal))
                    }
                }
            } catch (e: Exception) {
                Log.e("MeshApp", "解析传感器数据出错: ${e.message}")
                break
            }
        }
    }


    fun sendBrightness(address: Int, brightness: Int) {
        val network = meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }
        
        // 将亮度值 (0-100) 转换为 Level 值 (-32768 到 32767)
        // 0% -> -32768, 50% -> 0, 100% -> 32767
        val level = ((brightness - 50) * 655.35).toInt()
        
        var srcAddress = 0
        try {
             // 通过反射强行读取被限制的 provisionerAddress
             // 因为该方法被标记为 Restricted，编译时无法访问，但运行时存在
             val method = network.selectedProvisioner.javaClass.getDeclaredMethod("getProvisionerAddress")
             method.isAccessible = true
             srcAddress = method.invoke(network.selectedProvisioner) as Int
        } catch (e: Exception) {
             Log.e("MeshApp", "反射读取地址失败: $e")
        }

        val info = "Src:0x${srcAddress.toString(16)} Dst:0x${address.toString(16)} TID:$currentTid"

        Log.d("MeshApp", "发送亮度控制: $info, brightness=$brightness%, level=$level")
        
        // 使用无应答版本，参数：appKey, level, tId
        val message = GenericLevelSetUnacknowledged(appKey, level, currentTid)
        currentTid++ // Increment TID for next message
        
        meshManagerApi.createMeshPdu(address, message)
        statusText.postValue("发送: $info")
    }
    
    // 读取传感器温度
    fun readTemperature(address: Int) {
        val network = meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }
        
        Log.d("MeshApp", "读取温度: address=0x${address.toString(16)}")
        
        // 发送 Sensor Get 消息
        // Property ID 0x004F 是 Present Ambient Temperature (温度传感器)
        // 如果传 null，会返回所有传感器数据
        val message = SensorGet(appKey, null)
        
        meshManagerApi.createMeshPdu(address, message)
    }
    
    // 开始扫描 BLE 设备
    fun startBleScan() {
        Log.d("MeshApp", "startBleScan 被调用")
        
        if (!bleScanner.isBluetoothEnabled()) {
            Log.e("MeshApp", "蓝牙未开启")
            statusText.postValue("蓝牙未开启")
            return
        }
        
        isScanning.postValue(true)
        statusText.postValue("正在扫描 BLE Mesh 设备...")
        
        bleScanner.startScan(object : BleScannerManager.ScanListener {
            override fun onDeviceFound(device: ScanResult) {
                val currentList = scannedDevices.value ?: emptyList()
                // 避免重复添加
                if (currentList.none { it.device.address == device.device.address }) {
                    scannedDevices.postValue(currentList + device)
                    Log.d("MeshApp", "发现设备: ${device.device.address}")
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                isScanning.postValue(false)
                val errorMsg = when(errorCode) {
                    -1 -> "缺少蓝牙权限"
                    -2 -> "安全异常"
                    else -> "扫描失败: $errorCode"
                }
                statusText.postValue(errorMsg)
                Log.e("MeshApp", errorMsg)
            }
        })
    }
    
    // 停止扫描
    fun stopBleScan() {
        bleScanner.stopScan()
        isScanning.postValue(false)
        statusText.postValue("扫描已停止")
    }
    
    // 自动连接到最近的 Proxy 节点
    fun autoConnectToProxy() {
        Log.d("MeshApp", "开始自动连接 Proxy")
        statusText.postValue("正在搜索 Proxy 节点...")
        
        var foundProxy: ScanResult? = null
        
        bleScanner.startScan(object : BleScannerManager.ScanListener {
            override fun onDeviceFound(device: ScanResult) {
                // 找到第一个 Proxy 节点就连接
                if (foundProxy == null) {
                    foundProxy = device
                    Log.d("MeshApp", "找到 Proxy 节点: ${device.device.address}")
                    bleScanner.stopScan()
                    connectToDevice(device)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                statusText.postValue("未找到 Proxy 节点")
                Log.e("MeshApp", "扫描失败: $errorCode")
            }
        })
        
        // 10秒后如果还没找到，停止扫描
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (foundProxy == null) {
                bleScanner.stopScan()
                statusText.postValue("未找到 Proxy 节点，请手动连接")
                Log.w("MeshApp", "10秒内未找到 Proxy 节点")
            }
        }, 10000)
    }
    
    // 连接到设备
    fun connectToDevice(device: ScanResult) {
        currentDevice = device
        
        // 保存成功的 Proxy 地址
        saveProxyAddress(device.device.address)
        
        connectionRetryCount = 0
        attemptConnection()
    }
    
    // 直接连接到已保存的 Proxy 地址
    fun connectToSavedProxy() {
        val savedAddress = getSavedProxyAddress()
        if (savedAddress == null) {
            statusText.postValue("没有保存的 Proxy 地址，请先扫描一次")
            return
        }
        
        Log.d("MeshApp", "尝试直接连接到保存的地址: $savedAddress")
        statusText.postValue("正在连接到保存的设备 $savedAddress...")
        
        bleConnection.connect(savedAddress, object : BleConnectionManager.ConnectionListener {
            override fun onConnected() {
                Log.d("MeshApp", "设备已连接 (直连)")
                statusText.postValue("设备已连接，正在发现服务...")
                connectedDeviceAddress.postValue(savedAddress)
                connectionRetryCount = 0
            }

            override fun onDisconnected() {
                Log.d("MeshApp", "设备已断开")
                isConnected.postValue(false)
                connectedDeviceAddress.postValue(null)
                statusText.postValue("设备已断开，请重新连接")
            }

            override fun onServicesDiscovered() {
                Log.d("MeshApp", "服务发现完成")
                isConnected.postValue(true)
                statusText.postValue("已连接到 $savedAddress")
            }

            override fun onDataReceived(data: ByteArray) {
                meshManagerApi.handleNotifications(23, data)
            }

            override fun onMeshMessageReceived(src: Int, data: ByteArray) {
            }

            override fun onError(error: String) {
                Log.e("MeshApp", "直连错误: $error")
                statusText.postValue("连接失败: $error")
                isConnected.postValue(false)
            }
        })
    }
    
    private fun saveProxyAddress(address: String) {
        val prefs = getApplication<Application>().getSharedPreferences("MeshPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("last_proxy_address", address).apply()
        Log.d("MeshApp", "已保存 Proxy 地址: $address")
    }
    
    private fun getSavedProxyAddress(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("MeshPrefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("last_proxy_address", null)
    }
    
    fun hasSavedProxyAddress(): Boolean {
        return getSavedProxyAddress() != null
    }

    private fun attemptConnection() {
        val device = currentDevice ?: return
        
        Log.d("MeshApp", "尝试连接到设备: ${device.device.address} (第 ${connectionRetryCount + 1} 次)")
        statusText.postValue("正在连接到 ${device.device.address}... (${connectionRetryCount + 1}/$maxRetries)")
        
        bleConnection.connect(device.device, object : BleConnectionManager.ConnectionListener {
            override fun onConnected() {
                Log.d("MeshApp", "设备已连接")
                statusText.postValue("设备已连接，正在发现服务...")
                connectionRetryCount = 0
            }
            
            override fun onDisconnected() {
                Log.d("MeshApp", "设备已断开")
                isConnected.postValue(false)
                connectedDeviceAddress.postValue(null)
                statusText.postValue("设备已断开，请重新连接")
            }
            
            override fun onServicesDiscovered() {
                Log.d("MeshApp", "服务发现完成")
                isConnected.postValue(true)
                connectedDeviceAddress.postValue(device.device.address)
                statusText.postValue("已连接到 ${device.device.address}，可以控制设备")
            }
            
            override fun onDataReceived(data: ByteArray) {
                Log.d("MeshApp", "收到设备数据: ${data.size} 字节")
                // 必须将收到的数据交给 MeshManagerApi 处理
                // 否则 Mesh 协议栈无法更新状态，设备可能会因为收不到响应而断开连接
                meshManagerApi.handleNotifications(23, data)
            }
            
            override fun onMeshMessageReceived(src: Int, data: ByteArray) {
                Log.d("MeshApp", "收到来自 0x${src.toString(16)} 的 Mesh 消息")
                // 这里可以进一步解析消息类型
                // 但实际上 MeshManagerApi.handleNotifications 会触发相应的回调
            }
            
            override fun onError(error: String) {
                Log.e("MeshApp", "连接错误: $error")
                
                // 如果是连接失败且还有重试次数，则重试
                if (connectionRetryCount < maxRetries - 1) {
                    connectionRetryCount++
                    Log.d("MeshApp", "将在 2 秒后重试...")
                    statusText.postValue("连接失败，2秒后重试... (${connectionRetryCount}/$maxRetries)")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        attemptConnection()
                    }, 2000)
                } else {
                    statusText.postValue("连接失败: $error\n\n请确保:\n1. 完全关闭 nRF Mesh app\n2. 设备在蓝牙范围内\n3. 重启设备或手机蓝牙")
                    isConnected.postValue(false)
                }
            }
        })
    }
    
    // 断开设备连接
    fun disconnectDevice() {
        bleConnection.disconnect()
        isConnected.postValue(false)
        connectedDeviceAddress.postValue(null)
        statusText.postValue("已断开连接")
    }
    
    // 导入 Mesh 网络配置
    fun importMeshNetwork(jsonData: String) {
        try {
            Log.d("MeshApp", "开始导入网络配置")
            meshManagerApi.importMeshNetworkJson(jsonData)
            Log.d("MeshApp", "网络配置导入请求已发送")
            statusText.postValue("正在导入网络配置...")
        } catch (e: Exception) {
            Log.e("MeshApp", "导入异常: ${e.message}")
            statusText.postValue("导入异常: ${e.message}")
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        stopBleScan()
        disconnectDevice()
    }

}