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
import no.nordicsemi.android.mesh.transport.TimeGet
import no.nordicsemi.android.mesh.transport.TimeSet
import no.nordicsemi.android.mesh.transport.TimeStatus
import kotlin.math.pow

class MeshViewModel(application: Application): AndroidViewModel(application) {

    // 全局单例状态持有者
    // 使用 Object 来保存 Application 级别的状态，确保在不同 Activity 之间共享
    internal object MeshState {
        var isInitialized = false
        lateinit var meshManagerApi: MeshManagerApi
        lateinit var bleConnection: BleConnectionManager
        lateinit var bleScanner: BleScannerManager
        
        // LiveData 状态
        val statusText = MutableLiveData<String>()
        val isConnected = MutableLiveData<Boolean>(false)
        val isNetworkLoaded = MutableLiveData<Boolean>(false)
        val temperatureUpdates = MutableLiveData<Pair<Int, Float>>()
        val lightLevelUpdates = MutableLiveData<Pair<Int, Float>>()
        val timeUpdates = MutableLiveData<Pair<Int, Long>>()
        val schedulerUpdates = MutableLiveData<Pair<Int, Int>>() // Pair<src, schedules bitmap>
        val schedulerActionUpdates = MutableLiveData<Triple<Int, Int, SchedulerAction>>() // Triple<src, index, action>
        val scannedDevices = MutableLiveData<List<ScanResult>>(emptyList())
        val isScanning = MutableLiveData<Boolean>(false)
        val connectedDeviceAddress = MutableLiveData<String?>(null)
        val currentProvisionerAddress = MutableLiveData<Int>()
        val currentRssi = MutableLiveData<Int>(-999)
        
        // 超时处理
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var schedulerGetTimeoutRunnable: Runnable? = null
        
        // 配网相关
        val unprovisionedDevices = MutableLiveData<List<UnprovisionedMeshNode>>(emptyList())
        val isProvisioning = MutableLiveData<Boolean>(false)
        val provisioningStatus = MutableLiveData<String>("")
        val provisioningComplete = MutableLiveData<Pair<Boolean, Int>>()
        
        var meshNetWork: MeshNetwork? = null
        var currentTid = 0
        var connectionRetryCount = 0
        val maxRetries = 3
        var currentDevice: ScanResult? = null
        var currentUnprovisionedNode: UnprovisionedMeshNode? = null
    }

    // 暴露给 View 的属性 (代理到 MeshState)
    val statusText get() = MeshState.statusText
    val isConnected get() = MeshState.isConnected
    val isNetworkLoaded get() = MeshState.isNetworkLoaded
    val temperatureUpdates get() = MeshState.temperatureUpdates
    val lightLevelUpdates get() = MeshState.lightLevelUpdates
    val timeUpdates get() = MeshState.timeUpdates
    val schedulerUpdates get() = MeshState.schedulerUpdates
    val schedulerActionUpdates get() = MeshState.schedulerActionUpdates
    val scannedDevices get() = MeshState.scannedDevices
    val isScanning get() = MeshState.isScanning
    val connectedDeviceAddress get() = MeshState.connectedDeviceAddress
    val currentProvisionerAddress get() = MeshState.currentProvisionerAddress
    
    // 配网相关
    val unprovisionedDevices get() = MeshState.unprovisionedDevices
    val isProvisioning get() = MeshState.isProvisioning
    val provisioningStatus get() = MeshState.provisioningStatus
    val provisioningComplete get() = MeshState.provisioningComplete
    
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
                Log.e("MeshApp", "事务失败: dst=0x${dst.toString(16)}, timeout=$hasIncompleteTimerExpired")
                MeshState.statusText.postValue("设备 0x${dst.toString(16)} 响应超时")
            }

            override fun onUnknownPduReceived(src: Int, accessPayload: ByteArray?) {
                Log.d("MeshApp", "收到未知 PDU: src=$src")
            }

            override fun onBlockAcknowledgementProcessed(dst: Int, source: ControlMessage) {}
            override fun onBlockAcknowledgementReceived(src: Int, wrapper: ControlMessage) {}
            override fun onMeshMessageProcessed(dst: Int, meshMessage: MeshMessage) {}
            
            override fun onHeartbeatMessageReceived(src: Int, heartbeatMessage: ControlMessage) {
                Log.d("MeshApp", "收到心跳消息: src=0x${src.toString(16)}")
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
                } else if (meshMessage is TimeStatus) {
                    val data = meshMessage.parameters
                    Log.d("MeshApp", "收到 TimeStatus (Src: 0x${src.toString(16)})")
                    if (data != null && data.isNotEmpty()) {
                        Log.d("MeshApp", "TimeStatus 原始数据: ${data.joinToString(" ") { "%02X".format(it) }}")
                    }
                    parseTimeStatus(src, meshMessage)
                } else if (meshMessage.opCode == 0x824A) {
                    Log.d("MeshApp", "收到 SchedulerStatus (Src: 0x${src.toString(16)})")
                    parseSchedulerStatus(src, meshMessage)
                } else if (meshMessage.opCode == 0x5F) {
                    Log.d("MeshApp", "收到 SchedulerActionStatus (Src: 0x${src.toString(16)})")
                    parseSchedulerActionStatus(src, meshMessage)
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
                    // === 自动设置设备唯一地址 ===
                    try {
                        val deviceAddress = getOrGenerateDeviceAddress(getApplication())
                        setProvisionerAddressInternal(network, deviceAddress)
                        Log.d("MeshApp", "已设置设备地址: 0x${deviceAddress.toString(16)}")
                    } catch (e: Exception) {
                        Log.e("MeshApp", "设置设备地址失败: $e")
                    }

                    MeshState.meshNetWork = network
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

                if (meshNetwork != null) {
                    // === 使用配置文件中的原始 Provisioner 地址 ===
                    val originalAddr = meshNetwork.selectedProvisioner?.provisionerAddress ?: 0
                    MeshState.currentProvisionerAddress.postValue(originalAddr)
                    statusText.postValue("网络已导入 (Provisioner地址: 0x${originalAddr.toString(16)})")
                    Log.d("MeshApp", "使用配置文件中的 Provisioner 地址: 0x${originalAddr.toString(16)}")
                } else {
                    statusText.postValue("网络导入失败")
                }

                isNetworkLoaded.postValue(true)
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
    
    // 解析 Scheduler Status
    private fun parseSchedulerStatus(src: Int, message: MeshMessage) {
        try {
            // 取消超时
            MeshState.schedulerGetTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }
            
            val schedules = SchedulerMessageHelper.parseSchedulerStatus(message) ?: return
            Log.d("MeshApp", "解析到 Scheduler 状态: 0x${schedules.toString(16)} (Src: 0x${src.toString(16)})")

            // 显示哪些索引已设置
            val setIndexes = mutableListOf<Int>()
            for (i in 0..15) {
                if ((schedules and (1 shl i)) != 0) {
                    setIndexes.add(i)
                }
            }
            Log.d("MeshApp", "已设置的调度索引: ${setIndexes.joinToString(", ")}")

            MeshState.schedulerUpdates.postValue(Pair(src, schedules))
            MeshState.statusText.postValue("已收到设备 0x${src.toString(16)} 的调度状态")

            // 自动读取每个已设置的计划详情
            if (setIndexes.isNotEmpty()) {
                Log.d("MeshApp", "开始读取 ${setIndexes.size} 个计划的详细信息")
                setIndexes.forEachIndexed { idx, scheduleIndex ->
                    // 延迟发送，避免消息冲突
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        readSchedulerAction(src, scheduleIndex)
                    }, (idx * 500).toLong())
                }
            }
        } catch (e: Exception) {
            Log.e("MeshApp", "解析 SchedulerStatus 失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // 解析 Scheduler Action Status
    private fun parseSchedulerActionStatus(src: Int, message: MeshMessage) {
        try {
            val action = SchedulerMessageHelper.parseSchedulerActionStatus(message) ?: return

            Log.d("MeshApp", "解析到 Scheduler Action (Src: 0x${src.toString(16)}):")
            Log.d("MeshApp", "  - Index: ${action.index}")
            Log.d("MeshApp", "  - Year: ${action.year}")
            Log.d("MeshApp", "  - Month: ${action.month}")
            Log.d("MeshApp", "  - Day: ${action.day}")
            Log.d("MeshApp", "  - Hour: ${action.hour}")
            Log.d("MeshApp", "  - Minute: ${action.minute}")
            Log.d("MeshApp", "  - Second: ${action.second}")
            Log.d("MeshApp", "  - DayOfWeek: ${action.dayOfWeek}")
            Log.d("MeshApp", "  - Action: ${action.action}")
            Log.d("MeshApp", "  - TransitionTime: ${action.transitionTime}")
            Log.d("MeshApp", "  - SceneNumber: ${action.sceneNumber}")

            MeshState.schedulerActionUpdates.postValue(Triple(src, action.index, action))
            MeshState.statusText.postValue("已收到设备 0x${src.toString(16)} 的调度动作 #${action.index}")
        } catch (e: Exception) {
            Log.e("MeshApp", "解析 SchedulerActionStatus 失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // 解析时间状态
    private fun parseTimeStatus(src: Int, timeStatus: TimeStatus) {
        try {
            Log.d("MeshApp", "收到 TimeStatus 原始数据:")
            Log.d("MeshApp", "  - taiSeconds: ${timeStatus.taiSeconds}")
            Log.d("MeshApp", "  - subSecond: ${timeStatus.subSecond}")
            Log.d("MeshApp", "  - uncertainty: ${timeStatus.uncertainty}")

            // TimeStatus 包含 TAI 秒数
            val taiSeconds = timeStatus.taiSeconds

            if (taiSeconds == null) {
                Log.w("MeshApp", "TimeStatus 中 taiSeconds 为空")
                return
            }

            // TAI 转 Unix 时间戳（减去 37 秒偏移）
            val unixTime = taiSeconds.toLong() - 37L

            Log.d("MeshApp", "解析到设备时间: TAI=$taiSeconds, Unix=$unixTime (Src: 0x${src.toString(16)})")
            MeshState.timeUpdates.postValue(Pair(src, unixTime))

            // 更新状态，表示成功收到回复
            MeshState.statusText.postValue("已收到设备 0x${src.toString(16)} 的时间")
        } catch (e: Exception) {
            Log.e("MeshApp", "解析 TimeStatus 失败: ${e.message}")
            e.printStackTrace()
        }
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
                // PropID = 0x0071 (温度), 0000001110001
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

                    if (customPropId == 0x004D || customPropId == 0x004F || customPropId == 0x0071) {
                        val byte3 = data[offset + 3].toInt()
                        val valHigh = (byte2 and 0x3F)
                        val valLow = (byte3 shr 6) and 0x03
                        val rawValue = (valHigh shl 2) or valLow
                        val tempVal = rawValue.toByte() * 0.5f

                        Log.d("MeshApp", "解析到温度 (自定义 PropID=0x${customPropId.toString(16)}): $tempVal (Src: 0x${src.toString(16)})")
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
                    val lenCode = (byte0 shr 1) and 0xF
                    length = if (lenCode == 0xF) 0 else lenCode + 1 
                    val propIdLow = (byte0 shr 5) and 0x7
                    val propIdHigh = data[offset + 1].toInt() and 0xFF
                    propertyId = (propIdHigh shl 3) or propIdLow
                    valueOffset = offset + 2
                    offset += 2 + length
                } else {
                    // Format B: bit7=1, bit6-0=length code
                    if (offset + 2 >= data.size) break
                    val lenCode = byte0 and 0x7F  // 取低7位作为 length code
                    length = if (lenCode == 0x7F) 0 else lenCode + 1
                    // Property ID 小端序：Byte1=低字节, Byte2=高字节
                    propertyId = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    valueOffset = offset + 3
                    offset += 3 + length
                }
                
                Log.d("MeshApp", "Standard PropID: 0x${propertyId.toString(16)}, Length: $length")

                // 温度属性：
                // 0x004D = Present Ambient Temperature (标准环境温度)
                // 0x004F = Present Outdoor Ambient Temperature (室外温度)
                // 0x0071 = Present Indoor Ambient Temperature (室内温度 - CH592 固件)
                if (propertyId == 0x004D || propertyId == 0x004F || propertyId == 0x0071) {
                    if (valueOffset + length <= data.size) {
                       var tempVal = 0.0f
                       if (length == 1) {
                           // 1字节：有符号整数，0.5°C 精度
                           val raw = data[valueOffset].toByte()
                           tempVal = raw * 0.5f
                           Log.d("MeshApp", "温度解析 (1字节): raw=$raw, temp=$tempVal")
                       } else if (length == 2) {
                           // 2字节：小端序，有符号整数
                           val byte0 = data[valueOffset].toInt() and 0xFF
                           val byte1 = data[valueOffset + 1].toInt() and 0xFF
                           val raw = ((byte1 shl 8) or byte0).toShort()  // 转为有符号 short

                           // 根据 Mesh Model Spec，温度单位是 0.01°C
                           // 例如：25.00°C -> 设备发送 2500 (0x09C4)
                           tempVal = raw / 100.0f
                           Log.d("MeshApp", "温度解析 (2字节): byte0=0x${byte0.toString(16)}, byte1=0x${byte1.toString(16)}, raw=$raw, temp=$tempVal")
                       }
                       Log.d("MeshApp", "解析到温度 (PropID=0x${propertyId.toString(16)}): $tempVal°C (Src: 0x${src.toString(16)})")
                       MeshState.temperatureUpdates.postValue(Pair(src, tempVal))
                    }
                } else {
                    // 打印未识别的属性，帮助调试
                    Log.d("MeshApp", "未识别的 Property ID: 0x${propertyId.toString(16)}, 数据长度: $length")
                    if (valueOffset + length <= data.size) {
                        val rawData = data.slice(valueOffset until valueOffset + length).joinToString(" ") { "%02X".format(it) }
                        Log.d("MeshApp", "原始数据: $rawData")
                    }
                }

                // 光照度属性 0x004E (Present Ambient Light Level)
                // 固件直接存储原始ADC值，3字节uint24格式（小端序）
                if (propertyId == 0x004E) {
                    if (valueOffset + length <= data.size && length >= 3) {
                        // 读取3字节小端序数据
                        val raw = (data[valueOffset].toInt() and 0xFF) or
                                  ((data[valueOffset + 1].toInt() and 0xFF) shl 8) or
                                  ((data[valueOffset + 2].toInt() and 0xFF) shl 16)

                        // 固件存储的是原始ADC值（0-4095，12位ADC）
                        // 直接显示原始值，让用户看到实际的光敏电阻读数
                        Log.d("MeshApp", "解析到光照度: raw=$raw (Src: 0x${src.toString(16)})")
                        MeshState.lightLevelUpdates.postValue(Pair(src, raw.toFloat()))
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
        
        // 应用亮度映射曲线，补偿 OC6701 的非线性特性
        val mappedBrightness = mapBrightnessForOC6701(brightness)
        val level = ((mappedBrightness - 50) * 655.35).toInt()
        
        // 尝试通过反射获取源地址用于日志（可选）
        var srcAddress = 0
        try {
             val method = network.selectedProvisioner.javaClass.getDeclaredMethod("getProvisionerAddress")
             method.isAccessible = true
             srcAddress = method.invoke(network.selectedProvisioner) as Int
        } catch (e: Exception) {}

        val info = "Src:0x${srcAddress.toString(16)} Dst:0x${address.toString(16)} TID:${MeshState.currentTid}"
        Log.d("MeshApp", "发送亮度控制: $info, UI=$brightness%, mapped=$mappedBrightness%, level=$level")
        
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
    
    /**
     * OC6701 亮度映射曲线
     * 由于 OC6701 在 0-20% 亮度变化大，20-100% 变化小
     * 使用 1.5 次方曲线，比平方根更温和
     * 
     * @param uiBrightness UI 显示的亮度值 (0-100)
     * @return 映射后发送给硬件的亮度值 (0-100)
     */
    private fun mapBrightnessForOC6701(uiBrightness: Int): Int {
        if (uiBrightness <= 0) return 0
        if (uiBrightness >= 100) return 100
        
        // 1.5次方曲线：y = (x/100)^1.5 * 100
        // UI 1% -> 1%, UI 10% -> 3%, UI 25% -> 13%, UI 50% -> 35%, UI 100% -> 100%
        val x = uiBrightness / 100.0
        val mapped = x.pow(1.5) * 100
        return mapped.toInt().coerceIn(0, 100)
    }
    

    fun sendOnOff(address: Int, on: Boolean, brightness: Int = 100) {
        val network = MeshState.meshNetWork ?: return
        val appKey = network.appKeys.firstOrNull() ?: return
        val level = if (on) ((brightness - 50) * 655.35).toInt() else -32768
        val message = GenericLevelSetUnacknowledged(appKey, level, MeshState.currentTid)
        MeshState.currentTid++
        try {
            MeshState.meshManagerApi.createMeshPdu(address, message)
        } catch (e: Exception) {
            Log.e("MeshApp", "sendOnOff 失败: ${e.message}")
        }
    }

    fun readSensors(address: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }

        val appKey = network.appKeys.firstOrNull() ?: run {
             Log.e("MeshApp", "未找到 App Key")
             return
        }

        Log.d("MeshApp", "读取传感器数据: address=0x${address.toString(16)}")

        // 请求所有传感器（不指定 Property ID）
        // 固件会返回所有传感器数据：温度 (0x0071) 和光照 (0x004E)
        try {
            Log.d("MeshApp", "请求所有传感器数据")
            // 传入 null 表示请求所有传感器
            val message = SensorGet(appKey, null)
            MeshState.meshManagerApi.createMeshPdu(address, message)
        } catch (e: Exception) {
             Log.e("MeshApp", "请求传感器失败: ${e.message}")
             MeshState.statusText.postValue("读取传感器失败: ${e.message}")
        }
    }
    
    // 兼容旧代码，保留readTemperature作为别名
    @Deprecated("使用 readSensors 代替", ReplaceWith("readSensors(address)"))
    fun readTemperature(address: Int) = readSensors(address)
    
    // 读取设备时间
    fun readDeviceTime(address: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }

        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        Log.d("MeshApp", "发送 TimeGet 到地址: 0x${address.toString(16)}, AppKey Index: ${appKey.keyIndex}")
        val message = TimeGet(appKey)

        try {
            MeshState.meshManagerApi.createMeshPdu(address, message)
            MeshState.statusText.postValue("正在读取设备时间...")

            // 设置 5 秒超时
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // 检查是否收到了回复（通过检查状态文本）
                if (MeshState.statusText.value?.contains("正在读取设备时间") == true) {
                    MeshState.statusText.postValue("设备 0x${address.toString(16)} 响应超时 - 固件可能未实现 Time Server")
                    Log.w("MeshApp", "TimeGet 超时，固件可能：1) 未绑定 AppKey 2) 发送缓冲区满 3) 未实现回复逻辑")
                }
            }, 5000)
        } catch (e: Exception) {
            Log.e("MeshApp", "创建时间读取 PDU 失败: ${e.message}")
            MeshState.statusText.postValue("读取时间失败: ${e.message}")
        }
    }
    
    // 读取 Scheduler 寄存器状态
    fun readScheduler(address: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }

        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        Log.d("MeshApp", "发送 SchedulerGet 到地址: 0x${address.toString(16)}")

        // 尝试创建真正的 SchedulerGet 消息
        val message = SchedulerMessageHelper.createSchedulerGet(appKey)
        if (message != null) {
            try {
                MeshState.meshManagerApi.createMeshPdu(address, message)
                MeshState.statusText.postValue("正在读取调度状态...")

                // 设置 5 秒超时
                MeshState.schedulerGetTimeoutRunnable = Runnable {
                    if (MeshState.statusText.value?.contains("正在读取调度状态") == true) {
                        MeshState.statusText.postValue("设备 0x${address.toString(16)} 响应超时 - 可能不支持 Scheduler")
                        Log.w("MeshApp", "SchedulerGet 超时，可能原因：1) 固件未实现 Scheduler Server 2) Model 未绑定 3) OpCode 不匹配")
                    }
                }
                MeshState.mainHandler.postDelayed(MeshState.schedulerGetTimeoutRunnable!!, 5000)
            } catch (e: Exception) {
                Log.e("MeshApp", "创建 SchedulerGet PDU 失败: ${e.message}")
                MeshState.statusText.postValue("读取调度失败: ${e.message}")
            }
        } else {
            Log.e("MeshApp", "无法创建 SchedulerGet 消息")
            MeshState.statusText.postValue("不支持 Scheduler 功能")
        }
    }

    // 读取指定索引的 Scheduler Action
    fun readSchedulerAction(address: Int, index: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }

        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        if (index < 0 || index > 15) {
            Log.e("MeshApp", "无效的调度索引: $index (有效范围: 0-15)")
            MeshState.statusText.postValue("无效的调度索引")
            return
        }

        Log.d("MeshApp", "发送 SchedulerActionGet 到地址: 0x${address.toString(16)}, 索引: $index")
        val message = SchedulerMessageHelper.createSchedulerActionGet(appKey, index) ?: run {
            Log.e("MeshApp", "创建 SchedulerActionGet 消息失败")
            return
        }

        try {
            MeshState.meshManagerApi.createMeshPdu(address, message)
            MeshState.statusText.postValue("正在读取调度动作 #$index...")

            // 设置 5 秒超时
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (MeshState.statusText.value?.contains("正在读取调度动作") == true) {
                    MeshState.statusText.postValue("设备 0x${address.toString(16)} 响应超时")
                    Log.w("MeshApp", "SchedulerActionGet 超时")
                }
            }, 5000)
        } catch (e: Exception) {
            Log.e("MeshApp", "创建 SchedulerActionGet PDU 失败: ${e.message}")
            MeshState.statusText.postValue("读取调度动作失败: ${e.message}")
        }
    }

    // 设置设备时间（同步当前手机时间）
    fun setDeviceTime(address: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }
        
        // 获取当前 Unix 时间戳（秒）
        val currentTime = System.currentTimeMillis() / 1000
        
        // TAI 时间 = Unix 时间 + 37秒（TAI-UTC差值）
        val taiSeconds = (currentTime + 37).toInt()
        
        Log.d("MeshApp", "设置设备时间: address=0x${address.toString(16)}, Unix=$currentTime, TAI=$taiSeconds")
        
        // 创建 MeshTAITime 对象
        val taiTime = no.nordicsemi.android.mesh.MeshTAITime(
            taiSeconds,  // TAI 秒数
            0,           // 亚秒 (0-255)
            0,           // 不确定性
            false,       // 时间权威性
            37,          // TAI-UTC 差值（2024年）
            32           // 时区偏移 UTC+8 = 8*4 = 32（单位：15分钟）
        )
        
        // TimeSet 参数：appKey, MeshTAITime对象
        val message = TimeSet(appKey, taiTime)
        
        try {
            MeshState.meshManagerApi.createMeshPdu(address, message)
            MeshState.statusText.postValue("正在同步时间到设备...")
        } catch (e: Exception) {
            Log.e("MeshApp", "创建时间设置 PDU 失败: ${e.message}")
            MeshState.statusText.postValue("设置时间失败: ${e.message}")
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
            
            // 开始定期读取 RSSI
            startRssiUpdates()
        }

        override fun onDataReceived(data: ByteArray) {
            MeshState.meshManagerApi.handleNotifications(23, data)
        }

        override fun onMeshMessageReceived(src: Int, data: ByteArray) {}
        
        override fun onRssiRead(rssi: Int) {
            MeshState.currentRssi.postValue(rssi)
        }

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
    
    private var rssiUpdateHandler: android.os.Handler? = null
    private val rssiUpdateRunnable = object : Runnable {
        override fun run() {
            if (MeshState.isConnected.value == true) {
                MeshState.bleConnection.readRssi()
                rssiUpdateHandler?.postDelayed(this, 2000) // 每2秒更新一次
            }
        }
    }
    
    private fun startRssiUpdates() {
        rssiUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        rssiUpdateHandler?.post(rssiUpdateRunnable)
    }
    
    private fun stopRssiUpdates() {
        rssiUpdateHandler?.removeCallbacks(rssiUpdateRunnable)
        rssiUpdateHandler = null
        MeshState.currentRssi.postValue(-999)
    }

    fun disconnectDevice() {
        stopRssiUpdates()
        MeshState.bleConnection.disconnect()
        MeshState.isConnected.postValue(false)
        MeshState.connectedDeviceAddress.postValue(null)
        MeshState.statusText.postValue("已断开连接")
    }

    override fun onCleared() {
        super.onCleared()
        stopRssiUpdates()
    }

    private fun setProvisionerAddressInternal(network: MeshNetwork, address: Int) {
        // 检查地址是否已存在
        var nodeExists = false
        try {
            val method = network.javaClass.getMethod("getProvisionedNode", Int::class.javaPrimitiveType)
            val node = method.invoke(network, address)
            nodeExists = (node != null)
        } catch (e: Exception) {
            Log.w("MeshApp", "无法验证节点: $e")
        }

        // 不存在则创建虚拟节点
        if (!nodeExists) {
            createVirtualNode(network, address)
            Log.d("MeshApp", "已创建虚拟节点: 0x${address.toString(16)}")
        }

        // 设置 provisioner 地址
        val provisioner = network.selectedProvisioner
        val setMethod = try {
            provisioner.javaClass.getDeclaredMethod("setProvisionerAddress", Integer::class.java)
        } catch (e: Exception) {
            provisioner.javaClass.getDeclaredMethod("setProvisionerAddress", Int::class.javaPrimitiveType)
        }
        setMethod.isAccessible = true
        setMethod.invoke(provisioner, address)

        MeshState.currentProvisionerAddress.postValue(address)
    }
    

    
    fun importMeshNetwork(jsonData: String) {
        try {
            MeshState.meshManagerApi.importMeshNetworkJson(jsonData)

            // 等待导入完成后在 onNetworkImported 回调中自动设置地址
            // 不在这里手动设置，避免重复操作
        } catch (e: Exception) {
            MeshState.statusText.postValue("导入异常: ${e.message}")
        }
    }
    
    fun exportMeshNetwork(): String? {
        return try {
            MeshState.meshManagerApi.exportMeshNetwork()
        } catch (e: Exception) { null }
    }
    
    fun setProvisionerAddress(address: Int) {
        val network = MeshState.meshNetWork
        if (network == null) {
            MeshState.statusText.postValue("网络未加载")
            return
        }
        
        // 检查地址是否已存在
        var nodeExists = false
        try {
            val method = network.javaClass.getMethod("getProvisionedNode", Int::class.javaPrimitiveType)
            val node = method.invoke(network, address)
            nodeExists = (node != null)
        } catch (e: Exception) {
            Log.e("MeshApp", "验证地址失败: $e")
        }
        
        // 如果节点不存在，创建虚拟节点
        if (!nodeExists) {
            try {
                createVirtualNode(network, address)
                Log.d("MeshApp", "已创建虚拟节点: 0x${address.toString(16)}")
            } catch (e: Exception) {
                Log.e("MeshApp", "创建虚拟节点失败: $e")
                MeshState.statusText.postValue("地址 0x${address.toString(16)} 无效且无法创建虚拟节点")
                return
            }
        }
        
        try {
            val provisioner = network.selectedProvisioner
            val setMethod = try {
                provisioner.javaClass.getDeclaredMethod("setProvisionerAddress", Integer::class.java)
            } catch (e: Exception) {
                provisioner.javaClass.getDeclaredMethod("setProvisionerAddress", Int::class.javaPrimitiveType)
            }
            setMethod.isAccessible = true
            setMethod.invoke(provisioner, address)
            
            MeshState.currentProvisionerAddress.postValue(address)
            MeshState.statusText.postValue("地址已更新为: 0x${address.toString(16)}")
        } catch (e: Exception) {
            Log.e("MeshApp", "设置地址失败: $e")
            MeshState.statusText.postValue("设置失败: ${e.message}")
        }
    }
    
    private fun createVirtualNode(network: MeshNetwork, address: Int) {
        // 通过反射创建虚拟节点
        val nodeClass = Class.forName("no.nordicsemi.android.mesh.transport.ProvisionedMeshNode")
        val constructor = nodeClass.getDeclaredConstructor(
            String::class.java,  // uuid
            ByteArray::class.java,  // deviceKey
            Int::class.javaPrimitiveType,  // unicastAddress
            Int::class.javaPrimitiveType   // numberOfElements
        )
        constructor.isAccessible = true
        
        val uuid = java.util.UUID.randomUUID().toString()
        val deviceKey = ByteArray(16) { 0xFF.toByte() }  // 虚拟 key
        
        val node = constructor.newInstance(uuid, deviceKey, address, 1)
        
        // 添加到网络
        val addMethod = network.javaClass.getDeclaredMethod("addNode", nodeClass)
        addMethod.isAccessible = true
        addMethod.invoke(network, node)
    }
    
    /**
     * 根据设备唯一标识生成 Provisioner 地址
     * 地址范围: 0x200-0x2FF (512-767)
     * 包含冲突检测和解决机制
     */
    fun getOrGenerateDeviceAddress(context: android.content.Context): Int {
        val prefs = context.getSharedPreferences("mesh_config", android.content.Context.MODE_PRIVATE)
        
        // 检查是否已保存地址
        val savedAddress = prefs.getInt("provisioner_address", -1)
        if (savedAddress != -1) {
            Log.d("MeshApp", "使用已保存的地址: 0x${savedAddress.toString(16)}")
            return savedAddress
        }
        
        // 基于 Android ID 生成地址
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "default"
        
        // 使用哈希生成初始地址
        val hash = androidId.hashCode()
        var address = 0x200 + (Math.abs(hash) % 256)
        
        // 冲突检测：检查该地址是否已被其他设备使用
        // 通过在地址后追加随机偏移来解决冲突
        val network = MeshState.meshNetWork
        if (network != null) {
            var attempts = 0
            while (attempts < 256) {
                try {
                    val method = network.javaClass.getMethod("getProvisionedNode", Int::class.javaPrimitiveType)
                    val node = method.invoke(network, address)
                    
                    // 如果节点存在且不是虚拟节点，说明地址被占用
                    if (node != null) {
                        val nodeUuid = node.javaClass.getMethod("getUuid").invoke(node) as String
                        // 检查是否是真实节点（非虚拟节点的 UUID 不会是随机生成的）
                        if (!nodeUuid.contains("灯") && nodeUuid.length > 10) {
                            // 可能是虚拟节点，可以使用
                            break
                        }
                        // 真实节点占用，尝试下一个地址
                        Log.w("MeshApp", "地址 0x${address.toString(16)} 已被占用，尝试下一个")
                        address = 0x200 + ((address - 0x200 + 1) % 256)
                        attempts++
                        continue
                    }
                } catch (e: Exception) {
                    Log.w("MeshApp", "检测地址冲突失败: $e")
                }
                break
            }
        }
        
        // 保存地址和设备ID（用于追溯）
        prefs.edit()
            .putInt("provisioner_address", address)
            .putString("device_id", androidId)
            .apply()
        
        Log.d("MeshApp", "生成新地址: 0x${address.toString(16)} (设备ID: ${androidId.take(8)})")
        
        return address
    }
    
    private fun saveProxyAddress(address: String) {
        val prefs = getApplication<Application>().getSharedPreferences("MeshPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("last_proxy_address", address).apply()
        
        // 保存到历史列表
        val history = getProxyAddressHistory().toMutableSet()
        history.add(address)
        prefs.edit().putStringSet("proxy_address_history", history).apply()
    }
    
    fun getProxyAddressHistory(): List<String> {
        val prefs = getApplication<Application>().getSharedPreferences("MeshPrefs", android.content.Context.MODE_PRIVATE)
        return prefs.getStringSet("proxy_address_history", emptySet())?.toList() ?: emptyList()
    }
    
    fun connectToAddress(address: String) {
        MeshState.statusText.postValue("正在连接到 $address...")
        MeshState.bleConnection.connect(address, createConnectionListener(address))
    }
    
    fun autoConnectFromHistory(onAllFailed: () -> Unit) {
        val history = getProxyAddressHistory()
        if (history.isEmpty()) {
            MeshState.statusText.postValue("没有历史连接记录")
            onAllFailed()
            return
        }
        
        MeshState.statusText.postValue("正在尝试自动连接...")
        tryConnectToNextAddress(history, 0, onAllFailed)
    }
    
    private fun tryConnectToNextAddress(addresses: List<String>, index: Int, onAllFailed: () -> Unit) {
        if (index >= addresses.size) {
            MeshState.statusText.postValue("所有历史设备均无法连接")
            onAllFailed()
            return
        }
        
        val address = addresses[index]
        MeshState.statusText.postValue("尝试连接 $address (${index + 1}/${addresses.size})...")
        
        MeshState.bleConnection.connect(address, object : BleConnectionManager.ConnectionListener {
            override fun onConnected() {
                MeshState.statusText.postValue("设备已连接，正在发现服务...")
            }

            override fun onDisconnected() {
                MeshState.isConnected.postValue(false)
                MeshState.connectedDeviceAddress.postValue(null)
            }

            override fun onServicesDiscovered() {
                MeshState.isConnected.postValue(true)
                MeshState.connectedDeviceAddress.postValue(address)
                MeshState.statusText.postValue("已连接到 $address")
                saveProxyAddress(address)
            }

            override fun onDataReceived(data: ByteArray) {
                MeshState.meshManagerApi.handleNotifications(23, data)
            }

            override fun onMeshMessageReceived(src: Int, data: ByteArray) {}

            override fun onRssiRead(rssi: Int) {
                MeshState.currentRssi.postValue(rssi)
            }

            override fun onError(error: String) {
                // 连接失败，尝试下一个地址
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    tryConnectToNextAddress(addresses, index + 1, onAllFailed)
                }, 1000)
            }
        })
    }
    
    private fun getSavedProxyAddress(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("MeshPrefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("last_proxy_address", null)
    }
    
    fun hasSavedProxyAddress(): Boolean {
        return getSavedProxyAddress() != null
    }

    
    fun startUnprovisionedScan() {
        MeshState.bleScanner.startUnprovisionedScan { node: UnprovisionedMeshNode ->
            val current = MeshState.unprovisionedDevices.value ?: emptyList()
            if (current.none { n: UnprovisionedMeshNode -> n.deviceUuid == node.deviceUuid }) {
                MeshState.unprovisionedDevices.postValue(current + node)
            }
        }
    }
    
    fun stopUnprovisionedScan() = MeshState.bleScanner.stopScan()
    
    fun provisionDevice(node: UnprovisionedMeshNode) {
        MeshState.currentUnprovisionedNode = node
        MeshState.isProvisioning.postValue(true)
        MeshState.provisioningStatus.postValue("正在配网...")
        try {
            MeshState.meshManagerApi.identifyNode(node.deviceUuid)
        } catch (e: Exception) {
            MeshState.isProvisioning.postValue(false)
            MeshState.provisioningComplete.postValue(Pair(false, 0))
        }
    }
    
    private fun configureNode(address: Int) {
        val network = MeshState.meshNetWork ?: return
        val appKey = network.appKeys.firstOrNull() ?: return
        val netKey = network.netKeys.firstOrNull() ?: return
        MeshState.meshManagerApi.createMeshPdu(address, no.nordicsemi.android.mesh.transport.ConfigCompositionDataGet())
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            MeshState.meshManagerApi.createMeshPdu(address, no.nordicsemi.android.mesh.transport.ConfigAppKeyAdd(netKey, appKey))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ bindModels(address) }, 1000)
        }, 1000)
    }
    
    private fun bindModels(address: Int) {
        val network = MeshState.meshNetWork ?: return
        val node = network.getNode(address) ?: return
        val appKey = network.appKeys.firstOrNull() ?: return
        node.elements?.forEach { element ->
            element.value.meshModels?.forEach { (modelId, _) ->
                if (listOf(0x1000, 0x1002, 0x1100, 0x1200, 0x1206).contains(modelId)) {
                    try {
                        MeshState.meshManagerApi.createMeshPdu(address, no.nordicsemi.android.mesh.transport.ConfigModelAppBind(element.value.elementAddress, modelId, appKey.keyIndex))
                    } catch (e: Exception) {}
                }
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            MeshState.isProvisioning.postValue(false)
            MeshState.provisioningComplete.postValue(Pair(true, address))
        }, 2000)
    }

    fun getCurrentRssi(): MutableLiveData<Int> = MeshState.currentRssi
}