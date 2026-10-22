package com.example.ble_device_mesh

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import no.nordicsemi.android.mesh.AllocatedUnicastRange
import no.nordicsemi.android.mesh.MeshManagerApi
import no.nordicsemi.android.mesh.MeshManagerCallbacks
import no.nordicsemi.android.mesh.MeshNetwork
import no.nordicsemi.android.mesh.MeshStatusCallbacks
import no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode
import no.nordicsemi.android.mesh.sensorutils.DeviceProperty
import no.nordicsemi.android.mesh.transport.ConfigModelAppBind
import no.nordicsemi.android.mesh.transport.ConfigModelSubscriptionAdd
import no.nordicsemi.android.mesh.transport.ConfigModelSubscriptionDelete
import no.nordicsemi.android.mesh.transport.ConfigNetworkTransmitSet
import no.nordicsemi.android.mesh.transport.ConfigRelaySet
import no.nordicsemi.android.mesh.transport.ControlMessage
import no.nordicsemi.android.mesh.transport.GenericLevelSet
import no.nordicsemi.android.mesh.transport.GenericLevelSetUnacknowledged
import no.nordicsemi.android.mesh.transport.GenericOnOffGet
import no.nordicsemi.android.mesh.transport.GenericOnOffSet
import no.nordicsemi.android.mesh.transport.GenericOnOffSetUnacknowledged
import no.nordicsemi.android.mesh.transport.GenericLevelGet
import no.nordicsemi.android.mesh.transport.MeshMessage
import no.nordicsemi.android.mesh.transport.SensorGet
import no.nordicsemi.android.mesh.transport.SensorStatus
import no.nordicsemi.android.mesh.transport.TimeGet
import no.nordicsemi.android.mesh.transport.TimeSet
import no.nordicsemi.android.mesh.transport.TimeStatus
import no.nordicsemi.android.mesh.transport.VendorModelMessageUnacked
import no.nordicsemi.android.mesh.transport.VendorModelMessageAcked
import no.nordicsemi.android.mesh.transport.VendorModelMessageStatus
import com.example.ble_device_mesh.data.DeviceRepository
import com.example.ble_device_mesh.data.DeviceType
import com.example.ble_device_mesh.data.MeshDevice
import com.example.ble_device_mesh.data.SchedulerTask

/**
 * 一次性事件包装器，防止 LiveData 的粘性事件问题
 */
class Event<out T>(private val content: T) {
    var hasBeenHandled = false
        private set

    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }
}

/** Mesh 网络里已配网的节点（alreadyAdded=true 表示已在本地设备列表中） */
data class RecoverableNode(
    val address: Int,
    val name: String,
    val alreadyAdded: Boolean = false
)

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
        // Generic Level Status 读回：Pair<src, 亮度百分比 0-100>，用于全关后校验
        val levelReadUpdates = MutableLiveData<Pair<Int, Int>>()
        val autoLightStatusUpdates = MutableLiveData<Triple<Int, Int, Int>>() // Triple<src, enabled(0/1), threshold(0-100)>
        val autoLightEnabledUpdates = MutableLiveData<Pair<Int, Int>>() // Pair<src, enabled(0/1)> - derived from GenericOnOffStatus
        val autoLightBrightnessUpdates = MutableLiveData<Pair<Int, Int>>() // Pair<src, brightness(0-100)>
        val buzzerChimeUpdates = MutableLiveData<Pair<Int, Int>>() // Pair<src, enabled(0/1)>
        val buzzerVolumeUpdates = MutableLiveData<Pair<Int, Int>>() // Pair<src, volume(0-100)>

        // 雷达模式状态
        val radarStatusUpdates = MutableLiveData<Pair<Int, Int>>() // Pair<src, enabled(0/1)>
        val radarNightDurationUpdates = MutableLiveData<Pair<Int, Int>>() // Pair<src, duration_x10>
        val radarNightBrightnessUpdates = MutableLiveData<Pair<Int, Int>>() // Pair<src, brightness(1-100)>
        val radarTimesUpdates = MutableLiveData<Pair<Int, List<Pair<Int, Int>>>>() // Pair<src, List<(hour,min)>>
        val radarNightHoursUpdates = MutableLiveData<Pair<Int, Pair<Int, Int>>>() // Pair<src, Pair<startHour, endHour>>
        val radarSoundUpdates = MutableLiveData<Pair<Int, Pair<Int, Int>>>() // Pair<src, Pair<enabled(0/1), volume(0-100)>>

        // 设备密码
        val passwordStatusUpdates = MutableLiveData<Pair<Int, Int>>() // Pair<src, is_set(0/1)>
        val passwordOpResult = MutableLiveData<Event<Triple<Int, Int, Int>>>() // Triple<src, op, rc>

        // 工作模式：Pair<src, mode>  0=关闭, 1=雷达, 2=定时, 3=光敏
        val workModeUpdates = MutableLiveData<Pair<Int, Int>>()
        var pendingPwdOp: Int = MeshViewModel.PWD_OP_NONE
        var pendingPwdAddress: Int = -1
        var pendingPwdTimeout: Runnable? = null

        // 固件版本：Pair<src, "1.0.0+1">
        val firmwareVersionUpdates = MutableLiveData<Pair<Int, String>>()

        // 本机角色：true = 仅控制（导入时剥离了 Device Key，不能清除/配置设备）
        var isControlOnly: Boolean = false

        /** 正在执行「老固件直接 Node Reset」的设备地址，用于区分 0x804A 的含义 */
        var legacyResetAddress: Int = -1

        val timeUpdates = MutableLiveData<Pair<Int, Long>>()
        val schedulerUpdates = MutableLiveData<Pair<Int, Int>>() // Pair<src, schedules bitmap>
        val schedulerActionUpdates = MutableLiveData<Triple<Int, Int, SchedulerAction>>() // Triple<src, index, action>
        val schedulerTaskUpdates = MutableLiveData<SchedulerTask>() // 解析后的任务对象
        val schedulerExecutionNotify = MutableLiveData<SchedulerTask>() // 执行通知
        val scannedDevices = MutableLiveData<List<ScanResult>>(emptyList())
        val isScanning = MutableLiveData<Boolean>(false)
        val connectedDeviceAddress = MutableLiveData<String?>(null)
        val currentProvisionerAddress = MutableLiveData<Int>()
        val currentRssi = MutableLiveData<Int>(-999)

        // 设备在线状态跟踪
        val lastMessageTime = mutableMapOf<Int, Long>()  // 设备地址 → 最后收到消息的时间戳
        val deviceOnlineUpdates = MutableLiveData<Unit>()  // 在线状态变化信号

        // 超时处理
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var schedulerGetTimeoutRunnable: Runnable? = null
        var schedulerSetTimeoutRunnable: Runnable? = null
        var pendingSchedulerSetAddress: Int = -1
        var pendingSchedulerSetIndex: Int = -1
        
        // 配网相关
        val unprovisionedDevices = MutableLiveData<List<UnprovisionedMeshNode>>(emptyList())
        val unprovisionedScanResults = mutableMapOf<java.util.UUID, android.bluetooth.le.ScanResult>()
        val isProvisioning = MutableLiveData<Boolean>(false)
        val provisioningStatus = MutableLiveData<String>("")
        val provisioningComplete = MutableLiveData<Event<Pair<Boolean, Int>>>()
        
        var meshNetWork: MeshNetwork? = null
        // 光敏模式使用标准模型（Generic OnOff + Generic Level）

        var currentTid = 0
        var connectionRetryCount = 0
        val maxRetries = 3
        var currentDevice: ScanResult? = null
        var currentUnprovisionedNode: UnprovisionedMeshNode? = null
        var provisioningDevice: BluetoothDevice? = null
        var provisioningFinished = false

        // 配网后配置状态机
        var configState = 0
        var configTargetAddress = 0
        var configTimeoutRunnable: Runnable? = null

        // 自动模型绑定队列
        var configBindQueue = mutableListOf<Pair<Int, Int>>()
        var configBindIndex = 0
        var configAppKeyIndex = 0

        // MIUI 兼容：记录上次配网 PDU 写入时间，确保相邻 PDU 间隔足够
        var lastProvisioningWriteTime = 0L
        var lastMeshWriteTime = 0L

        // 用户配网配置
        var provisionConfig: ProvisionConfig? = null
    }

    /**
     * 配网前配置：设备名称、单播地址、AppKey、蓝牙 MAC
     */
    data class ProvisionConfig(
        val deviceName: String,
        val unicastAddress: Int,
        val appKeyIndex: Int,  // AppKey 在 network.appKeys 列表中的索引
        val deviceType: DeviceType = DeviceType.LIGHT,
        val bluetoothMac: String? = null  // BLE MAC 地址
    )

    // 暴露给 View 的属性 (代理到 MeshState)
    val statusText get() = MeshState.statusText
    val isConnected get() = MeshState.isConnected
    val isNetworkLoaded get() = MeshState.isNetworkLoaded
    val temperatureUpdates get() = MeshState.temperatureUpdates
    val lightLevelUpdates get() = MeshState.lightLevelUpdates
    val levelReadUpdates get() = MeshState.levelReadUpdates
    val timeUpdates get() = MeshState.timeUpdates
    val schedulerUpdates get() = MeshState.schedulerUpdates
    val schedulerActionUpdates get() = MeshState.schedulerActionUpdates
    val schedulerTaskUpdates get() = MeshState.schedulerTaskUpdates
    val schedulerExecutionNotify get() = MeshState.schedulerExecutionNotify
    val scannedDevices get() = MeshState.scannedDevices
    val isScanning get() = MeshState.isScanning
    val connectedDeviceAddress get() = MeshState.connectedDeviceAddress
    val currentProvisionerAddress get() = MeshState.currentProvisionerAddress
    val deviceOnlineUpdates get() = MeshState.deviceOnlineUpdates
    
    // 配网相关
    val unprovisionedDevices get() = MeshState.unprovisionedDevices
    val isProvisioning get() = MeshState.isProvisioning
    val provisioningStatus get() = MeshState.provisioningStatus
    val provisioningComplete get() = MeshState.provisioningComplete
    
    var meshNetWork: MeshNetwork?
        get() = MeshState.meshNetWork
        private set(value) { MeshState.meshNetWork = value }

    // 配网时根据 UUID 查找对应的 BLE MAC 地址
    fun getMacForUnprovisionedNode(uuid: java.util.UUID?): String? {
        return MeshState.unprovisionedScanResults[uuid]?.device?.address
    }

    // 根据 BLE MAC 查找已保存的设备名称
    fun getDeviceNameForMac(mac: String): String? {
        val repo = DeviceRepository(getApplication())
        // 1. 优先查新字段 bluetoothMac
        repo.getAllDevices().firstOrNull { it.bluetoothMac == mac }?.name?.let { return it }
        // 2. 回退查旧映射 DevicePrefs (device_mac_0x{addr} → mac)
        val prefs = getApplication<Application>().getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        for (device in repo.getAllDevices()) {
            val saved = prefs.getString("device_mac_0x${device.address.toString(16)}", null)
            if (mac == saved) return device.name
        }
        return null
    }

    init {
        initializeGlobalState(application)
    }

    private val onlineCheckRunnable = object : Runnable {
        override fun run() {
            checkDevicesOnlineStatus()
            MeshState.mainHandler.postDelayed(this, ONLINE_CHECK_INTERVAL_MS)
        }
    }

    private fun initializeGlobalState(app: Application) {
        if (MeshState.isInitialized) return

        // 恢复本机角色（管理员 / 仅控制）
        MeshState.isControlOnly = app.getSharedPreferences(ROLE_PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(ROLE_KEY_CONTROL_ONLY, false)

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

        // 启动设备在线状态定时检查
        MeshState.mainHandler.postDelayed(onlineCheckRunnable, ONLINE_CHECK_INTERVAL_MS)
        Log.d("MeshApp", "设备在线状态检查已启动，间隔 ${ONLINE_CHECK_INTERVAL_MS / 1000}s，超时 ${ONLINE_TIMEOUT_MS / 1000}s")
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
                // 如果正在等待 Set 确认，不要覆盖状态文本，等待 bitmap 确认
                if (MeshState.pendingSchedulerSetAddress == dst) {
                    Log.d("MeshApp", "忽略 Scheduler Set 事务失败，等待 bitmap 确认")
                    return
                }
                MeshState.statusText.postValue("设备 0x${dst.toString(16)} 响应超时")
            }

            override fun onUnknownPduReceived(src: Int, accessPayload: ByteArray?) {
                Log.w("MeshApp", "=== 收到未知 PDU ===")
                Log.w("MeshApp", "  源地址: 0x${src.toString(16)}")
                if (accessPayload != null && accessPayload.isNotEmpty()) {
                    Log.w("MeshApp", "  数据长度: ${accessPayload.size} 字节")
                    Log.w("MeshApp", "  数据内容: ${accessPayload.joinToString(" ") { "%02X".format(it) }}")

                    // 尝试解析 OpCode
                    if (accessPayload.size >= 1) {
                        val byte0 = accessPayload[0].toInt() and 0xFF
                        val opCode = when {
                            (byte0 and 0x80) == 0 -> {
                                // 1 字节 OpCode (0xxxxxxx)
                                byte0
                            }
                            (byte0 and 0xC0) == 0x80 && accessPayload.size >= 2 -> {
                                // 2 字节 OpCode (10xxxxxx xxxxxxxx)
                                ((byte0 and 0x3F) shl 8) or (accessPayload[1].toInt() and 0xFF)
                            }
                            (byte0 and 0xC0) == 0xC0 && accessPayload.size >= 3 -> {
                                // 3 字节 OpCode (11xxxxxx xxxxxxxx xxxxxxxx)
                                ((byte0 and 0x3F) shl 16) or ((accessPayload[1].toInt() and 0xFF) shl 8) or (accessPayload[2].toInt() and 0xFF)
                            }
                            else -> -1
                        }
                        if (opCode >= 0) {
                            Log.w("MeshApp", "  推测 OpCode: 0x${opCode.toString(16)}")
                        }
                    }

                    // 厂商模型（CID=0x07D7）的异步上报：库在非“请求-应答”窗口收到 3 字节
                    // 厂商 OpCode 时无法匹配模型，会走到这里；自行按 CID + 6bit 字段分发。
                    if (accessPayload.size >= 4) {
                        val b0 = accessPayload[0].toInt() and 0xFF
                        if ((b0 and 0xC0) == 0xC0) {
                            val cid = (accessPayload[1].toInt() and 0xFF) or
                                    ((accessPayload[2].toInt() and 0xFF) shl 8)
                            val vendorOp = b0 and 0x3F
                            val payload = accessPayload.copyOfRange(3, accessPayload.size)
                            if (cid == DEVINFO_CID) {
                                when (vendorOp) {
                                    OP_DEV_INFO_STATUS -> parseDeviceInfoStatus(src, payload)
                                    OP_PWD_STATUS -> parsePasswordStatus(src, payload)
                                    OP_WORK_MODE_STATUS -> parseWorkModeStatus(src, payload)
                                }
                            }
                        }
                    }
                } else {
                    Log.w("MeshApp", "  数据: 无")
                }
                Log.w("MeshApp", "========================")
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
                // === 详细日志：记录所有收到的消息 ===
                Log.d("MeshApp", "=== 收到 Mesh 消息 ===")
                Log.d("MeshApp", "  源地址: 0x${src.toString(16)}")
                Log.d("MeshApp", "  OpCode: 0x${meshMessage.opCode.toString(16)}")
                Log.d("MeshApp", "  消息类型: ${meshMessage.javaClass.simpleName}")
                Log.d("MeshApp", "  消息类全名: ${meshMessage.javaClass.name}")

                // 获取消息参数
                val params = try {
                    // 在类继承层次中查找 mParameters 字段
                    var clazz: Class<*>? = meshMessage.javaClass
                    var field: java.lang.reflect.Field? = null
                    while (clazz != null && field == null) {
                        try { field = clazz.getDeclaredField("mParameters") } catch (_: NoSuchFieldException) {}
                        clazz = clazz.superclass
                    }
                    field?.apply { isAccessible = true }?.get(meshMessage) as? ByteArray
                } catch (_: Exception) { null }
                if (params != null && params.isNotEmpty()) {
                    Log.d("MeshApp", "  参数长度: ${params.size} 字节")
                    Log.d("MeshApp", "  参数数据: ${params.joinToString(" ") { "%02X".format(it) }}")
                } else {
                    Log.d("MeshApp", "  参数: 无")
                }
                Log.d("MeshApp", "========================")

                // 收到设备消息，标记为在线
                markDeviceOnline(src)

                // 处理 Config 状态消息（配网后配置回调驱动）
                if (MeshState.configState != CONFIG_IDLE && MeshState.configTargetAddress == src) {
                    when (meshMessage.opCode) {
                        0x02 -> {  // ConfigCompositionDataStatus
                            Log.d("MeshApp", "收到 ConfigCompositionDataStatus，继续配置...")
                            MeshState.configTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }
                            proceedAfterComposition(src)
                            return
                        }
                        0x8003 -> {  // ConfigAppKeyStatus
                            Log.d("MeshApp", "收到 ConfigAppKeyStatus，继续配置...")
                            MeshState.configTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }
                            proceedAfterAppKey(src)
                            return
                        }
                        0x803E -> {  // ConfigModelAppStatus
                            Log.d("MeshApp", "收到 ConfigModelAppStatus（已收到绑定回复），继续绑定下一个模型...")
                            MeshState.configTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }
                            MeshState.configBindIndex++
                            sendNextBind(MeshState.configTargetAddress, MeshState.configAppKeyIndex)
                            return
                        }
                    }
                }

                // 处理不同类型的消息
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
                } else if (meshMessage.opCode == 0x804A) {
                    // ConfigNodeResetStatus
                    if (src == MeshState.legacyResetAddress) {
                        // 老固件（无密码模型）：标准 Node Reset 有效
                        Log.d("MeshApp", "老固件已确认 Node Reset (src=0x${src.toString(16)})")
                        MeshState.statusText.postValue("设备已确认清除配网")
                        MeshState.legacyResetAddress = -1
                    } else {
                        // 新固件已忽略标准 Node Reset（需要带密码的 PWD_RESET），
                        // 且不能作为“设备已清除配网”的依据，绝不删除本地设备。
                        Log.w("MeshApp", "收到 Node Reset Status（标准清除已被固件拒绝，忽略）src=0x${src.toString(16)}")
                        MeshState.statusText.postValue("标准清除配网已被设备拒绝，请使用“设备密码”方式")
                    }
                } else if (meshMessage.opCode == 0x824A) {
                    Log.d("MeshApp", "收到 SchedulerStatus (Src: 0x${src.toString(16)}), type=${meshMessage.javaClass.simpleName}")
                    parseSchedulerStatus(src, meshMessage)
                } else if (meshMessage.opCode == 0x5F) {
                    Log.d("MeshApp", "收到 SchedulerActionStatus (Src: 0x${src.toString(16)})")
                    parseSchedulerActionStatus(src, meshMessage)
                } else if (meshMessage.opCode == 0x8204) {
                    // GenericOnOffStatus - 使用公开 API 获取状态
                    val status = meshMessage as? no.nordicsemi.android.mesh.transport.GenericOnOffStatus
                    val enabled = if (status?.presentState == true) 1 else 0
                    Log.d("MeshApp", "GenericOnOffStatus(0x8204): enabled=$enabled (src=0x${src.toString(16)})")
                    MeshState.autoLightEnabledUpdates.postValue(Pair(src, enabled))
                    // 同步更新蜂鸣器报时状态（由 src 地址区分）
                    MeshState.buzzerChimeUpdates.postValue(Pair(src, enabled))
                    // 同步更新雷达启用状态（Element 4）
                    MeshState.radarStatusUpdates.postValue(Pair(src, enabled))
                    // THRESHOLD 由 0x8208 单独更新，此处不覆盖（否则 User 拖 SeekBar 中收到回复会回弹）
                } else if (meshMessage.opCode == 0x8208) {
                    // GenericLevelStatus - 使用公开 API 获取 level
                    val levelStatus = meshMessage as? no.nordicsemi.android.mesh.transport.GenericLevelStatus
                    val level = levelStatus?.presentLevel?.coerceIn(0, 100) ?: 50
                    Log.d("MeshApp", "GenericLevelStatus(0x8208): level=$level (src=0x${src.toString(16)})")
                    // 原始 level 是 int16（-32768..32767），换算成 0-100% 供全关校验使用
                    levelStatus?.let {
                        MeshState.levelReadUpdates.postValue(Pair(src, meshLevelToPercent(it.presentLevel)))
                    }
                    // 更新阈值（可能是 Element 1 回复）
                    val lastKnown = MeshState.autoLightStatusUpdates.value
                    val en = lastKnown?.second ?: 0
                    MeshState.autoLightStatusUpdates.postValue(Triple(src, en, level))
                    // 也更新亮度（可能是 Element 2 回复）
                    MeshState.autoLightBrightnessUpdates.postValue(Pair(src, level))
                    // 同步更新蜂鸣器音量（可能是 Element 3 回复）
                    MeshState.buzzerVolumeUpdates.postValue(Pair(src, level))
                    // 同步更新雷达夜晚时长（可能是 Element 4 回复）
                    MeshState.radarNightDurationUpdates.postValue(Pair(src, level))
                    // 同步更新雷达夜晚亮灯亮度（可能是 Element 5 回复）
                    MeshState.radarNightBrightnessUpdates.postValue(Pair(src, level))
                } else if (meshMessage is VendorModelMessageStatus) {
                    val data = meshMessage.parameters
                    // 厂商模型标识：兼容 16 位 ModelID 与 (CID << 16) | ModelID 两种形式
                    val vendorModelId = meshMessage.modelIdentifier and 0xFFFF
                    Log.d("MeshApp", "收到 VendorModelMessageStatus (Src: 0x${src.toString(16)}): model=0x${vendorModelId.toString(16)}, opCode=0x${meshMessage.opCode.toString(16)}, data=${data?.joinToString("") { "%02X".format(it) }}")
                    if (vendorModelId == DEVINFO_MODEL_ID && meshMessage.opCode == OP_DEV_INFO_STATUS) {
                        // 设备信息： [type(2B LE), major, minor, patch, build]
                        parseDeviceInfoStatus(src, data)
                    } else when (meshMessage.opCode) {
                        OP_RADAR_TIMES_STATUS -> {
                            // 解析检测时间列表: [count(1B), hour1(1B), min1(1B), hour2(1B), min2(1B), ...]
                            if (data != null && data.isNotEmpty()) {
                                val count = data[0].toInt() and 0xFF
                                val times = mutableListOf<Pair<Int, Int>>()
                                for (i in 0 until count) {
                                    val idx = 1 + i * 2
                                    if (idx + 1 < data.size) {
                                        val hour = data[idx].toInt() and 0xFF
                                        val minute = data[idx + 1].toInt() and 0xFF
                                        times.add(Pair(hour, minute))
                                    }
                                }
                                Log.d("MeshApp", "雷达检测时间列表(count=$count): ${times.joinToString { "%02d:%02d".format(it.first, it.second) }}")
                                MeshState.radarTimesUpdates.postValue(Pair(src, times.toList()))
                            }
                        }
                        OP_RADAR_NIGHT_HOURS_GET -> {
                            // 解析夜晚时段: [startHour(1B), endHour(1B)]
                            if (data != null && data.size >= 2) {
                                val startHour = data[0].toInt() and 0xFF
                                val endHour = data[1].toInt() and 0xFF
                                Log.d("MeshApp", "雷达夜晚时段: $startHour:00 - $endHour:00")
                                MeshState.radarNightHoursUpdates.postValue(Pair(src, Pair(startHour, endHour)))
                            }
                        }
                        OP_RADAR_SOUND_GET -> {
                            // 解析提示音配置: [enabled(1B), volume(1B)]
                            if (data != null && data.size >= 2) {
                                val enabled = data[0].toInt() and 0xFF
                                val volume = data[1].toInt() and 0xFF
                                Log.d("MeshApp", "雷达提示音: enabled=$enabled, volume=$volume")
                                MeshState.radarSoundUpdates.postValue(Pair(src, Pair(enabled, volume)))
                            }
                        }
                        OP_PWD_STATUS -> {
                            // [rc(1B), is_set(1B)]
                            parsePasswordStatus(src, data)
                        }
                        OP_WORK_MODE_STATUS -> {
                            // [mode(1B)]
                            parseWorkModeStatus(src, data)
                        }
                        else -> {
                            Log.w("MeshApp", "未处理的 Vendor OpCode: 0x${meshMessage.opCode.toString(16)}")
                        }
                    }
                } else {
                    Log.w("MeshApp", "收到未处理的消息 OpCode: 0x${meshMessage.opCode.toString(16)} (Src: 0x${src.toString(16)})")
                }
            }
        })



        meshManagerApi.setMeshManagerCallbacks(object : MeshManagerCallbacks{
            override fun onNetworkLoaded(network: MeshNetwork?) {
                Log.d("MeshApp", "onNetworkLoaded 被调用, network = $network")
                MeshState.meshNetWork = network
                if (network != null) normalizeEmptyDeviceKeys(network)

                if (network == null) {
                    statusText.postValue("没有发现网络，请导入 nRF Mesh 配置")
                } else {
                    // 始终使用本机唯一 Provisioner 地址，确保不同手机有不同源地址
                    // 导入后 DB 中存储的是导出手机的原始地址，必须用 SharedPreferences 中的本机地址覆盖
                    val context = getApplication<Application>()
                    val addr = getOrGenerateDeviceAddress(context)
                    try {
                        setProvisionerAddressInternal(network, addr)
                        MeshState.currentProvisionerAddress.postValue(addr)
                        ProvisionerSeq.ensureForward(context, network)
                    } catch (e: Exception) {
                        Log.w("MeshApp", "设置 Provisioner 地址失败，使用原始地址: $e")
                        val fallbackAddr = network.selectedProvisioner?.provisionerAddress ?: 0
                        MeshState.currentProvisionerAddress.postValue(fallbackAddr)
                    }
                    Log.d("MeshApp", "Provisioner 地址: 0x${addr.toString(16)}")

                    // 调试：检查 NetKey 和 AppKey
                    Log.d("MeshApp", "=== 网络 Key 信息 ===")
                    Log.d("MeshApp", "NetKeys 数量: ${network.netKeys.size}")
                    network.netKeys.forEach { netKey ->
                        Log.d("MeshApp", "  NetKey: index=${netKey.keyIndex}, name=${netKey.name}")
                    }
                    Log.d("MeshApp", "AppKeys 数量: ${network.appKeys.size}")
                    network.appKeys.forEach { appKey ->
                        Log.d("MeshApp", "  AppKey: index=${appKey.keyIndex}, name=${appKey.name}, boundNetKeyIndex=${appKey.boundNetKeyIndex}")
                    }
                    Log.d("MeshApp", "========================")

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
                if (meshNetwork != null) normalizeEmptyDeviceKeys(meshNetwork)

                if (meshNetwork != null) {
                    // 导入后自动分配本机唯一的 Provisioner 地址
                    // 不同手机基于 Android ID 生成不同地址，且在 0x200-0x23F 范围内检测冲突
                    val provisioner = meshNetwork.selectedProvisioner
                    if (provisioner != null) {
                        val context = getApplication<Application>()
                        val newAddr = getOrGenerateDeviceAddress(context)
                        try {
                            setProvisionerAddressInternal(meshNetwork, newAddr)
                            MeshState.currentProvisionerAddress.postValue(newAddr)
                            ProvisionerSeq.ensureForward(context, meshNetwork)
                            statusText.postValue("网络已导入 (本机地址: 0x${newAddr.toString(16)})")
                            Log.d("MeshApp", "导入后分配本机 Provisioner 地址: 0x${newAddr.toString(16)}")
                        } catch (e: Exception) {
                            Log.w("MeshApp", "分配本机地址失败，使用导入的原始地址: $e")
                            val originalAddr = provisioner.provisionerAddress ?: 0
                            MeshState.currentProvisionerAddress.postValue(originalAddr)
                            statusText.postValue("网络已导入 (使用原始地址: 0x${originalAddr.toString(16)})")
                        }
                    } else {
                        statusText.postValue("网络已导入（无 Provisioner）")
                    }
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
                Log.d("MeshApp", "=== 发送配网 PDU ===")
                Log.d("MeshApp", "  设备 UUID: ${meshNode?.deviceUuid}")
                Log.d("MeshApp", "  PDU 长度: ${pdu?.size}")

                if (pdu == null) {
                    Log.e("MeshApp", "  配网 PDU 为空！")
                    return
                }

                if (!bleConnection.isConnected()) {
                    Log.e("MeshApp", "未连接到设备，无法发送配网 PDU")
                    MeshState.provisioningStatus.postValue("配网失败：未连接到设备")
                    MeshState.isProvisioning.postValue(false)
                    MeshState.provisioningComplete.postValue(Event(Pair(false, 0)))
                    return
                }

                // 动态计算延迟：确保相邻 PDU 实际写入间隔至少 300ms
                // MIUI 蓝牙协议栈在连续 WRITE_TYPE_DEFAULT 时会因 mDeviceBusy 卡死
                val now = System.currentTimeMillis()
                val elapsed = now - MeshState.lastProvisioningWriteTime
                val delay = if (MeshState.lastProvisioningWriteTime == 0L) {
                    200L  // 首 PDU：服务发现后 200ms 写入
                } else {
                    maxOf(300 - elapsed, 0L)  // 后续 PDU：距离上次写入至少 300ms
                }

                Log.d("MeshApp", "  PDU 发送延迟: ${delay}ms (距上次写入: ${elapsed}ms)")
                val pduCopy = pdu.clone()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    writeProvisioningPdu(pduCopy)
                }, delay)
            }

            /**
             * 实际执行配网 PDU 的 BLE 写入和状态机推进
             * MIUI 兼容：sendData 返回 false 时数据仍可能已发送，
             * 必须调用 handleWriteCallbacks 推进状态机
             */
            private fun writeProvisioningPdu(pdu: ByteArray) {
                MeshState.lastProvisioningWriteTime = System.currentTimeMillis()
                val success = bleConnection.sendData(pdu, forceReliable = false)
                if (success) {
                    Log.d("MeshApp", "配网 PDU 已发送 (长度=${pdu.size})")
                } else {
                    Log.w("MeshApp", "配网 PDU sendData 返回 false，但仍可能已发送 (长度=${pdu.size})")
                }
                // 无论 sendData 是否成功，都调用 handleWriteCallbacks 推进状态机
                // MIUI 上 sendData 可能返回 false 但实际已写入，不推进状态机会导致配网卡死
                val mtu = MeshState.bleConnection.mtuSize
                Log.d("MeshApp", "调用 handleWriteCallbacks, mtu=$mtu, 数据: ${pdu.joinToString(" ") { "%02X".format(it) }}")
                MeshState.meshManagerApi.handleWriteCallbacks(mtu, pdu)
                Log.d("MeshApp", "handleWriteCallbacks 返回")
            }

            override fun onMeshPduCreated(pdu: ByteArray?) {
                if (pdu == null) {
                    Log.e("MeshApp", "PDU 为空！")
                    return
                }

                // 解析 SAR 类型和 PDU 类型
                val sarType = (pdu[0].toInt() shr 6) and 0x03
                val pduType = pdu[0].toInt() and 0x3F
                val sarName = when (sarType) {
                    0 -> "完整"
                    1 -> "首段"
                    2 -> "续段"
                    3 -> "末段"
                    else -> "未知"
                }
                val pduTypeName = when (pduType) {
                    0 -> "Network"
                    1 -> "Beacon"
                    2 -> "ProxyConfig"
                    3 -> "Provisioning"
                    else -> "未知($pduType)"
                }

                Log.d("MeshApp", "=== Mesh PDU 已创建 ===")
                Log.d("MeshApp", "  PDU 长度: ${pdu.size}, SAR=$sarName, 类型=$pduTypeName")
                Log.d("MeshApp", "  完整数据: ${pdu.joinToString(" ") { "%02X".format(it) }}")
                Log.d("MeshApp", "========================")

                if (!bleConnection.isConnected()) {
                    Log.w("MeshApp", "未连接到设备，无法发送 PDU")
                    statusText.postValue("未连接到设备！请先扫描并连接 Proxy 节点")
                    return
                }

                val pduCopy = pdu.clone()
                val now = System.currentTimeMillis()
                val elapsed = now - MeshState.lastMeshWriteTime
                val delay = if (MeshState.lastMeshWriteTime == 0L) 0L else maxOf(60 - elapsed, 0L)
                MeshState.lastMeshWriteTime = now + delay
                MeshState.mainHandler.postDelayed({
                    val success = bleConnection.sendData(pduCopy, forceReliable = false)
                    if (success) {
                        Log.d("MeshApp", "PDU 已发送到设备")
                    } else {
                        Log.e("MeshApp", "PDU 发送失败")
                        statusText.postValue("PDU 发送失败")
                    }
                    val mtu = MeshState.bleConnection.mtuSize
                    MeshState.meshManagerApi.handleWriteCallbacks(mtu, pduCopy)
                }, delay)
            }

            override fun getMtu(): Int {
                return bleConnection.mtuSize
            }
        })

        // 设置配网状态回调
        meshManagerApi.setProvisioningStatusCallbacks(object : no.nordicsemi.android.mesh.MeshProvisioningStatusCallbacks {
            override fun onProvisioningStateChanged(
                meshNode: UnprovisionedMeshNode?,
                state: no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States?,
                data: ByteArray?
            ) {
                Log.d("MeshApp", "=== 配网状态变化 ===")
                Log.d("MeshApp", "  设备 UUID: ${meshNode?.deviceUuid}")
                Log.d("MeshApp", "  状态: $state")

                val statusText = when (state) {
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_INVITE -> "发送配网邀请..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_CAPABILITIES -> {
                        // 收到设备能力后，自动开始配网（使用 No OOB）
                        Log.d("MeshApp", "收到设备能力，开始配网流程...")
                        Log.d("MeshApp", "当前线程: ${Thread.currentThread().name}")

                        if (meshNode == null) {
                            Log.e("MeshApp", "❌ meshNode 为 null，无法开始配网")
                            "获取设备能力..."
                        } else {
                            try {
                                Log.d("MeshApp", "设备能力信息:")
                                Log.d("MeshApp", "  - 元素数量: ${meshNode.provisioningCapabilities?.numberOfElements}")
                                Log.d("MeshApp", "  - 算法: ${meshNode.provisioningCapabilities?.rawAlgorithm}")
                                Log.d("MeshApp", "  - 公钥类型: ${meshNode.provisioningCapabilities?.rawPublicKeyType}")
                                Log.d("MeshApp", "  - 静态 OOB 类型: ${meshNode.provisioningCapabilities?.rawStaticOOBType}")
                                Log.d("MeshApp", "  - 输出 OOB 大小: ${meshNode.provisioningCapabilities?.outputOOBSize}")
                                Log.d("MeshApp", "  - 输入 OOB 大小: ${meshNode.provisioningCapabilities?.inputOOBSize}")

                                // 检查网络状态
                                val network = MeshState.meshNetWork
                                Log.d("MeshApp", "网络状态:")
                                Log.d("MeshApp", "  - 网络: ${network?.meshName}")
                                Log.d("MeshApp", "  - Provisioner: ${network?.selectedProvisioner?.provisionerName}")
                                Log.d("MeshApp", "  - 下一个可用地址: ${network?.nextAvailableUnicastAddress(meshNode.numberOfElements, network.selectedProvisioner)}")

                                // 应用用户配置的地址
                                val config = MeshState.provisionConfig
                                if (config != null && config.unicastAddress > 0) {
                                    Log.d("MeshApp", "设置自定义地址: 0x${config.unicastAddress.toString(16)}")

                                    // 1. 直接给 meshNode 设地址（最可靠，ProvisioningDataState 直接从 node 读）
                                    meshNode.setUnicastAddress(config.unicastAddress)
                                    Log.d("MeshApp", "✅ meshNode 地址已设: 0x${config.unicastAddress.toString(16)}")

                                    // 2. 同步更新网络的地址计数器
                                    try {
                                        network?.assignUnicastAddress(config.unicastAddress)
                                        Log.d("MeshApp", "✅ 网络地址也已更新")
                                    } catch (e: Exception) {
                                        Log.w("MeshApp", "网络地址更新失败: ${e.message}")
                                    }
                                }

                                Log.d("MeshApp", "调用 startProvisioning...")
                                MeshState.meshManagerApi.startProvisioning(meshNode)
                                Log.d("MeshApp", "✅ startProvisioning 调用成功")
                            } catch (e: IllegalArgumentException) {
                                Log.e("MeshApp", "❌ startProvisioning 参数错误: ${e.message}", e)
                                e.printStackTrace()
                                MeshState.provisioningStatus.postValue("配网失败：参数错误 - ${e.message}")
                            } catch (e: Exception) {
                                Log.e("MeshApp", "❌ startProvisioning 失败: ${e.message}", e)
                                e.printStackTrace()
                                MeshState.provisioningStatus.postValue("配网失败：${e.message}")
                            }
                            "获取设备能力..."
                        }
                    }
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_START -> "开始配网..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_PUBLIC_KEY_SENT -> "发送公钥..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_PUBLIC_KEY_RECEIVED -> "接收公钥..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_AUTHENTICATION_INPUT_OOB_WAITING -> "等待认证输入..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_AUTHENTICATION_OUTPUT_OOB_WAITING -> "等待认证输出..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_AUTHENTICATION_STATIC_OOB_WAITING -> "等待静态认证..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_AUTHENTICATION_INPUT_ENTERED -> "认证输入完成..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_INPUT_COMPLETE -> "输入完成..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_CONFIRMATION_SENT -> "发送确认..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_CONFIRMATION_RECEIVED -> "接收确认..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_RANDOM_SENT -> "发送随机数..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_RANDOM_RECEIVED -> "接收随机数..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_DATA_SENT -> "发送配网数据..."
                    no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States.PROVISIONING_COMPLETE -> "配网完成"
                    else -> "配网中: $state"
                }

                MeshState.provisioningStatus.postValue(statusText)
            }

            override fun onProvisioningFailed(
                meshNode: UnprovisionedMeshNode?,
                state: no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States?,
                data: ByteArray?
            ) {
                Log.e("MeshApp", "=== 配网失败 ===")
                Log.e("MeshApp", "  设备 UUID: ${meshNode?.deviceUuid}")
                Log.e("MeshApp", "  失败状态: $state")

                MeshState.provisioningStatus.postValue("配网失败: $state")
                MeshState.isProvisioning.postValue(false)
                MeshState.provisioningComplete.postValue(Event(Pair(false, 0)))
            }

            override fun onProvisioningCompleted(
                meshNode: no.nordicsemi.android.mesh.transport.ProvisionedMeshNode?,
                state: no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States?,
                data: ByteArray?
            ) {
                val configAddr = MeshState.provisionConfig?.unicastAddress
                Log.d("MeshApp", "=== 配网完成 ===")
                Log.d("MeshApp", "  设备地址: 0x${meshNode?.unicastAddress?.toString(16)} (用户配置: 0x${configAddr?.toString(16) ?: "无"})")
                Log.d("MeshApp", "  设备 UUID: ${meshNode?.uuid}")

                val address = meshNode?.unicastAddress ?: 0
                MeshState.provisioningFinished = true
                MeshState.isProvisioning.postValue(false)

                val device = MeshState.provisioningDevice
                if (device == null) {
                    Log.e("MeshApp", "provisioningDevice 为空，无法配置")
                    MeshState.provisioningStatus.postValue("配网失败：无法配置节点")
                    MeshState.provisioningComplete.postValue(Event(Pair(false, 0)))
                    return
                }

                // == 策略：优先在同一条连接上重新发现服务（无需断开重连）==
                MeshState.provisioningStatus.postValue("配网完成，正在刷新服务列表...")
                Log.d("MeshApp", "尝试在同一条连接上重新发现服务（使用 Proxy Service）...")

                val configListener = createConfigurationListener(device, address)
                MeshState.bleConnection.setListener(configListener)

                val rediscovered = MeshState.bleConnection.rediscoverServices()
                if (rediscovered) {
                    // 设置超时保护：如果 5 秒内 onServicesDiscovered 没触发，回退到断开重连
                    MeshState.configTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }
                    MeshState.configTimeoutRunnable = Runnable {
                        Log.w("MeshApp", "服务重发现超时（5s 未回调），回退到断开重连")
                        MeshState.provisioningStatus.postValue("刷新超时，尝试断开重连...")
                        fallbackToReconnectForConfiguration(device, address, 0)
                    }
                    MeshState.mainHandler.postDelayed(MeshState.configTimeoutRunnable!!, 5000)
                } else {
                    Log.w("MeshApp", "服务重发现发起失败，回退到断开重连")
                    MeshState.provisioningStatus.postValue("正在断开并重新连接...")
                    fallbackToReconnectForConfiguration(device, address, 0)
                }
            }
        })
    }

    /**
     * 创建配置阶段专用的 ConnectionListener
     * 复用此 listener 用于服务重发现和断开重连两种路径
     */
    private fun createConfigurationListener(device: BluetoothDevice, address: Int): BleConnectionManager.ConnectionListener {
        return object : BleConnectionManager.ConnectionListener {
            private var rediscoveryHandled = false  // 防止多次触发回退

            override fun onConnected() {
                Log.d("MeshApp", "配置连接已建立")
                MeshState.provisioningStatus.postValue("已连接，正在发现 Proxy 服务...")
            }

            override fun onDisconnected() {
                Log.w("MeshApp", "配置连接断开")
                if (!rediscoveryHandled) {
                    rediscoveryHandled = true
                    MeshState.configTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }
                    MeshState.provisioningStatus.postValue("配置失败：连接断开")
                }
            }

            override fun onServicesDiscovered() {
                // 取消重发现超时
                MeshState.configTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }

                Log.d("MeshApp", "服务发现完成，检查服务类型...")
                Log.d("MeshApp", "  当前 DataIn UUID: ${MeshState.bleConnection.getCurrentCharacteristicUuid()}")

                if (!MeshState.bleConnection.isUsingProxyService()) {
                    Log.w("MeshApp", "未找到 Proxy Service (0x1828)，断开重连")
                    if (!rediscoveryHandled) {
                        rediscoveryHandled = true
                        MeshState.provisioningStatus.postValue("未找到 Proxy 服务，尝试断开重连...")
                        MeshState.bleConnection.disconnect()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            fallbackToReconnectForConfiguration(device, address, 0)
                        }, 3000)
                    }
                    return
                }

                if (rediscoveryHandled) return
                rediscoveryHandled = true

                Log.d("MeshApp", "已使用 Proxy Service，读取设备信息（跳过自动绑定）")
                MeshState.configState = CONFIG_IDLE
                MeshState.isConnected.postValue(true)
                MeshState.connectedDeviceAddress.postValue(device.address)
                MeshState.provisioningStatus.postValue("正在读取设备信息...")
                configureNode(address)
            }

            override fun onDataReceived(data: ByteArray) {
                val mtu = MeshState.bleConnection.mtuSize
                MeshState.meshManagerApi.handleNotifications(mtu, data)
            }

            override fun onDataSent(data: ByteArray) {
                val mtu = MeshState.bleConnection.mtuSize
                MeshState.meshManagerApi.handleWriteCallbacks(mtu, data)
            }

            override fun onMeshMessageReceived(src: Int, data: ByteArray) {}

            override fun onRssiRead(rssi: Int) {}

            override fun onError(error: String) {
                Log.e("MeshApp", "配置连接错误: $error")
                fallbackToReconnectForConfiguration(device, address, 0)
            }
        }
    }

    /**
     * 后备方案：断开后重新连接以获取 Proxy Service
     * 仅在服务重发现找不到 Proxy Service 时使用
     */
    private fun fallbackToReconnectForConfiguration(device: BluetoothDevice, address: Int, retryCount: Int) {
        val maxRetries = 2
        if (retryCount >= maxRetries) {
            Log.e("MeshApp", "重连 ${maxRetries} 次仍无法获取 Proxy Service，保存设备但不配置")
            MeshState.provisioningStatus.postValue("设备已保存，但未完成配置")
            // 直接保存设备，让用户稍后手动连接配置
            saveDeviceToLocal(address)
            MeshState.isProvisioning.postValue(false)
            MeshState.provisioningComplete.postValue(Event(Pair(true, address)))
            return
        }

        Log.d("MeshApp", "===== 断开重连 (第 ${retryCount + 1}/$maxRetries 次) =====")
        MeshState.provisioningStatus.postValue("正在重新连接 (${retryCount + 1}/$maxRetries)...")

        MeshState.bleConnection.connect(device, object : BleConnectionManager.ConnectionListener {
            override fun onConnected() {
                Log.d("MeshApp", "重连成功，正在发现服务...")
                MeshState.provisioningStatus.postValue("已连接，正在发现 Proxy 服务...")
            }

            override fun onDisconnected() {
                Log.w("MeshApp", "重连断开")
            }

            override fun onServicesDiscovered() {
                Log.d("MeshApp", "重连后服务发现完成，检查 Proxy Service...")
                Log.d("MeshApp", "  当前 DataIn UUID: ${MeshState.bleConnection.getCurrentCharacteristicUuid()}")

                if (!MeshState.bleConnection.isUsingProxyService()) {
                    Log.w("MeshApp", "重连后仍未找到 Proxy Service，重试...")
                    MeshState.bleConnection.disconnect()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        fallbackToReconnectForConfiguration(device, address, retryCount + 1)
                    }, 3000)
                    return
                }

                Log.d("MeshApp", "重连成功，已使用 Proxy Service")
                MeshState.configState = CONFIG_IDLE
                MeshState.isConnected.postValue(true)
                MeshState.connectedDeviceAddress.postValue(device.address)
                MeshState.provisioningStatus.postValue("正在读取设备信息...")
                configureNode(address)
            }

            override fun onDataReceived(data: ByteArray) {
                MeshState.meshManagerApi.handleNotifications(MeshState.bleConnection.mtuSize, data)
            }

            override fun onDataSent(data: ByteArray) {
                MeshState.meshManagerApi.handleWriteCallbacks(MeshState.bleConnection.mtuSize, data)
            }

            override fun onMeshMessageReceived(src: Int, data: ByteArray) {}

            override fun onRssiRead(rssi: Int) {}

            override fun onError(error: String) {
                Log.e("MeshApp", "重连错误: $error")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    fallbackToReconnectForConfiguration(device, address, retryCount + 1)
                }, 3000)
            }
        })
    }

    /**
     * 保存设备到本地数据库（从 onConfigurationComplete 中提取）
     */
    private fun saveDeviceToLocal(address: Int) {
        try {
            val configName = MeshState.provisionConfig?.deviceName
            val configMac = MeshState.provisionConfig?.bluetoothMac
            val network = MeshState.meshNetWork
            val node = network?.getNode(address)
            val nodeName = configName ?: node?.nodeName ?: "Mesh Device"
            val deviceType = MeshState.provisionConfig?.deviceType ?: DeviceType.LIGHT
            val device = MeshDevice(
                id = "mesh_$address",
                name = nodeName,
                address = address,
                type = deviceType,
                bluetoothMac = configMac
            )
            DeviceRepository(getApplication()).addDevice(device)
            Log.d("MeshApp", "设备已保存到本地: $nodeName (0x${address.toString(16)}) mac=$configMac")
        } catch (e: Exception) {
            Log.e("MeshApp", "保存设备失败: ${e.message}")
        }
    }

    // 解析 Scheduler Status
    private fun parseSchedulerStatus(src: Int, message: MeshMessage) {
        try {
            Log.d("MeshApp", "=== 解析 SchedulerStatus ===")
            Log.d("MeshApp", "  源地址: 0x${src.toString(16)}")
            Log.d("MeshApp", "  消息类型: ${message.javaClass.simpleName}")
            Log.d("MeshApp", "  OpCode: 0x${message.opCode.toString(16)}")

            // 取消超时
            MeshState.schedulerGetTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }

            val schedules = SchedulerMessageHelper.parseSchedulerStatus(message)
            if (schedules == null) {
                Log.w("MeshApp", "无法解析 SchedulerStatus 消息: type=${message.javaClass.simpleName}")
                // 即使解析失败，也要更新状态防止孤儿超时误报
                val pendingAddr = MeshState.pendingSchedulerSetAddress
                if (pendingAddr == src) {
                    MeshState.statusText.postValue("收到设备响应但无法解析调度状态")
                    MeshState.pendingSchedulerSetAddress = -1
                    MeshState.pendingSchedulerSetIndex = -1
                } else {
                    MeshState.statusText.postValue("已收到设备 0x${src.toString(16)} 的调度状态（解析失败）")
                }
                return
            }

            Log.d("MeshApp", "解析到 Scheduler bitmap: 0x${schedules.toString(16)} (Src: 0x${src.toString(16)})")
            MeshState.schedulerUpdates.postValue(Pair(src, schedules))

            // 如果是 Set 确认，更新状态
            val pendingAddr = MeshState.pendingSchedulerSetAddress
            if (pendingAddr == src) {
                val pendingIdx = MeshState.pendingSchedulerSetIndex
                val isSet = (schedules and (1 shl pendingIdx)) != 0
                if (isSet) {
                    MeshState.statusText.postValue("调度任务 #$pendingIdx 已设置")
                    Log.d("MeshApp", "Set 确认成功: index=$pendingIdx, bitmap=0x${schedules.toString(16)}")
                } else {
                    MeshState.statusText.postValue("调度任务 #$pendingIdx 已清除")
                    Log.d("MeshApp", "Set 确认成功（清除）: index=$pendingIdx, bitmap=0x${schedules.toString(16)}")
                }
                MeshState.pendingSchedulerSetAddress = -1
                MeshState.pendingSchedulerSetIndex = -1
            } else {
                val setIndexes = (0..15).filter { (schedules and (1 shl it)) != 0 }
                if (setIndexes.isEmpty()) {
                    MeshState.statusText.postValue("设备 0x${src.toString(16)} 暂无定时任务")
                } else {
                    MeshState.statusText.postValue("已收到设备 0x${src.toString(16)} 的调度状态，正在读取 ${setIndexes.size} 个任务详情")
                    setIndexes.forEachIndexed { readIndex, schedulerIndex ->
                        MeshState.mainHandler.postDelayed({
                            readSchedulerAction(src, schedulerIndex)
                        }, readIndex * 250L)
                    }
                }
            }

            Log.d("MeshApp", "========================")
        } catch (e: Exception) {
            Log.e("MeshApp", "解析 SchedulerStatus 失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // 解析 Scheduler Action Status
    private fun parseSchedulerActionStatus(src: Int, message: MeshMessage) {
        try {
            Log.d("MeshApp", "=== 解析 SchedulerActionStatus ===")
            Log.d("MeshApp", "  源地址: 0x${src.toString(16)}")
            Log.d("MeshApp", "  消息类型: ${message.javaClass.simpleName}")
            Log.d("MeshApp", "  OpCode: 0x${message.opCode.toString(16)}")

            // 一次解析，同时更新旧版和新版数据
            val task = SchedulerMessageHelper.parseSchedulerActionStatus(message, src)

            if (task != null) {
                Log.d("MeshApp", "解析到 SchedulerTask (Src: 0x${src.toString(16)}):")
                Log.d("MeshApp", "  - Index: ${task.index}, Time: ${task.getTimeString()}")
                Log.d("MeshApp", "  - Action: ${task.action}, Brightness: ${task.brightness}%")
                Log.d("MeshApp", "  - Repeat: 0x${task.repeat.toString(16)}, Enabled: ${task.enabled}")

                // 更新新版 SchedulerTask
                MeshState.schedulerTaskUpdates.postValue(task)

                // 发送执行通知
                MeshState.schedulerExecutionNotify.postValue(task)

                // 兼容旧版：构造 SchedulerAction 对象
                val compatAction = SchedulerAction(
                    index = task.index,
                    year = task.year,
                    month = task.month,
                    day = task.day,
                    hour = task.hour,
                    minute = task.minute,
                    second = task.second,
                    dayOfWeek = task.repeat,
                    action = task.action.value,
                    transitionTime = task.brightness,
                    sceneNumber = 0
                )
                MeshState.schedulerActionUpdates.postValue(Triple(src, task.index, compatAction))
            } else {
                Log.w("MeshApp", "无法解析 SchedulerActionStatus")
            }

            MeshState.statusText.postValue("已收到设备 0x${src.toString(16)} 的调度动作 #${task?.index}")
            Log.d("MeshApp", "========================")
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

            // TAI 转 Unix 时间戳
            // TAI 纪元:   2000-01-01 00:00:00 TAI
            // Unix 纪元:  1970-01-01 00:00:00 UTC
            // 差值 = 946684800 秒 - 37 秒 (TAI-UTC偏移)
            val unixTime = taiSeconds.toLong() + 946684800L - 37L

            Log.d("MeshApp", "解析到设备时间: TAI=$taiSeconds, Unix=$unixTime (Src: 0x${src.toString(16)})")
            MeshState.timeUpdates.postValue(Pair(src, unixTime))

            // 更新状态，表示成功收到回复
            MeshState.statusText.postValue("已收到设备 0x${src.toString(16)} 的时间")
        } catch (e: Exception) {
            Log.e("MeshApp", "解析 TimeStatus 失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    // 本 App 能识别的传感器属性 ID（用于区分标准 MPID 与老固件 MPID 编码）
    private fun isKnownSensorProperty(id: Int): Boolean {
        return id == 0x004E || id == 0x004F || id == 0x0056 || id == 0x0071
    }

    // 解析传感器数据
    private fun parseSensorStatus(src: Int, data: ByteArray) {
        var offset = 0
        Log.d("MeshApp", "Parsing Sensor Status Data: ${data.joinToString(" ") { "%02X".format(it) }}")
        
        while (offset < data.size) {
            try {
                val byte0 = data[offset].toInt() and 0xFF

                // 标准格式解析 (Mesh Spec)
                // bit0 = format: 0=Format A, 1=Format B
                val format = byte0 and 1
                var length = 0
                var propertyId = 0
                var valueOffset = 0

                if (format == 0) {
                    // Format A: 2字节头
                    // octet0: bit0=0, bits1-4=length-1, bits5-7=Property ID bits 2..0
                    // octet1: Property ID bits 10..3
                    if (offset + 1 >= data.size) break
                    length = ((byte0 and 0x1E) shr 1) + 1
                    val octet1 = data[offset + 1].toInt() and 0xFF
                    val standardId = (octet1 shl 3) or (byte0 shr 5)
                    // 老固件(<= v1)曾把属性 ID 的高 3 位放在 bit5..7、低 8 位放在 octet1：
                    //   温度 0x0071 -> 02 71，光照 0x004E -> 04 4E
                    // 新固件按标准规范发送：
                    //   温度 0x0071 -> 22 0E，光照 0x004E -> C4 09
                    // 两种固件都兼容：标准解出未知属性时回退到老格式解一次。
                    val legacyId = (((byte0 shr 5) and 0x07) shl 8) or octet1
                    propertyId = if (isKnownSensorProperty(standardId) || !isKnownSensorProperty(legacyId)) {
                        standardId
                    } else {
                        Log.d("MeshApp", "检测到老固件 MPID: 0x${standardId.toString(16)} -> 0x${legacyId.toString(16)}")
                        legacyId
                    }
                    valueOffset = offset + 2
                    offset += 2 + length
                } else {
                    // Format B: 3字节头, byte0 bit0=1, bits1-6=length-1
                    if (offset + 2 >= data.size) break
                    // Format B: bit0=1, bits1-7=1-based length (0x7F means zero)
                    val lenCode = (byte0 and 0xFE) shr 1
                    length = if (lenCode == 0x7F) 0 else lenCode
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
                if (propertyId == 0x004F || propertyId == 0x0056 || propertyId == 0x0071) {
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
                        // 电路: 3.3V→100K→ADC→GL5506→GND, GL5506暗阻大亮阻小
                        // ADC读值 暗↑亮↓, 需要反转: 暗→0%, 亮→100%
                        val percent = ((4095f - raw.toFloat()) / 4095f * 100f).coerceIn(0f, 100f)
                        Log.d("MeshApp", "解析到光照度: raw=$raw -> ${"%.1f".format(percent)}% (Src: 0x${src.toString(16)})")
                        MeshState.lightLevelUpdates.postValue(Pair(src, percent))
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
    
    fun sendOnOff(address: Int, on: Boolean, brightness: Int = 100) {
        val network = MeshState.meshNetWork ?: return
        val appKey = network.appKeys.firstOrNull() ?: return
        val mappedBrightness = mapBrightnessForOC6701(brightness)
        val level = if (on) ((mappedBrightness - 50) * 655.35).toInt() else -32768
        val message = GenericLevelSetUnacknowledged(appKey, level, MeshState.currentTid)
        MeshState.currentTid++
        try {
            MeshState.meshManagerApi.createMeshPdu(address, message)
        } catch (e: Exception) {
            Log.e("MeshApp", "sendOnOff 失败: ${e.message}")
        }
    }

    fun sendGroupOnOff(groupAddress: Int, on: Boolean, brightness: Int = 100) {
        sendOnOff(groupAddress, on, brightness)
    }

    fun sendGroupBrightness(groupAddress: Int, brightness: Int) {
        sendBrightness(groupAddress, brightness)
    }

    /**
     * 读取指定元素的亮度（Generic Level Get）。
     * 设备会回 Generic Level Status，最终通过 levelReadUpdates 暴露成 0-100%。
     */
    fun readLevel(address: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        try {
            Log.d("MeshApp", "读取亮度: dst=0x${address.toString(16)}")
            MeshState.meshManagerApi.createMeshPdu(address, GenericLevelGet(appKey))
        } catch (e: Exception) {
            Log.e("MeshApp", "读取亮度失败: ${e.message}")
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
            val tempMessage = SensorGet(appKey, DeviceProperty.DESIRED_AMBIENT_TEMPERATURE)
            MeshState.meshManagerApi.createMeshPdu(address, tempMessage)
            val lightMessage = SensorGet(appKey, DeviceProperty.PRESENT_AMBIENT_LIGHT_LEVEL)
            MeshState.meshManagerApi.createMeshPdu(address, lightMessage)
        } catch (e: Exception) {
             Log.e("MeshApp", "请求传感器失败: ${e.message}")
             MeshState.statusText.postValue("读取传感器失败: ${e.message}")
        }
    }
    
    // 兼容旧代码，保留readTemperature作为别名
    @Deprecated("使用 readSensors 代替", ReplaceWith("readSensors(address)"))
    fun readTemperature(address: Int) = readSensors(address)

    // ========== 光敏模式 (Auto-Light Vendor Model) ==========

    /**
     * 发送光敏模式设置
     * @param enable 0=关闭, 1=开启
     * @param thresholdPercent 亮度阈值 0-100
     */
    fun sendAutoLightMode(elementAddress: Int, enable: Int, thresholdPercent: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        val threshold = thresholdPercent.coerceIn(0, 100)
        Log.d("MeshApp", "发送光敏模式: enable=$enable, threshold=$threshold% to elem=0x${elementAddress.toString(16)}")

        try {
            // 1) 先发 OnOff Set（有确认版本，Mesh 层会重传直到收到 ACK）
            val onOffMsg = GenericOnOffSet(appKey, enable == 1, MeshState.currentTid++)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, onOffMsg)
            // 2) 乐观更新本地状态，让 UI 立即响应
            MeshState.autoLightEnabledUpdates.postValue(Pair(elementAddress, enable))
            // 3) Level Set：阈值（无确认即可）
            val levelMsg = GenericLevelSetUnacknowledged(appKey, threshold, MeshState.currentTid++)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, levelMsg)
            MeshState.statusText.postValue("光敏模式: ${if (enable == 1) "开启" else "关闭"}, 阈值=$threshold%")
        } catch (e: Exception) {
            Log.e("MeshApp", "发送光敏模式失败: ${e.message}")
            MeshState.statusText.postValue("发送失败: ${e.message}")
        }
    }

    /**
     * 只关闭光敏模式使能，不改阈值/亮度（用于工作模式互斥）
     */
    fun sendAutoLightDisable(elementAddress: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化"); return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key"); return
        }
        try {
            val onOffMsg = GenericOnOffSet(appKey, false, MeshState.currentTid++)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, onOffMsg)
            MeshState.autoLightEnabledUpdates.postValue(Pair(elementAddress, 0))
            Log.d("MeshApp", "发送光敏关闭(不改阈值) to elem=0x${elementAddress.toString(16)}")
        } catch (e: Exception) {
            Log.e("MeshApp", "发送光敏关闭失败: ${e.message}")
        }
    }

    /**
     * 读取光敏模式状态（所有三个 Element）
     */
    fun readAutoLightMode(elementAddr1: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        val elementAddr2 = elementAddr1 + 1  // Element 2 = 主地址 + 2
        Log.d("MeshApp", "读取光敏模式: Elem1=0x${elementAddr1.toString(16)}, Elem2=0x${elementAddr2.toString(16)}")

        try {
            val onOffMsg = GenericOnOffGet(appKey)
            MeshState.meshManagerApi.createMeshPdu(elementAddr1, onOffMsg)
            val levelMsg = GenericLevelGet(appKey)
            MeshState.meshManagerApi.createMeshPdu(elementAddr1, levelMsg)
            // 也读取 Element 2 上的亮度值
            val brightnessMsg = GenericLevelGet(appKey)
            MeshState.meshManagerApi.createMeshPdu(elementAddr2, brightnessMsg)
            MeshState.statusText.postValue("正在读取光敏模式状态...")
        } catch (e: Exception) {
            Log.e("MeshApp", "读取光敏模式失败: ${e.message}")
            MeshState.statusText.postValue("读取失败: ${e.message}")
        }
    }

    /**
     * 发送自动开灯亮度（Element 2，地址 = 主地址 + 2）
     */
    fun sendAutoLightBrightness(elementAddr2: Int, brightness: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        val bri = brightness.coerceIn(1, 100)
        val mappedBrightness = mapBrightnessForOC6701(bri)
        Log.d(
            "MeshApp",
            "发送自动开灯亮度: UI=$bri%, mapped=$mappedBrightness% to 0x${elementAddr2.toString(16)}"
        )

        try {
            // Element 2 固件按"原始百分比"解析（不是标准 mesh level）：
            // app_scheduler_model.c 的 auto_light_brightness_set 直接取 le16 当 0-100% 用。
            // 因此这里必须发 mappedBrightness（0-100），不能做 (level-50)*655.35 换算，
            // 否则 <=77% 会被固件截成 1%，>=78% 会被截成 100%。
            val msg = GenericLevelSetUnacknowledged(appKey, mappedBrightness, MeshState.currentTid++)
            MeshState.meshManagerApi.createMeshPdu(elementAddr2, msg)
        } catch (e: Exception) {
            Log.e("MeshApp", "发送开灯亮度失败: ${e.message}")
        }
    }

    // ========== 整点报时 (Buzzer, Element 3) ==========

    /**
     * 发送整点报时开关
     * @param elementAddress Element 3 地址（主地址 + 3）
     * @param enable 0=关闭, 1=开启
     */
    fun sendBuzzerChimeMode(elementAddress: Int, enable: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        Log.d("MeshApp", "发送整点报时: enable=$enable to elem=0x${elementAddress.toString(16)}")

        try {
            val msg = GenericOnOffSetUnacknowledged(appKey, enable == 1, MeshState.currentTid++)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
            MeshState.statusText.postValue("整点报时: ${if (enable == 1) "开启" else "关闭"}")
        } catch (e: Exception) {
            Log.e("MeshApp", "发送整点报时开关失败: ${e.message}")
            MeshState.statusText.postValue("发送失败: ${e.message}")
        }
    }

    /**
     * 发送蜂鸣器音量（Element 3）
     * @param elementAddress Element 3 地址（主地址 + 3）
     * @param volume 音量 0-100
     */
    fun sendBuzzerVolume(elementAddress: Int, volume: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        val vol = volume.coerceIn(0, 100)
        Log.d("MeshApp", "发送蜂鸣器音量: $vol% to 0x${elementAddress.toString(16)}")

        try {
            val msg = GenericLevelSet(appKey, vol, MeshState.currentTid++)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
            MeshState.statusText.postValue("蜂鸣器音量: $vol%")
        } catch (e: Exception) {
            Log.e("MeshApp", "发送蜂鸣器音量失败: ${e.message}")
            MeshState.statusText.postValue("发送失败: ${e.message}")
        }
    }

    /**
     * 发送临时蜂鸣（不保存到 Flash），用于连接提示等场景
     * @param elementAddress Element 3 地址（主地址 + 3）
     * @param volume 音量 0-100
     */
    fun sendBuzzerBeep(elementAddress: Int, volume: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        val vol = volume.coerceIn(0, 100)
        Log.d("MeshApp", "发送临时蜂鸣: $vol% to 0x${elementAddress.toString(16)}")

        try {
            val msg = GenericLevelSetUnacknowledged(appKey, vol, MeshState.currentTid++)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
        } catch (e: Exception) {
            Log.e("MeshApp", "发送临时蜂鸣失败: ${e.message}")
        }
    }

    /**
     * 读取蜂鸣器配置（Element 3）
     */
    fun readBuzzerConfig(elementAddress: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        Log.d("MeshApp", "读取蜂鸣器配置: elem=0x${elementAddress.toString(16)}")

        try {
            val onOffMsg = GenericOnOffGet(appKey)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, onOffMsg)
            val levelMsg = GenericLevelGet(appKey)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, levelMsg)
            MeshState.statusText.postValue("正在读取蜂鸣器配置...")
        } catch (e: Exception) {
            Log.e("MeshApp", "读取蜂鸣器配置失败: ${e.message}")
            MeshState.statusText.postValue("读取失败: ${e.message}")
        }
    }

    /**
     * 读取雷达配置（Element 4 OnOff + Level）
     */
    fun readRadarConfig(elementAddress: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化"); return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key"); return
        }
        Log.d("MeshApp", "读取雷达配置: elem=0x${elementAddress.toString(16)}")
        try {
            val onOffMsg = GenericOnOffGet(appKey)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, onOffMsg)
            MeshState.mainHandler.postDelayed({
                val levelMsg = GenericLevelGet(appKey)
                MeshState.meshManagerApi.createMeshPdu(elementAddress, levelMsg)
            }, 400)
            MeshState.mainHandler.postDelayed({
                val brightnessMsg = GenericLevelGet(appKey)
                MeshState.meshManagerApi.createMeshPdu(elementAddress + 1, brightnessMsg)
            }, 800)
            MeshState.statusText.postValue("正在读取雷达配置...")
        } catch (e: Exception) {
            Log.e("MeshApp", "读取雷达配置失败: ${e.message}")
            MeshState.statusText.postValue("读取雷达配置失败: ${e.message}")
        }
    }

    // ========== 雷达模式 ==========

    /**
     * 发送雷达启用/禁用（Element 4 Generic OnOff）
     */
    fun sendRadarEnable(elementAddress: Int, enable: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化"); return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key"); return
        }
        Log.d("MeshApp", "发送雷达启用: enable=$enable to 0x${elementAddress.toString(16)}")
        try {
            val msg = GenericOnOffSetUnacknowledged(appKey, enable == 1, MeshState.currentTid++)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
            MeshState.statusText.postValue("雷达模式: ${if (enable == 1) "开启" else "关闭"}")
        } catch (e: Exception) {
            Log.e("MeshApp", "发送雷达开关失败: ${e.message}")
            MeshState.statusText.postValue("雷达开关发送失败: ${e.message}")
        }
    }

    /**
     * 发送雷达夜晚亮灯时长（Element 4 Generic Level）
     * @param durationX10 0-200 (0.0-20.0 分钟, ×10)
     */
    fun sendRadarNightDuration(elementAddress: Int, durationX10: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化"); return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key"); return
        }
        val v = durationX10.coerceIn(0, 200)
        Log.d("MeshApp", "发送雷达夜晚时长: $v (${v/10.0}min) to 0x${elementAddress.toString(16)}")
        try {
            val msg = GenericLevelSetUnacknowledged(appKey, v, MeshState.currentTid++)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
            MeshState.statusText.postValue("夜晚亮灯时长: ${v/10.0} 分钟")
        } catch (e: Exception) {
            Log.e("MeshApp", "发送雷达时长失败: ${e.message}")
            MeshState.statusText.postValue("发送失败: ${e.message}")
        }
    }

    /**
     * 发送雷达夜晚亮灯亮度（Element 5 Generic Level）
     *
     * 注意：雷达亮度协议里传的是“固件内部亮度百分比”，与手动亮度一样
     * 需要先经过 OC6701 补偿曲线（UI 百分比 → 实际驱动百分比），
     * 否则同样 UI 24% 会比手动亮度亮很多。
     */
    fun sendRadarNightBrightness(elementAddress: Int, brightness: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化"); return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key"); return
        }
        val ui = brightness.coerceIn(1, 100)
        val v = mapBrightnessForOC6701(ui).coerceIn(1, 100)
        Log.d("MeshApp", "发送雷达夜晚亮度: UI=$ui%, mapped=$v% to 0x${elementAddress.toString(16)}")
        try {
            val msg = GenericLevelSetUnacknowledged(appKey, v, MeshState.currentTid++)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
            MeshState.statusText.postValue("雷达亮灯亮度: $ui%")
        } catch (e: Exception) {
            Log.e("MeshApp", "发送雷达亮度失败: ${e.message}")
            MeshState.statusText.postValue("发送失败: ${e.message}")
        }
    }

    /**
     * 请求读取检测时间列表（Element 4 厂商模型）
     */
    fun sendRadarTimesGet(elementAddress: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化"); return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key"); return
        }
        Log.d("MeshApp", "请求雷达检测时间: 0x${elementAddress.toString(16)}")
        try {
            val msg = VendorModelMessageUnacked(appKey, RADAR_VENDOR_MODEL_ID, RADAR_CID, OP_RADAR_TIMES_GET, null)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
            MeshState.statusText.postValue("正在读取检测时间列表...")
        } catch (e: Exception) {
            Log.e("MeshApp", "请求检测时间失败: ${e.message}")
            MeshState.statusText.postValue("请求失败: ${e.message}")
        }
    }

    /**
     * 请求读取夜晚时段（Element 4 厂商模型）
     */
    fun sendRadarNightHoursGet(elementAddress: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化"); return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key"); return
        }
        Log.d("MeshApp", "请求雷达夜晚时段: 0x${elementAddress.toString(16)}")
        try {
            val msg = VendorModelMessageUnacked(appKey, RADAR_VENDOR_MODEL_ID, RADAR_CID, OP_RADAR_NIGHT_HOURS_GET, null)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
        } catch (e: Exception) {
            Log.e("MeshApp", "请求夜晚时段失败: ${e.message}")
        }
    }

    /**
     * 设置夜晚时段（Element 4 厂商模型）
     */
    fun sendRadarNightHoursSet(elementAddress: Int, startHour: Int, endHour: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化"); return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key"); return
        }
        Log.d("MeshApp", "设置雷达夜晚时段: $startHour:00 - $endHour:00")
        try {
            val params = byteArrayOf(startHour.toByte(), endHour.toByte())
            val msg = VendorModelMessageUnacked(appKey, RADAR_VENDOR_MODEL_ID, RADAR_CID, OP_RADAR_NIGHT_HOURS_SET, params)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
            MeshState.statusText.postValue("夜晚时段: $startHour:00 - $endHour:00")
        } catch (e: Exception) {
            Log.e("MeshApp", "设置夜晚时段失败: ${e.message}")
            MeshState.statusText.postValue("设置失败: ${e.message}")
        }
    }

    /**
     * 请求读取检测提示音配置（Element 4 厂商模型）
     */
    fun sendRadarSoundGet(elementAddress: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化"); return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key"); return
        }
        Log.d("MeshApp", "请求雷达提示音配置: 0x${elementAddress.toString(16)}")
        try {
            val msg = VendorModelMessageUnacked(appKey, RADAR_VENDOR_MODEL_ID, RADAR_CID, OP_RADAR_SOUND_GET, null)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
        } catch (e: Exception) {
            Log.e("MeshApp", "请求提示音配置失败: ${e.message}")
        }
    }

    /**
     * 设置检测提示音配置（Element 4 厂商模型）: [enabled(1B), volume(1B)]
     */
    fun sendRadarSoundSet(elementAddress: Int, enabled: Int, volume: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化"); return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key"); return
        }
        Log.d("MeshApp", "设置雷达提示音: enabled=$enabled, volume=$volume")
        try {
            val params = byteArrayOf(enabled.toByte(), volume.toByte())
            val msg = VendorModelMessageUnacked(appKey, RADAR_VENDOR_MODEL_ID, RADAR_CID, OP_RADAR_SOUND_SET, params)
            MeshState.meshManagerApi.createMeshPdu(elementAddress, msg)
            MeshState.statusText.postValue("提示音: ${if (enabled == 1) "开" else "关"}, 音量 $volume%")
        } catch (e: Exception) {
            Log.e("MeshApp", "设置提示音失败: ${e.message}")
            MeshState.statusText.postValue("设置失败: ${e.message}")
        }
    }

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

        // 检查节点和 Model 绑定状态
        val node = network.getNode(address)
        if (node == null) {
            Log.e("MeshApp", "节点 0x${address.toString(16)} 不存在")
            MeshState.statusText.postValue("节点不存在")
            return
        }

        Log.d("MeshApp", "=== SchedulerGet 调试信息 ===")
        Log.d("MeshApp", "目标地址: 0x${address.toString(16)}")
        Log.d("MeshApp", "节点名称: ${node.nodeName}")
        Log.d("MeshApp", "AppKey Index: ${appKey.keyIndex}, Key: ${appKey.key.joinToString("") { "%02X".format(it) }}")

        // 检查 Scheduler Model (0x1206) 是否存在并绑定
        var schedulerModelFound = false
        var schedulerModelBound = false
        node.elements?.forEach { (_, element) ->
            element.meshModels?.forEach { (modelId, model) ->
                if (modelId == 0x1206) {
                    schedulerModelFound = true
                    val boundKeys = model.boundAppKeyIndexes
                    schedulerModelBound = boundKeys.contains(appKey.keyIndex)
                    Log.d("MeshApp", "找到 Scheduler Model (0x1206) 在 Element ${element.elementAddress}")
                    Log.d("MeshApp", "  绑定的 AppKey: ${boundKeys.joinToString()}")
                    Log.d("MeshApp", "  是否绑定当前 AppKey: $schedulerModelBound")
                }
            }
        }

        if (!schedulerModelFound) {
            Log.w("MeshApp", "警告: 节点未声明 Scheduler Model (0x1206)")
        }
        if (!schedulerModelBound) {
            Log.w("MeshApp", "警告: Scheduler Model 未绑定 AppKey ${appKey.keyIndex}")
        }

        Log.d("MeshApp", "发送 SchedulerGet 到地址: 0x${address.toString(16)}")

        val message = SchedulerMessageHelper.createSchedulerGet(appKey)
        Log.d("MeshApp", "消息类型: ${message.javaClass.simpleName}")
        Log.d("MeshApp", "消息 OpCode: 0x${message.opCode.toString(16)}")

        try {
            MeshState.meshManagerApi.createMeshPdu(address, message)
            Log.d("MeshApp", "PDU 已创建并发送")
            MeshState.statusText.postValue("正在读取调度状态...")

            // 设置 5 秒超时
            MeshState.schedulerGetTimeoutRunnable = Runnable {
                if (MeshState.statusText.value?.contains("正在读取调度状态") == true) {
                    MeshState.statusText.postValue("设备 0x${address.toString(16)} 响应超时 - 可能不支持 Scheduler")
                    Log.w("MeshApp", "SchedulerGet 超时，可能原因：")
                    Log.w("MeshApp", "  1) 固件未实现 Scheduler Server")
                    Log.w("MeshApp", "  2) Model 未绑定 AppKey (found=$schedulerModelFound, bound=$schedulerModelBound)")
                    Log.w("MeshApp", "  3) OpCode 不匹配 (期望 0x8249)")
                    Log.w("MeshApp", "  4) 网络连接问题")
                }
            }
            MeshState.mainHandler.postDelayed(MeshState.schedulerGetTimeoutRunnable!!, 5000)
        } catch (e: Exception) {
            Log.e("MeshApp", "创建 SchedulerGet PDU 失败: ${e.message}")
            Log.e("MeshApp", "异常堆栈: ", e)
            MeshState.statusText.postValue("读取调度失败: ${e.message}")
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
        val message = SchedulerMessageHelper.createSchedulerActionGet(appKey, index)

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

    // 设置 Scheduler 任务（发送到设备）
    // 发送后读取 bitmap 确认是否设置成功
    fun setSchedulerTask(address: Int, task: SchedulerTask) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }

        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 App Key")
            return
        }

        Log.d("MeshApp", "发送 SchedulerActionSet 到地址: 0x${address.toString(16)}, 索引: ${task.index}")
        val message = SchedulerMessageHelper.createSchedulerActionSet(appKey, task)

        try {
            MeshState.pendingSchedulerSetAddress = address
            MeshState.pendingSchedulerSetIndex = task.index
            MeshState.meshManagerApi.createMeshPdu(address, message)
            MeshState.statusText.postValue("正在设置调度任务 #${task.index}...")

            // 取消之前的 Get 超时
            MeshState.schedulerGetTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }

            // 等待设备处理 Set 请求后，读取 bitmap 确认
            MeshState.mainHandler.postDelayed({
                if (MeshState.pendingSchedulerSetAddress == address) {
                    Log.d("MeshApp", "Set 已发送，读取 bitmap 确认...")
                    readScheduler(address)
                    // 取消 readScheduler 创建的超时，统一由 Set 确认超时管理
                    // 避免产生孤儿 Runnable 导致误报超时
                    MeshState.schedulerGetTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }
                    // 设置确认超时（兼容两种状态文本）
                    MeshState.schedulerGetTimeoutRunnable = Runnable {
                        val currentStatus = MeshState.statusText.value ?: ""
                        if (currentStatus.contains("正在读取调度状态") || currentStatus.contains("正在设置调度任务")) {
                            MeshState.statusText.postValue("设置调度任务超时")
                            Log.w("MeshApp", "SchedulerActionSet 确认超时")
                        }
                        MeshState.pendingSchedulerSetAddress = -1
                    }
                    MeshState.mainHandler.postDelayed(MeshState.schedulerGetTimeoutRunnable!!, 5000)
                }
            }, 1000)
        } catch (e: Exception) {
            Log.e("MeshApp", "创建 SchedulerActionSet PDU 失败: ${e.message}")
            MeshState.pendingSchedulerSetAddress = -1
            MeshState.statusText.postValue("设置调度任务失败: ${e.message}")
        }
    }

    // 读取设备所有调度任务（先获取 bitmap，再逐个读取）
    fun readAllSchedulerTasks(address: Int) {
        readScheduler(address)
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
        
        // TAI 时间 = (Unix时间戳 - Unix到Mesh纪元偏移 + TAI-UTC差值)
        // Mesh TAI 纪元: 2000-01-01 00:00:00 UTC, Unix 纪元: 1970-01-01 00:00:00 UTC
        val unixToMeshEpoch = 946684800L
        val taiSeconds = (currentTime - unixToMeshEpoch + 37).toInt()
        
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
                val currentList = MeshState.scannedDevices.value?.toMutableList() ?: mutableListOf()
                val existingIndex = currentList.indexOfFirst { it.device.address == device.device.address }
                if (existingIndex >= 0) {
                    currentList[existingIndex] = device  // 更新 RSSI
                } else {
                    currentList.add(device)
                }
                MeshState.scannedDevices.postValue(currentList)
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

    fun clearScannedDevices() {
        MeshState.scannedDevices.setValue(emptyList())
    }
    
    fun autoConnectToStrongestProxyInAddresses(addresses: List<String>, scanDurationMs: Long = 3000L) {
        val targetAddresses = addresses.map { it.uppercase() }.toSet()
        val candidates = mutableMapOf<String, ScanResult>()
        MeshState.statusText.postValue("正在扫描设备卡片中信号最强节点...")
        MeshState.bleScanner.startScan(object : BleScannerManager.ScanListener {
            override fun onDeviceFound(device: ScanResult) {
                val address = device.device.address.uppercase()
                if (address in targetAddresses) {
                    val current = candidates[address]
                    if (current == null || device.rssi > current.rssi) {
                        candidates[address] = device
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                MeshState.statusText.postValue("扫描失败: $errorCode")
            }
        })

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            MeshState.bleScanner.stopScan()
            updateDeviceOnlineStatusByMac(candidates.keys)
            val strongest = candidates.values.maxByOrNull { it.rssi }
            if (strongest != null) {
                MeshState.statusText.postValue("连接设备卡片中信号最强节点 ${strongest.device.address} (${strongest.rssi}dBm)")
                connectToDevice(strongest)
            } else {
                MeshState.statusText.postValue("未扫描到设备卡片中的 Proxy 节点，尝试历史连接")
                autoConnectFromHistory(onAllFailed = {
                    autoConnectToStrongestProxy(scanDurationMs)
                })
            }
        }, scanDurationMs)
    }

    private fun updateDeviceOnlineStatusByMac(onlineMacs: Set<String>) {
        try {
            val repo = DeviceRepository(getApplication())
            val normalizedOnlineMacs = onlineMacs.map { it.uppercase() }.toSet()
            var changed = false
            repo.getAllDevices().forEach { device ->
                val mac = device.bluetoothMac?.uppercase()
                val isReachable = mac != null && mac in normalizedOnlineMacs
                if (device.isOnline != isReachable) {
                    device.isOnline = isReachable
                    repo.updateDevice(device)
                    changed = true
                }
            }
            if (changed) {
                MeshState.deviceOnlineUpdates.postValue(Unit)
            }
        } catch (e: Exception) {
            Log.e("MeshApp", "更新设备在线状态失败: ${e.message}")
        }
    }

    fun autoConnectToStrongestKnownProxy(scanDurationMs: Long = 3000L) {
        val knownAddresses = getProxyAddressHistory().toSet()
        if (knownAddresses.isEmpty()) {
            MeshState.statusText.postValue("没有历史连接记录，正在搜索 Proxy 节点...")
            autoConnectToStrongestProxy(scanDurationMs)
            return
        }

        val candidates = mutableMapOf<String, ScanResult>()
        MeshState.statusText.postValue("正在扫描信号最强设备...")
        MeshState.bleScanner.startScan(object : BleScannerManager.ScanListener {
            override fun onDeviceFound(device: ScanResult) {
                val address = device.device.address
                if (address in knownAddresses) {
                    val current = candidates[address]
                    if (current == null || device.rssi > current.rssi) {
                        candidates[address] = device
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                MeshState.statusText.postValue("扫描失败: $errorCode")
            }
        })

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            MeshState.bleScanner.stopScan()
            val strongest = candidates.values.maxByOrNull { it.rssi }
            if (strongest != null) {
                MeshState.statusText.postValue("连接信号最强设备 ${strongest.device.address} (${strongest.rssi}dBm)")
                connectToDevice(strongest)
            } else {
                MeshState.statusText.postValue("未扫描到设备列表中的 Proxy 节点")
                autoConnectFromHistory(onAllFailed = {
                    autoConnectToStrongestProxy(scanDurationMs)
                })
            }
        }, scanDurationMs)
    }

    fun autoConnectToStrongestProxy(scanDurationMs: Long = 3000L) {
        MeshState.statusText.postValue("正在搜索信号最强 Proxy 节点...")
        val candidates = mutableMapOf<String, ScanResult>()
        MeshState.bleScanner.startScan(object : BleScannerManager.ScanListener {
            override fun onDeviceFound(device: ScanResult) {
                val address = device.device.address
                val current = candidates[address]
                if (current == null || device.rssi > current.rssi) {
                    candidates[address] = device
                }
            }

            override fun onScanFailed(errorCode: Int) {
                MeshState.statusText.postValue("扫描失败: $errorCode")
            }
        })

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            MeshState.bleScanner.stopScan()
            val strongest = candidates.values.maxByOrNull { it.rssi }
            if (strongest != null) {
                MeshState.statusText.postValue("连接信号最强 Proxy 节点 ${strongest.device.address} (${strongest.rssi}dBm)")
                connectToDevice(strongest)
            } else {
                MeshState.statusText.postValue("未找到 Proxy 节点")
            }
        }, scanDurationMs)
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
            // 使用实际的 MTU 大小
            val mtu = MeshState.bleConnection.mtuSize
            Log.d("MeshApp", "handleNotifications - MTU: $mtu, 数据长度: ${data.size}")
            MeshState.meshManagerApi.handleNotifications(mtu, data)
        }

        override fun onDataSent(data: ByteArray) {
            val mtu = MeshState.bleConnection.mtuSize
            MeshState.meshManagerApi.handleWriteCallbacks(mtu, data)
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
        // 清除旧的轮询，避免重复
        rssiUpdateHandler?.removeCallbacks(rssiUpdateRunnable)
        rssiUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        rssiUpdateHandler?.post(rssiUpdateRunnable)
    }
    
    private fun stopRssiUpdates() {
        rssiUpdateHandler?.removeCallbacks(rssiUpdateRunnable)
        rssiUpdateHandler = null
        MeshState.currentRssi.postValue(-999)
    }

    /**
     * 确保 RSSI 轮询正在运行（可在 Activity 重入时调用）
     */
    fun ensureRssiUpdates() {
        if (MeshState.isConnected.value == true) {
            startRssiUpdates()
        }
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

    /**
     * 仅控制权限导出：剥离所有节点的 Device Key（接收方无法发 Config 命令，
     * 无法清除配网/改模型绑定，但仍可用 App Key 正常开关灯）。
     */
    private fun exportMeshNetworkControlOnly(): String? {
        return try {
            MeshState.meshManagerApi.exportMeshNetwork(
                no.nordicsemi.android.mesh.NetworkKeysConfig.ExportAll().build(),
                no.nordicsemi.android.mesh.ApplicationKeysConfig.ExportAll().build(),
                no.nordicsemi.android.mesh.NodesConfig.ExportWithoutDeviceKey().build(),
                no.nordicsemi.android.mesh.ProvisionersConfig.ExportAll().build(),
                no.nordicsemi.android.mesh.GroupsConfig.ExportAll().build(),
                no.nordicsemi.android.mesh.ScenesConfig.ExportAll().build()
            )
        } catch (e: Exception) {
            Log.e("MeshApp", "仅控制导出失败: ${e.message}")
            null
        }
    }

    /**
     * 导出 Mesh 网络配置 + 设备元数据（名称、类型等）的合并 JSON
     * 用于多手机共享设备控制
     * @param controlOnly true = 仅控制（剥离 Device Key，不能清除配网）
     */
    fun exportMeshNetworkWithDevices(controlOnly: Boolean = false): String? {
        // 控制端本机没有 Device Key，导出时强制按“仅控制”处理
        val effectiveControlOnly = controlOnly || MeshState.isControlOnly
        val meshJson = (if (effectiveControlOnly) exportMeshNetworkControlOnly() else exportMeshNetwork()) ?: return null
        try {
            val devices = DeviceRepository(getApplication()).getAllDevices()
            val gson = com.google.gson.Gson()
            val wrapper = java.util.LinkedHashMap<String, Any>()
            wrapper["version"] = 1
            wrapper["role"] = if (effectiveControlOnly) "control" else "admin"
            wrapper["meshNetwork"] = meshJson
            wrapper["devices"] = devices.map { device ->
                mapOf(
                    "name" to device.name,
                    "address" to device.address,
                    "type" to device.type.name,
                    "brightness" to device.brightness,
                    "groupAddress" to device.groupAddress,
                    "bluetoothMac" to device.bluetoothMac
                )
            }
            return gson.toJson(wrapper)
        } catch (e: Exception) {
            Log.e("MeshApp", "导出设备元数据失败: ${e.message}")
            return meshJson // 降级返回纯 Mesh 网络 JSON
        }
    }

    /** 根据导入内容设置本机角色：control = 仅控制（无 Device Key） */
    private fun applyImportedRole(role: String) {
        val controlOnly = role.equals("control", ignoreCase = true)
        MeshState.isControlOnly = controlOnly
        getApplication<Application>()
            .getSharedPreferences(ROLE_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ROLE_KEY_CONTROL_ONLY, controlOnly)
            .apply()
        Log.d("MeshApp", "本机角色: ${if (controlOnly) "仅控制" else "管理员"}")
    }

    /**
     * nRF Mesh 库在“导出时不含 Device Key”的情况下会把 deviceKey 序列化为 ""，
     * 反序列化后得到 0 长度数组而不是 null。统一改成 null，
     * 避免任何误触发的 Config 消息使用空密钥。
     */
    private fun normalizeEmptyDeviceKeys(network: MeshNetwork) {
        try {
            var count = 0
            network.nodes.forEach { node ->
                val key = node.deviceKey
                if (key != null && key.isEmpty()) {
                    node.setDeviceKey(null)
                    count++
                }
            }
            if (count > 0) {
                Log.d("MeshApp", "已清理 $count 个空 Device Key（仅控制网络）")
            }
        } catch (e: Exception) {
            Log.w("MeshApp", "清理空 Device Key 失败: ${e.message}")
        }
    }

    /**
     * 导入合并的 JSON（包含 Mesh 网络 + 设备元数据）
     * 兼容旧格式（纯 nRF Mesh JSON）
     */
    fun importMeshNetworkWithDevices(jsonData: String) {
        try {
            // 先尝试解析为合并格式
            val gson = com.google.gson.Gson()
            val json = org.json.JSONObject(jsonData)

            if (json.has("meshNetwork")) {
                // === 合并格式 ===
                // 角色由发送方决定：control = 仅控制（Device Key 已被剥离）
                applyImportedRole(json.optString("role", "admin"))
                val meshJson = json.getString("meshNetwork")
                // 先导入 Mesh 网络（触发 onNetworkImported 回调设置 Provisioner 地址）
                importMeshNetwork(meshJson)

                // 导入设备元数据
                if (json.has("devices")) {
                    val devicesArray = json.getJSONArray("devices")
                    val repo = DeviceRepository(getApplication())
                    val existingDevices = repo.getAllDevices()
                    val existingAddresses = existingDevices.map { it.address }.toSet()

                    for (i in 0 until devicesArray.length()) {
                        val deviceJson = devicesArray.getJSONObject(i)
                        val name = deviceJson.getString("name")
                        val address = deviceJson.getInt("address")
                        val typeName = deviceJson.optString("type", "LIGHT")
                        val brightness = deviceJson.optInt("brightness", 50)
                        val groupAddress = if (deviceJson.has("groupAddress") && !deviceJson.isNull("groupAddress"))
                            deviceJson.getInt("groupAddress") else null
                        val bluetoothMac = if (deviceJson.has("bluetoothMac") && !deviceJson.isNull("bluetoothMac"))
                            deviceJson.getString("bluetoothMac") else null
                        val deviceType = try {
                            DeviceType.valueOf(typeName)
                        } catch (e: Exception) {
                            DeviceType.LIGHT
                        }

                        // 跳过已存在的设备
                        if (address in existingAddresses) continue

                        val device = MeshDevice(
                            id = "mesh_$address",
                            name = name,
                            address = address,
                            type = deviceType,
                            brightness = brightness,
                            groupAddress = groupAddress,
                            bluetoothMac = bluetoothMac
                        )
                        repo.addDevice(device)
                        Log.d("MeshApp", "已从导入配置恢复设备: $name (0x${address.toString(16)})")
                    }

                    // 从导入的设备 MAC 地址填充代理地址历史记录
                    val allDevices = repo.getAllDevices()
                    val existingHistory = getProxyAddressHistory().toSet()
                    allDevices.forEach { d ->
                        d.bluetoothMac?.let { mac ->
                            if (mac !in existingHistory) {
                                saveProxyAddress(mac)
                            }
                        }
                    }
                }
            } else {
                // === 旧格式：纯 nRF Mesh JSON ===
                importMeshNetwork(jsonData)
            }
        } catch (e: org.json.JSONException) {
            // 不是标准 JSON 或合并格式，尝试作为纯 nRF Mesh JSON 导入
            Log.d("MeshApp", "不是合并格式 JSON，尝试纯 Mesh 网络导入: ${e.message}")
            importMeshNetwork(jsonData)
        } catch (e: Exception) {
            Log.e("MeshApp", "导入失败: ${e.message}")
            MeshState.statusText.postValue("导入失败: ${e.message}")
        }
    }
    
    fun setProvisionerAddress(address: Int) {
        val network = MeshState.meshNetWork
        if (network == null) {
            MeshState.statusText.postValue("网络未加载")
            return
        }
        
        // 检查地址是否已存在于 JSON 中
        var nodeExists = false
        try {
            val method = network.javaClass.getMethod("getProvisionedNode", Int::class.javaPrimitiveType)
            val node = method.invoke(network, address)
            nodeExists = (node != null)
        } catch (e: Exception) {
            Log.e("MeshApp", "验证地址失败: $e")
        }
        
        if (!nodeExists) {
            Log.e("MeshApp", "地址 0x${address.toString(16)} 对应的节点不存在于网络中")
            MeshState.statusText.postValue("地址 0x${address.toString(16)} 不在网络中，请先导入包含该节点的配置")
            return
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

    private fun setProvisionerAddressInternal(network: MeshNetwork, address: Int) {
        val provisioner = network.selectedProvisioner
        // 1. 设置 Provisioner 地址（反射）
        val setMethod = try {
            provisioner.javaClass.getDeclaredMethod("setProvisionerAddress", Integer::class.java)
        } catch (e: Exception) {
            provisioner.javaClass.getDeclaredMethod("setProvisionerAddress", Int::class.javaPrimitiveType)
        }
        setMethod.isAccessible = true
        setMethod.invoke(provisioner, address)

        // 2. 同步更新 Nodes 列表中对应 ProvisionedMeshNode 的 unicastAddress
        val node = network.getNode(provisioner.provisionerUuid)
        if (node != null) {
            node.setUnicastAddress(address)
            Log.d("MeshApp", "已同步 ProvisionedMeshNode 地址: 0x${address.toString(16)}")
        }

        // 3. 确保地址在 provisioner 的分配范围内
        val hasRange = provisioner.allocatedUnicastRanges.any { it.isInRange(address) }
        if (!hasRange) {
            provisioner.addRange(AllocatedUnicastRange(0x0200, 0x02FF))
        }
    }

    /**
     * 根据设备唯一标识生成 Provisioner 地址
     * 地址范围: 0x200-0x2FF (512-767)
     * 包含冲突检测和解决机制
     */
    fun getOrGenerateDeviceAddress(context: android.content.Context): Int {
        val prefs = context.getSharedPreferences("mesh_config", android.content.Context.MODE_PRIVATE)
        
        // Provisioner 自己的节点 UUID：冲突检测时必须排除，否则每次启动都会把自己的地址当成冲突
        val provisionerUuid = MeshState.meshNetWork?.selectedProvisioner?.provisionerUuid

        // 检查是否已保存地址
        val savedAddress = prefs.getInt("provisioner_address", -1)
        if (savedAddress != -1) {
            // 即使有缓存也检查冲突（后续导入可能有新节点占用该地址），但要忽略 Provisioner 自身
            val network = MeshState.meshNetWork
            val occupant = network?.getNode(savedAddress)
            val occupiedByOther = occupant != null && occupant.uuid != provisionerUuid
            if (occupiedByOther) {
                Log.w("MeshApp", "缓存地址 0x${savedAddress.toString(16)} 已被节点占用，重新分配")
                prefs.edit().remove("provisioner_address").apply()
            } else {
                Log.d("MeshApp", "使用已保存的地址: 0x${savedAddress.toString(16)}")
                return savedAddress
            }
        }
        
        // 基于 Android ID 生成地址
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "default"
        
        // 使用哈希生成初始地址
        val hash = androidId.hashCode()
        var address = 0x200 + (Math.abs(hash) % 256)
        
        // 冲突检测：检查该地址是否已被网络中已有节点占用
        val network = MeshState.meshNetWork
        if (network != null) {
            var attempts = 0
            while (attempts < 60) {  // 最多尝试 60 次（范围 0x200-0x23F）
                val node = network.getNode(address)
                if (node != null && node.uuid != provisionerUuid) {
                    Log.w("MeshApp", "地址 0x${address.toString(16)} 已被节点 ${node.nodeName} 占用，尝试下一个")
                    address = 0x200 + ((address - 0x200 + 1) % 256)
                    attempts++
                } else {
                    break  // 地址可用
                }
            }
            if (attempts >= 60) {
                Log.w("MeshApp", "地址冲突检测超限，使用 0x${address.toString(16)}")
            } else {
                Log.d("MeshApp", "地址 0x${address.toString(16)} 可用（冲突检测通过）")
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

    /**
     * Mesh 网络数据库里的全部节点（排除 Provisioner 自身），并标注是否已加入本地设备列表。
     * 用于「删除了设备还能找回」：删除只影响本地列表，节点仍在网络数据库中。
     */
    fun getRecoverableNodes(): List<RecoverableNode> {
        val network = MeshState.meshNetWork ?: return emptyList()
        val provisionerUuid = network.selectedProvisioner?.provisionerUuid
        val existing = DeviceRepository(getApplication()).getAllDevices().map { it.address }.toSet()
        return network.nodes
            .filter { it.uuid != provisionerUuid }
            .map { RecoverableNode(it.unicastAddress, it.nodeName ?: "", it.unicastAddress in existing) }
            .distinctBy { it.address }
            .sortedBy { it.address }
    }

    /** 向指定地址发一条 GenericOnOffGet：节点若在线会回包并刷新 lastMessageTime */
    fun probeNode(address: Int): Boolean {
        return try {
            val appKey = MeshState.meshNetWork?.appKeys?.firstOrNull() ?: return false
            MeshState.meshManagerApi.createMeshPdu(address, GenericOnOffGet(appKey))
            true
        } catch (e: Exception) {
            Log.w("MeshApp", "探测 0x${address.toString(16)} 失败: ${e.message}")
            false
        }
    }

    // ---------------- 地址扫描：把未知设备的单播地址找回来 ----------------

    private var addressScanRunnable: Runnable? = null
    private var addressScanning = false

    /**
     * 逐个单播地址发 GenericOnOffGet（只是查询，不改状态）。
     * 回包会被 markDeviceOnline 记进 lastMessageTime，调用方据此判断哪些地址活着。
     * 只用到 NetKey + AppKey，不需要 DevKey —— 卡片和备份都丢了也能找回地址。
     *
     * @return false 表示当前无法扫描（没网络 / 没 AppKey / 没连上 Proxy）
     */
    fun startAddressScan(
        from: Int,
        to: Int,
        onProgress: (scanned: Int, total: Int) -> Unit,
        onDone: () -> Unit
    ): Boolean {
        val network = MeshState.meshNetWork ?: return false
        val appKey = network.appKeys.firstOrNull() ?: return false
        if (!MeshState.bleConnection.isConnected()) return false

        stopAddressScan()
        val self = try { network.selectedProvisioner?.provisionerAddress ?: -1 } catch (e: Exception) { -1 }
        val total = (to - from + 1).coerceAtLeast(1)
        var current = from
        addressScanning = true

        val runnable = object : Runnable {
            override fun run() {
                if (!addressScanning) return
                if (current > to) {
                    addressScanning = false
                    // 留一点时间收尾包
                    MeshState.mainHandler.postDelayed({ onDone() }, 800)
                    return
                }
                val target = current
                current++
                if (target != self && target < 0xFF00) {
                    try {
                        MeshState.meshManagerApi.createMeshPdu(target, GenericOnOffGet(appKey))
                    } catch (e: Exception) {
                        Log.w("MeshApp", "扫描 0x${target.toString(16)} 失败: ${e.message}")
                    }
                }
                onProgress(target - from + 1, total)
                MeshState.mainHandler.postDelayed(this, 70)
            }
        }
        addressScanRunnable = runnable
        MeshState.mainHandler.post(runnable)
        return true
    }

    fun stopAddressScan() {
        addressScanRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }
        addressScanRunnable = null
        addressScanning = false
    }

    /** 连发两次开关，方便肉眼确认这个地址对应哪盏灯 */
    fun blinkAddress(address: Int) {
        val handler = MeshState.mainHandler
        val steps = listOf(true, false, true, false)
        steps.forEachIndexed { index, on ->
            handler.postDelayed({ sendOnOff(address, on, 100) }, index * 500L)
        }
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
                val mtu = MeshState.bleConnection.mtuSize
                MeshState.meshManagerApi.handleNotifications(mtu, data)
            }

            override fun onDataSent(data: ByteArray) {
                val mtu = MeshState.bleConnection.mtuSize
                MeshState.meshManagerApi.handleWriteCallbacks(mtu, data)
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
    
    /**
     * 按优先级顺序尝试连接一组 MAC 地址：
     * 先试 targetMac，再试 groupMacs，最后试 allOtherMacs
     */
    fun connectToDeviceList(
        targetMac: String?,
        groupMacs: List<String>,
        allOtherMacs: List<String>,
        onConnected: (String) -> Unit = {},
        onAllFailed: () -> Unit = {}
    ) {
        // 构建优先级列表：目标 → 同组 → 其他
        val priorityList = mutableListOf<String>()
        if (targetMac != null) {
            priorityList.add(targetMac)
        }
        // 同组设备（去重，排除目标）
        for (mac in groupMacs) {
            if (mac != targetMac && mac !in priorityList) {
                priorityList.add(mac)
            }
        }
        // 其他已配网设备（去重）
        for (mac in allOtherMacs) {
            if (mac !in priorityList) {
                priorityList.add(mac)
            }
        }

        if (priorityList.isEmpty()) {
            MeshState.statusText.postValue("没有可连接的设备")
            onAllFailed()
            return
        }

        MeshState.statusText.postValue("正在尝试连接 ${priorityList.size} 个设备...")
        tryConnectNextInList(priorityList, 0, onConnected, onAllFailed)
    }

    private fun tryConnectNextInList(
        addresses: List<String>,
        index: Int,
        onConnected: (String) -> Unit,
        onAllFailed: () -> Unit
    ) {
        if (index >= addresses.size) {
            MeshState.statusText.postValue("所有设备均无法连接")
            onAllFailed()
            return
        }

        val address = addresses[index]
        MeshState.statusText.postValue("连接 $address (${index + 1}/${addresses.size})...")

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            MeshState.statusText.postValue("连接 $address 超时")
            handler.postDelayed({
                tryConnectNextInList(addresses, index + 1, onConnected, onAllFailed)
            }, 500)
        }
        handler.postDelayed(timeoutRunnable, 5000)

        MeshState.bleConnection.connect(address, object : BleConnectionManager.ConnectionListener {
            override fun onConnected() {
                MeshState.statusText.postValue("设备已连接，正在发现服务...")
            }

            override fun onDisconnected() {
                handler.removeCallbacks(timeoutRunnable)
                MeshState.isConnected.postValue(false)
                MeshState.connectedDeviceAddress.postValue(null)
            }

            override fun onServicesDiscovered() {
                handler.removeCallbacks(timeoutRunnable)
                MeshState.isConnected.postValue(true)
                MeshState.connectedDeviceAddress.postValue(address)
                MeshState.statusText.postValue("已连接到 $address")
                saveProxyAddress(address)
                startRssiUpdates()
                onConnected(address)
            }

            override fun onDataReceived(data: ByteArray) {
                val mtu = MeshState.bleConnection.mtuSize
                MeshState.meshManagerApi.handleNotifications(mtu, data)
            }

            override fun onDataSent(data: ByteArray) {
                val mtu = MeshState.bleConnection.mtuSize
                MeshState.meshManagerApi.handleWriteCallbacks(mtu, data)
            }

            override fun onMeshMessageReceived(src: Int, data: ByteArray) {}

            override fun onRssiRead(rssi: Int) {
                MeshState.currentRssi.postValue(rssi)
            }

            override fun onError(error: String) {
                handler.removeCallbacks(timeoutRunnable)
                handler.postDelayed({
                    tryConnectNextInList(addresses, index + 1, onConnected, onAllFailed)
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
        // 重置配网状态，避免旧的 LiveData 值在下次打开 ProvisionActivity 时误触
        resetProvisioningState()
        MeshState.bleScanner.startUnprovisionedScan { node: UnprovisionedMeshNode, scanResult: android.bluetooth.le.ScanResult ->
            val current = MeshState.unprovisionedDevices.value ?: emptyList()
            if (current.none { n: UnprovisionedMeshNode -> n.deviceUuid == node.deviceUuid }) {
                MeshState.unprovisionedDevices.postValue(current + node)
                MeshState.unprovisionedScanResults[node.deviceUuid] = scanResult
            }
        }
    }

    /**
     * 重置配网相关状态，防止旧的 LiveData 值在下次进入 ProvisionActivity 时误触
     */
    fun resetProvisioningState() {
        MeshState.isProvisioning.postValue(false)
        MeshState.provisioningStatus.postValue("")
        MeshState.provisionConfig = null
        MeshState.currentUnprovisionedNode = null
        MeshState.unprovisionedDevices.postValue(emptyList())
        MeshState.unprovisionedScanResults.clear()
    }
    
    fun stopUnprovisionedScan() = MeshState.bleScanner.stopScan()

    /**
     * 返回网络中所有 AppKey 的名称列表，用于配网配置对话框选择
     */
    fun getAppKeyNames(): List<String> {
        val network = MeshState.meshNetWork ?: return emptyList()
        return network.appKeys.mapIndexed { index, key ->
            val name = key.name
            val boundNetKeyName = network.netKeys.find { it.keyIndex == key.boundNetKeyIndex }?.name ?: "?"
            "${name} (绑定: ${boundNetKeyName})"
        }
    }

    /**
     * 返回网络中下一个可用的单播地址
     */
    fun getNextAvailableAddress(): Int {
        val network = MeshState.meshNetWork ?: return 0x0001
        val provisioner = network.selectedProvisioner ?: return 0x0001
        return try {
            network.nextAvailableUnicastAddress(1, provisioner)
        } catch (e: Exception) {
            network.unicastAddress
        }
    }
    
    fun provisionDevice(node: UnprovisionedMeshNode, config: ProvisionConfig? = null) {
        MeshState.provisionConfig = config
        MeshState.currentUnprovisionedNode = node
        MeshState.isProvisioning.postValue(true)
        MeshState.provisioningStatus.postValue("正在连接到未配网设备...")
        Log.d("MeshApp", "开始配网设备: UUID=${node.deviceUuid}")

        // 应用用户配置
        if (config != null) {
            node.setNodeName(config.deviceName)
            Log.d("MeshApp", "  设备名称: ${config.deviceName}")
            Log.d("MeshApp", "  目标地址: 0x${config.unicastAddress.toString(16)}")
            Log.d("MeshApp", "  AppKey索引: ${config.appKeyIndex}")
        }

        // 从保存的扫描结果中找到对应的设备
        val scanResult = MeshState.unprovisionedScanResults[node.deviceUuid]
        MeshState.provisioningDevice = scanResult?.device
        MeshState.provisioningFinished = false
        if (scanResult == null) {
            Log.e("MeshApp", "未找到对应的 BLE 设备")
            MeshState.provisioningStatus.postValue("配网失败：未找到设备")
            MeshState.isProvisioning.postValue(false)
            MeshState.provisioningComplete.postValue(Event(Pair(false, 0)))
            return
        }

        // 连接到未配网设备
        MeshState.bleConnection.connect(scanResult.device, object : BleConnectionManager.ConnectionListener {
            override fun onConnected() {
                Log.d("MeshApp", "已连接到未配网设备")
                MeshState.provisioningStatus.postValue("已连接，正在发现服务...")
            }

            override fun onDisconnected() {
                Log.d("MeshApp", "未配网设备已断开")
                if (MeshState.provisioningFinished) {
                    Log.d("MeshApp", "配网已完成，忽略断开事件")
                    return
                }
                if (MeshState.isProvisioning.value == true) {
                    MeshState.provisioningStatus.postValue("配网失败：设备断开连接")
                    MeshState.isProvisioning.postValue(false)
                    MeshState.provisioningComplete.postValue(Event(Pair(false, 0)))
                }
            }

            override fun onServicesDiscovered() {
                Log.d("MeshApp", "服务发现完成，开始配网流程")
                MeshState.provisioningStatus.postValue("正在配网...")

                // 调试：检查网络和 Key
                val network = MeshState.meshNetWork
                if (network == null) {
                    Log.e("MeshApp", "配网失败：Mesh 网络未初始化")
                    MeshState.provisioningStatus.postValue("配网失败：网络未初始化")
                    MeshState.isProvisioning.postValue(false)
                    MeshState.provisioningComplete.postValue(Event(Pair(false, 0)))
                    return
                }

                Log.d("MeshApp", "=== 配网前检查 ===")
                Log.d("MeshApp", "网络名称: ${network.meshName}")
                Log.d("MeshApp", "NetKeys: ${network.netKeys.size}")
                Log.d("MeshApp", "AppKeys: ${network.appKeys.size}")

                if (network.netKeys.isEmpty()) {
                    Log.e("MeshApp", "配网失败：网络中没有 NetKey")
                    MeshState.provisioningStatus.postValue("配网失败：网络缺少 NetKey")
                    MeshState.isProvisioning.postValue(false)
                    MeshState.provisioningComplete.postValue(Event(Pair(false, 0)))
                    return
                }

                // 在 identifyNode 之前就应用用户配置的地址，确保库使用正确的地址配网
                val provConfig = MeshState.provisionConfig
                if (provConfig != null && provConfig.unicastAddress > 0) {
                    try {
                        Log.d("MeshApp", "预先设置自定义地址: 0x${provConfig.unicastAddress.toString(16)}")
                        network?.assignUnicastAddress(provConfig.unicastAddress)
                        Log.d("MeshApp", "✅ 地址预先设置成功")
                    } catch (e: Exception) {
                        Log.w("MeshApp", "地址预先设置失败: ${e.message}")
                    }
                }

                try {
                    Log.d("MeshApp", "调用 identifyNode: UUID=${node.deviceUuid}")
                    // MIUI 延迟：服务发现后等待 300ms 再开始配网，让 BLE 协议栈稳定
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.d("MeshApp", "执行 identifyNode (延迟后)")
                        MeshState.meshManagerApi.identifyNode(node.deviceUuid)
                    }, 300)
                } catch (e: Exception) {
                    Log.e("MeshApp", "配网失败: ${e.message}", e)
                    MeshState.provisioningStatus.postValue("配网失败: ${e.message}")
                    MeshState.isProvisioning.postValue(false)
                    MeshState.provisioningComplete.postValue(Event(Pair(false, 0)))
                }
            }

            override fun onDataReceived(data: ByteArray) {
                // 配网过程中接收到的数据传递给 MeshManagerApi
                val mtu = MeshState.bleConnection.mtuSize
                Log.d("MeshApp", "配网数据接收 - MTU: $mtu, 数据长度: ${data.size}")
                MeshState.meshManagerApi.handleNotifications(mtu, data)
            }

            override fun onDataSent(data: ByteArray) {
                // 数据发送成功后通知 MeshManagerApi
                val mtu = MeshState.bleConnection.mtuSize
                Log.d("MeshApp", "配网数据发送成功 - MTU: $mtu, 数据长度: ${data.size}")
                MeshState.meshManagerApi.handleWriteCallbacks(mtu, data)
            }

            override fun onMeshMessageReceived(src: Int, data: ByteArray) {}

            override fun onRssiRead(rssi: Int) {}

            override fun onError(error: String) {
                Log.e("MeshApp", "连接错误: $error")
                MeshState.provisioningStatus.postValue("配网失败：$error")
                MeshState.isProvisioning.postValue(false)
                MeshState.provisioningComplete.postValue(Event(Pair(false, 0)))
            }
        })
    }
    
    /**
     * 启动节点配置状态机
     * 步骤：ConfigCompositionDataGet → ConfigAppKeyAdd → ConfigModelAppBind × N → 完成
     * 每个步骤等待设备响应，超时则自动继续
     */
    private fun configureNode(address: Int) {
        // 防止重复调用（例如 descriptor 写入完成后重复触发）
        if (MeshState.configState != CONFIG_IDLE) {
            Log.d("MeshApp", "配置已在运行中，忽略重复调用 (state=${MeshState.configState})")
            return
        }
        val network = MeshState.meshNetWork ?: return
        Log.d("MeshApp", "===== 开始配置节点: 0x${address.toString(16)} =====")

        MeshState.configState = CONFIG_WAIT_COMPOSITION
        MeshState.configTargetAddress = address

        MeshState.statusText.postValue("正在获取设备信息...")
        MeshState.meshManagerApi.createMeshPdu(address, no.nordicsemi.android.mesh.transport.ConfigCompositionDataGet())

        // 超时后备：收不到 ConfigCompositionDataStatus 则 5 秒后继续
        setConfigTimeout(5000) { proceedAfterComposition(address) }
    }

    /**
     * 收到 ConfigCompositionDataStatus 或超时后继续
     * 发送 ConfigAppKeyAdd 添加应用密钥
     */
    private fun getSelectedAppKey(): no.nordicsemi.android.mesh.ApplicationKey? {
        val network = MeshState.meshNetWork ?: return null
        val config = MeshState.provisionConfig
        if (config != null && config.appKeyIndex >= 0 && config.appKeyIndex < network.appKeys.size) {
            return network.appKeys[config.appKeyIndex]
        }
        return network.appKeys.firstOrNull()
    }

    private fun proceedAfterComposition(address: Int) {
        if (MeshState.configState != CONFIG_WAIT_COMPOSITION) return
        val network = MeshState.meshNetWork ?: return
        val appKey = getSelectedAppKey() ?: return
        val netKey = network.netKeys.firstOrNull() ?: return

        // 详细日志：记录 ConfigAppKeyAdd 使用的密钥
        Log.d("MeshApp", "=== ConfigAppKeyAdd 密钥详情 ===")
        Log.d("MeshApp", "  NetKey index: ${netKey.keyIndex}")
        Log.d("MeshApp", "  NetKey bytes: ${netKey.key.joinToString("") { "%02X".format(it) }}")
        Log.d("MeshApp", "  AppKey index: ${appKey.keyIndex}")
        Log.d("MeshApp", "  AppKey bytes: ${appKey.key.joinToString("") { "%02X".format(it) }}")
        Log.d("MeshApp", "  AppKey boundNetKeyIndex: ${appKey.boundNetKeyIndex}")
        Log.d("MeshApp", "  AppKey AID: ${appKey.aid}")
        Log.d("MeshApp", "  目标地址: 0x${address.toString(16)}")
        Log.d("MeshApp", "==============================")

        MeshState.configState = CONFIG_WAIT_APPKEY
        MeshState.statusText.postValue("正在添加 AppKey...")
        MeshState.meshManagerApi.createMeshPdu(address, no.nordicsemi.android.mesh.transport.ConfigAppKeyAdd(netKey, appKey))

        // 超时后备：收不到 ConfigAppKeyStatus 则 5 秒后继续
        setConfigTimeout(5000) { proceedAfterAppKey(address) }
    }

    /**
     * 收到 ConfigAppKeyStatus 或超时后继续
     * 自动绑定常用模型（0x1000, 0x1002, 0x1100, 0x1200, 0x1206）使光感/时间/定时可用
     */
    private fun proceedAfterAppKey(address: Int) {
        if (MeshState.configState != CONFIG_WAIT_APPKEY) return
        val network = MeshState.meshNetWork ?: return
        val node = network.getNode(address) ?: run {
            Log.e("MeshApp", "节点 0x${address.toString(16)} 不在网络中")
            onConfigurationComplete(address)
            return
        }
        val appKey = getSelectedAppKey() ?: run {
            Log.e("MeshApp", "未找到 AppKey")
            onConfigurationComplete(address)
            return
        }
        MeshState.configAppKeyIndex = appKey.keyIndex

        MeshState.configState = CONFIG_BINDING

        // 构建绑定队列
        MeshState.configBindQueue.clear()
        var hasElements = false
        node.elements?.forEach { element ->
            element.value.meshModels?.forEach { (modelId, _) ->
                hasElements = true
                // 厂商模型的 key 是 (CID << 16) | ModelID，必须用完整 32 位 ID
                // 才能让 ConfigModelAppBind 按 Vendor Model App Bind 编码
                if (listOf(
                        0x1000, 0x1002, 0x1100, 0x1200, 0x1206, 0x1207,
                        RADAR_VENDOR_MODEL_KEY, DEVINFO_MODEL_KEY
                    ).contains(modelId)
                ) {
                    MeshState.configBindQueue.add(Pair(element.value.elementAddress, modelId))
                }
            }
        }

        if (MeshState.configBindQueue.isEmpty()) {
            if (hasElements) {
                Log.w("MeshApp", "节点元素中没有需要绑定的模型，配置完成")
                onConfigurationComplete(address)
                return
            }
            // Fallback: 如果未收到 ConfigCompositionDataStatus（MIUI 等兼容问题），
            // 直接绑定常用模型到主元素地址
            val primaryAddr = address
            Log.w("MeshApp", "节点元素列表为空，使用 fallback 绑定常用模型到 0x${primaryAddr.toString(16)}")
            val fallbackModels = listOf(0x1000, 0x1002, 0x1100, 0x1200, 0x1206, 0x1207)
            fallbackModels.forEach { modelId ->
                MeshState.configBindQueue.add(Pair(primaryAddr, modelId))
            }
            // 厂商模型：设备信息/密码在主元素，雷达厂商模型在 Element 4
            MeshState.configBindQueue.add(Pair(primaryAddr, DEVINFO_MODEL_KEY))
            MeshState.configBindQueue.add(Pair(primaryAddr + 4, RADAR_VENDOR_MODEL_KEY))
            Log.d("MeshApp", "Fallback 绑定 ${MeshState.configBindQueue.size} 个常用模型")
        } else if (hasElements) {
            // 保险：无论网络模型表里是否包含厂商模型，都显式绑一次
            // （主元素 = 主地址，雷达厂商模型 = 主地址 + 4）
            listOf(
                Pair(address, DEVINFO_MODEL_KEY),
                Pair(address + 4, RADAR_VENDOR_MODEL_KEY)
            ).forEach { binding ->
                if (!MeshState.configBindQueue.contains(binding)) {
                    MeshState.configBindQueue.add(binding)
                }
            }
        }

        MeshState.configBindIndex = 0
        Log.d("MeshApp", "需要绑定 ${MeshState.configBindQueue.size} 个模型")
        sendNextBind(address, appKey.keyIndex)
    }

    private fun sendNextBind(address: Int, appKeyIndex: Int) {
        if (MeshState.configBindIndex >= MeshState.configBindQueue.size) {
            Log.d("MeshApp", "所有模型绑定完成")
            onConfigurationComplete(address)
            return
        }

        val (elemAddr, modelId) = MeshState.configBindQueue[MeshState.configBindIndex]
        MeshState.statusText.postValue("绑定模型 ${MeshState.configBindIndex + 1}/${MeshState.configBindQueue.size} (0x${modelId.toString(16)})...")
        Log.d("MeshApp", "绑定: element=0x${elemAddr.toString(16)}, model=0x${modelId.toString(16)}, appKey=$appKeyIndex")

        try {
            MeshState.meshManagerApi.createMeshPdu(
                address,
                no.nordicsemi.android.mesh.transport.ConfigModelAppBind(elemAddr, modelId, appKeyIndex)
            )
        } catch (e: Exception) {
            Log.e("MeshApp", "绑定模型 0x${modelId.toString(16)} 失败: ${e.message}")
        }

        // 每个绑定等待 3 秒后自动继续
        setConfigTimeout(3000) {
            MeshState.configBindIndex++
            sendNextBind(address, appKeyIndex)
        }
    }

    /**
     * 设置配置超时
     * 取消之前的超时，设置新的超时回调
     */
    private fun setConfigTimeout(delayMs: Long, action: () -> Unit) {
        MeshState.configTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }
        MeshState.configTimeoutRunnable = Runnable {
            if (MeshState.configState != CONFIG_IDLE) {
                Log.d("MeshApp", "配置步骤超时，继续下一步...")
                action()
            }
        }
        MeshState.mainHandler.postDelayed(MeshState.configTimeoutRunnable!!, delayMs)
    }

    /**
     * 节点配置完成
     * 保存设备到本地、导出 Mesh 网络、通知 UI
     */
    private fun onConfigurationComplete(address: Int) {
        MeshState.configState = CONFIG_IDLE
        MeshState.configTimeoutRunnable?.let { MeshState.mainHandler.removeCallbacks(it) }

        Log.d("MeshApp", "===== 节点配置完成: 0x${address.toString(16)} =====")

        // 保存设备到本地
        saveDeviceToLocal(address)

        // 导出 Mesh 网络
        try {
            val json = MeshState.meshManagerApi.exportMeshNetwork()
            Log.d("MeshApp", "Mesh 网络已导出: ${json?.length} 字符")
        } catch (e: Exception) {
            Log.e("MeshApp", "导出 Mesh 网络失败: ${e.message}")
        }

        MeshState.isProvisioning.postValue(false)
        MeshState.provisioningComplete.postValue(Event(Pair(true, address)))
    }

    /**
     * 重新绑定 AppKey 到设备
     * 用于解决导入 JSON 后 AppKey 不匹配的问题
     *
     * @param address 设备地址
     */
    fun rebindAppKey(address: Int) {
        if (MeshState.isControlOnly) {
            MeshState.statusText.postValue("本机为控制端，不能修改设备配置")
            return
        }
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "网络未加载")
            MeshState.statusText.postValue("错误：网络未加载")
            return
        }

        val node = network.getNode(address) ?: run {
            Log.e("MeshApp", "未找到设备 0x${address.toString(16)}")
            MeshState.statusText.postValue("错误：未找到设备")
            return
        }

        val appKey = network.appKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 AppKey")
            MeshState.statusText.postValue("错误：未找到 AppKey")
            return
        }

        val netKey = network.netKeys.firstOrNull() ?: run {
            Log.e("MeshApp", "未找到 NetKey")
            MeshState.statusText.postValue("错误：未找到 NetKey")
            return
        }

        Log.d("MeshApp", "=== 开始重新绑定 AppKey ===")
        Log.d("MeshApp", "  设备地址: 0x${address.toString(16)}")
        Log.d("MeshApp", "  设备名称: ${node.nodeName}")
        Log.d("MeshApp", "  AppKey Index: ${appKey.keyIndex}")
        Log.d("MeshApp", "  AppKey: ${appKey.key.joinToString("") { "%02X".format(it) }}")

        MeshState.statusText.postValue("正在重新绑定 AppKey...")

        // 步骤 1: 添加 AppKey 到设备（如果设备已有，会忽略）
        try {
            MeshState.meshManagerApi.createMeshPdu(
                address,
                no.nordicsemi.android.mesh.transport.ConfigAppKeyAdd(netKey, appKey)
            )
            Log.d("MeshApp", "已发送 ConfigAppKeyAdd")
        } catch (e: Exception) {
            Log.e("MeshApp", "发送 ConfigAppKeyAdd 失败: ${e.message}")
            MeshState.statusText.postValue("添加 AppKey 失败: ${e.message}")
            return
        }

        // 步骤 2: 延迟 1 秒后绑定模型
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d("MeshApp", "开始绑定模型...")

            var bindCount = 0
            val sentVendorModels = mutableSetOf<Int>()
            node.elements?.forEach { element ->
                element.value.meshModels?.forEach { (modelId, model) ->
                    // 需要绑定的模型列表（厂商模型用 32 位完整 ID：CID<<16 | ModelID）
                    if (listOf(
                            0x1002, 0x1100, 0x1200, 0x1206, 0x1207,
                            RADAR_VENDOR_MODEL_KEY, DEVINFO_MODEL_KEY
                        ).contains(modelId)
                    ) {
                        try {
                            MeshState.meshManagerApi.createMeshPdu(
                                address,
                                no.nordicsemi.android.mesh.transport.ConfigModelAppBind(
                                    element.value.elementAddress,
                                    modelId,
                                    appKey.keyIndex
                                )
                            )
                            bindCount++
                            if (modelId == RADAR_VENDOR_MODEL_KEY || modelId == DEVINFO_MODEL_KEY) {
                                sentVendorModels.add(modelId)
                            }
                            Log.d("MeshApp", "  绑定模型 0x${modelId.toString(16)} (Element 0x${element.value.elementAddress.toString(16)})")
                        } catch (e: Exception) {
                            Log.e("MeshApp", "  绑定模型 0x${modelId.toString(16)} 失败: ${e.message}")
                        }
                    }
                }
            }

            // 保险：模型表里没有厂商模型时，按已知 Element 地址显式绑定
            listOf(
                Pair(address, DEVINFO_MODEL_KEY),
                Pair(address + 4, RADAR_VENDOR_MODEL_KEY)
            ).forEach { (elemAddr, modelId) ->
                if (!sentVendorModels.contains(modelId)) {
                    try {
                        MeshState.meshManagerApi.createMeshPdu(
                            address,
                            no.nordicsemi.android.mesh.transport.ConfigModelAppBind(
                                elemAddr,
                                modelId,
                                appKey.keyIndex
                            )
                        )
                        bindCount++
                        Log.d("MeshApp", "  绑定厂商模型 0x${modelId.toString(16)} (Element 0x${elemAddr.toString(16)})")
                    } catch (e: Exception) {
                        Log.e("MeshApp", "  绑定厂商模型 0x${modelId.toString(16)} 失败: ${e.message}")
                    }
                }
            }

            if (bindCount > 0) {
                Log.d("MeshApp", "已发送 $bindCount 个模型绑定命令")
                MeshState.statusText.postValue("已绑定 $bindCount 个模型，请稍候...")

                // 步骤 3: 延迟 2 秒后完成
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Log.d("MeshApp", "=== AppKey 重新绑定完成 ===")
                    MeshState.statusText.postValue("AppKey 重新绑定完成，请尝试控制设备")
                }, 2000)
            } else {
                Log.w("MeshApp", "没有找到需要绑定的模型")
                MeshState.statusText.postValue("警告：未找到需要绑定的模型")
            }
        }, 1000)
    }

    /**
     * 订阅设备的应用模型到分组地址
     * 遍历设备的所有 Element/Model，发送 ConfigModelSubscriptionAdd
     */
    fun subscribeDeviceToGroup(deviceAddress: Int, groupAddress: Int) {
        // 控制端没有 Device Key，不能发 Config 消息
        if (MeshState.isControlOnly) {
            MeshState.statusText.postValue("本机为控制端，不能修改分组")
            return
        }
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "Mesh 网络未初始化")
            return
        }
        val node = network.getNode(deviceAddress) ?: run {
            Log.e("MeshApp", "节点 0x${deviceAddress.toString(16)} 未找到")
            return
        }

        Log.d("MeshApp", "=== 订阅分组 ===")
        Log.d("MeshApp", "  设备地址: 0x${deviceAddress.toString(16)}")
        Log.d("MeshApp", "  分组地址: 0x${groupAddress.toString(16)}")

        MeshState.statusText.postValue("正在订阅分组 0x${groupAddress.toString(16)}...")

        var count = 0
        node.elements?.forEach { element ->
            element.value.meshModels?.forEach { (modelId, _) ->
                if (listOf(0x1002, 0x1100, 0x1200, 0x1206, 0x1207).contains(modelId)) {
                    try {
                        MeshState.meshManagerApi.createMeshPdu(
                            deviceAddress,
                            ConfigModelSubscriptionAdd(
                                element.value.elementAddress,
                                groupAddress,
                                modelId
                            )
                        )
                        count++
                        Log.d("MeshApp", "  订阅模型 0x${modelId.toString(16)} (Element 0x${element.value.elementAddress.toString(16)}) -> 0x${groupAddress.toString(16)}")
                    } catch (e: Exception) {
                        Log.e("MeshApp", "  订阅模型 0x${modelId.toString(16)} 失败: ${e.message}")
                    }
                }
            }
        }

        if (count > 0) {
            Log.d("MeshApp", "已发送 $count 个订阅命令")
            MeshState.statusText.postValue("已发送 $count 个订阅命令")
        } else {
            Log.w("MeshApp", "没有找到需要订阅的模型")
            MeshState.statusText.postValue("警告：未找到需要订阅的模型")
        }
    }

    /**
     * 取消订阅设备的应用模型到分组地址
     * 遍历设备的所有 Element/Model，发送 ConfigModelSubscriptionDelete
     */
    fun unsubscribeDeviceFromGroup(deviceAddress: Int, groupAddress: Int) {
        if (MeshState.isControlOnly) {
            MeshState.statusText.postValue("本机为控制端，不能修改分组")
            return
        }
        val network = MeshState.meshNetWork ?: return
        val node = network.getNode(deviceAddress) ?: return

        Log.d("MeshApp", "=== 取消订阅分组 ===")
        Log.d("MeshApp", "  设备地址: 0x${deviceAddress.toString(16)}")
        Log.d("MeshApp", "  分组地址: 0x${groupAddress.toString(16)}")

        var count = 0
        node.elements?.forEach { element ->
            element.value.meshModels?.forEach { (modelId, _) ->
                if (listOf(0x1002, 0x1100, 0x1200, 0x1206, 0x1207).contains(modelId)) {
                    try {
                        MeshState.meshManagerApi.createMeshPdu(
                            deviceAddress,
                            ConfigModelSubscriptionDelete(
                                element.value.elementAddress,
                                groupAddress,
                                modelId
                            )
                        )
                        count++
                    } catch (e: Exception) {
                        Log.e("MeshApp", "  取消订阅模型 0x${modelId.toString(16)} 失败: ${e.message}")
                    }
                }
            }
        }

        if (count > 0) {
            Log.d("MeshApp", "已发送 $count 个取消订阅命令")
        }
    }

    fun getCurrentRssi(): MutableLiveData<Int> = MeshState.currentRssi
    fun getAutoLightStatus(): MutableLiveData<Triple<Int, Int, Int>> = MeshState.autoLightStatusUpdates
    fun getAutoLightEnabled(): MutableLiveData<Pair<Int, Int>> = MeshState.autoLightEnabledUpdates
    fun getAutoLightBrightness(): MutableLiveData<Pair<Int, Int>> = MeshState.autoLightBrightnessUpdates
    fun getBuzzerChimeEnabled(): MutableLiveData<Pair<Int, Int>> = MeshState.buzzerChimeUpdates
    fun getBuzzerVolume(): MutableLiveData<Pair<Int, Int>> = MeshState.buzzerVolumeUpdates
    fun getRadarStatus(): MutableLiveData<Pair<Int, Int>> = MeshState.radarStatusUpdates
    fun getRadarNightDuration(): MutableLiveData<Pair<Int, Int>> = MeshState.radarNightDurationUpdates
    fun getRadarNightBrightness(): MutableLiveData<Pair<Int, Int>> = MeshState.radarNightBrightnessUpdates
    fun getRadarTimes(): MutableLiveData<Pair<Int, List<Pair<Int, Int>>>> = MeshState.radarTimesUpdates
    fun getRadarNightHours(): MutableLiveData<Pair<Int, Pair<Int, Int>>> = MeshState.radarNightHoursUpdates
    fun getRadarSound(): MutableLiveData<Pair<Int, Pair<Int, Int>>> = MeshState.radarSoundUpdates
    fun getPasswordStatus(): MutableLiveData<Pair<Int, Int>> = MeshState.passwordStatusUpdates
    fun getPasswordOpResult(): MutableLiveData<Event<Triple<Int, Int, Int>>> = MeshState.passwordOpResult
    fun getFirmwareVersionUpdates(): MutableLiveData<Pair<Int, String>> = MeshState.firmwareVersionUpdates
    fun getWorkMode(): MutableLiveData<Pair<Int, Int>> = MeshState.workModeUpdates
    fun isControlOnly(): Boolean = MeshState.isControlOnly

    /**
     * 发送 Node Reset 命令，清除设备的配网信息
     * 设备会恢复到未配网状态，需要重新配网才能使用
     *
     * 如果当前没有 Proxy 连接，会自动连接到设备 MAC 地址再发送。
     */
    fun resetNode(address: Int) {
        val network = MeshState.meshNetWork ?: run {
            Log.e("MeshApp", "网络未加载")
            MeshState.statusText.postValue("错误：网络未加载")
            return
        }

        val node = network.getNode(address)
        Log.d("MeshApp", "=== 发送 Node Reset ===")
        Log.d("MeshApp", "  设备地址: 0x${address.toString(16)}")
        Log.d("MeshApp", "  设备名称: ${node?.nodeName ?: "未知"}")

        MeshState.statusText.postValue("正在清除设备配网信息...")
        MeshState.legacyResetAddress = address

        if (MeshState.bleConnection.isConnected()) {
            // 已有 Proxy 连接，直接发送（即使连接的是其他 Proxy，消息也会经 Mesh 网络转发）
            sendNodeReset(address)
        } else {
            // 无连接，自动获取设备 MAC 并连接后发送
            connectToDeviceThen(address) { sendNodeReset(address) }
        }
    }

    /**
     * 连接到指定设备的 Proxy 后执行 action（用于无连接时发送命令）
     */
    private fun connectToDeviceThen(address: Int, action: () -> Unit) {
        val mac = getDeviceMacFromPrefs(address)
        if (mac == null) {
            MeshState.statusText.postValue("错误：未找到设备 MAC 地址，请先连接设备")
            Log.e("MeshApp", "未保存设备 0x${address.toString(16)} 的 MAC 地址")
            return
        }

        MeshState.statusText.postValue("正在连接 $mac...")
        MeshState.bleConnection.connect(mac, object : BleConnectionManager.ConnectionListener {
            override fun onConnected() {
                Log.d("MeshApp", "已连接，等待 Proxy 服务")
                MeshState.statusText.postValue("已连接，正在发现 Proxy 服务...")
            }

            override fun onDisconnected() {
                Log.w("MeshApp", "连接断开")
            }

            override fun onServicesDiscovered() {
                if (MeshState.bleConnection.isUsingProxyService()) {
                    Log.d("MeshApp", "Proxy 服务已就绪")
                    MeshState.isConnected.postValue(true)
                    MeshState.connectedDeviceAddress.postValue(mac)
                    action()
                } else {
                    Log.e("MeshApp", "未找到 Proxy Service (0x1828)")
                    MeshState.statusText.postValue("错误：设备不支持 Mesh Proxy")
                }
            }

            override fun onDataReceived(data: ByteArray) {
                MeshState.meshManagerApi.handleNotifications(MeshState.bleConnection.mtuSize, data)
            }

            override fun onDataSent(data: ByteArray) {}

            override fun onMeshMessageReceived(src: Int, data: ByteArray) {}

            override fun onRssiRead(rssi: Int) {}

            override fun onError(error: String) {
                Log.e("MeshApp", "连接失败: $error")
                MeshState.statusText.postValue("连接失败：$error")
            }
        })
    }

    /**
     * 实际执行 Node Reset 的 Mesh PDU 发送
     */
    private fun sendNodeReset(address: Int) {
        try {
            MeshState.meshManagerApi.createMeshPdu(
                address,
                no.nordicsemi.android.mesh.transport.ConfigNodeReset()
            )
            Log.d("MeshApp", "ConfigNodeReset 已发送")
            MeshState.statusText.postValue("已发送清除命令，等待设备响应...")
        } catch (e: Exception) {
            Log.e("MeshApp", "发送 Node Reset 失败: ${e.message}")
            MeshState.statusText.postValue("发送清除命令失败: ${e.message}")
        }
    }

    /**
     * 请求设备密码状态（是否改过密码），结果通过 passwordStatusUpdates 返回
     */
    fun sendPasswordGet(address: Int) {
        sendPasswordCommand(address, OP_PWD_GET, PWD_OP_GET, null)
    }

    /**
     * 查询设备固件版本（设备信息厂商模型 0x0003）
     * 结果通过 firmwareVersionUpdates 返回
     */
    fun sendDeviceInfoGet(address: Int) {
        try {
            val network = MeshState.meshNetWork ?: run {
                Log.w("MeshApp", "查询固件版本失败：网络未加载")
                return
            }
            val appKey = network.appKeys.firstOrNull() ?: run {
                Log.w("MeshApp", "查询固件版本失败：未找到 App Key")
                return
            }
            val msg = VendorModelMessageUnacked(appKey, DEVINFO_MODEL_ID, DEVINFO_CID, OP_DEV_INFO_GET, null)
            MeshState.meshManagerApi.createMeshPdu(address, msg)
            Log.d("MeshApp", "查询固件版本已发送: addr=0x${address.toString(16)}")
        } catch (e: Exception) {
            Log.e("MeshApp", "查询固件版本失败: ${e.message}")
        }
    }

    /**
     * 读取工作模式（设备信息厂商模型 0x0003）
     * 结果通过 workModeUpdates 返回
     */
    fun readWorkMode(address: Int) {
        try {
            val network = MeshState.meshNetWork ?: run {
                Log.w("MeshApp", "读取工作模式失败：网络未加载")
                return
            }
            val appKey = network.appKeys.firstOrNull() ?: run {
                Log.w("MeshApp", "读取工作模式失败：未找到 App Key")
                return
            }
            val msg = VendorModelMessageUnacked(appKey, DEVINFO_MODEL_ID, DEVINFO_CID, OP_WORK_MODE_GET, null)
            MeshState.meshManagerApi.createMeshPdu(address, msg)
            Log.d("MeshApp", "读取工作模式已发送: addr=0x${address.toString(16)}")
        } catch (e: Exception) {
            Log.e("MeshApp", "读取工作模式失败: ${e.message}")
        }
    }

    /**
     * 设置工作模式（雷达/定时/光敏互斥）
     * @param mode 0=关闭, 1=雷达, 2=定时, 3=光敏
     */
    fun sendWorkMode(address: Int, mode: Int) {
        try {
            val network = MeshState.meshNetWork ?: run {
                Log.w("MeshApp", "设置工作模式失败：网络未加载")
                return
            }
            val appKey = network.appKeys.firstOrNull() ?: run {
                Log.w("MeshApp", "设置工作模式失败：未找到 App Key")
                return
            }
            val m = mode.coerceIn(WORK_MODE_OFF, WORK_MODE_AUTO_LIGHT)
            val msg = VendorModelMessageUnacked(
                appKey, DEVINFO_MODEL_ID, DEVINFO_CID,
                OP_WORK_MODE_SET, byteArrayOf(m.toByte())
            )
            MeshState.meshManagerApi.createMeshPdu(address, msg)
            val label = when (m) {
                WORK_MODE_RADAR -> "雷达"
                WORK_MODE_TIMER -> "定时"
                WORK_MODE_AUTO_LIGHT -> "光敏"
                else -> "关闭"
            }
            Log.d("MeshApp", "设置工作模式: mode=$m($label) to 0x${address.toString(16)}")
            MeshState.statusText.postValue("工作模式: $label")
        } catch (e: Exception) {
            Log.e("MeshApp", "设置工作模式失败: ${e.message}")
            MeshState.statusText.postValue("设置工作模式失败: ${e.message}")
        }
    }

    /**
     * 带密码清除配网（真正的恢复出厂）。成功后设备会退出网络，
     * App 收到 PWD_STATUS(rc=0) 后再删除本地设备。
     */
    fun resetNodeWithPassword(address: Int, password: String) {
        if (!isValidPassword(password)) {
            MeshState.passwordOpResult.postValue(Event(Triple(address, PWD_OP_RESET, PWD_RC_FORMAT)))
            return
        }
        if (MeshState.isControlOnly) {
            MeshState.statusText.postValue("本机为控制端，不能清除配网")
            MeshState.passwordOpResult.postValue(Event(Triple(address, PWD_OP_RESET, PWD_RC_FORBIDDEN)))
            return
        }

        val params = password.toByteArray(Charsets.US_ASCII)
        if (MeshState.bleConnection.isConnected()) {
            sendPasswordCommand(address, OP_PWD_RESET, PWD_OP_RESET, params)
        } else {
            connectToDeviceThen(address) {
                sendPasswordCommand(address, OP_PWD_RESET, PWD_OP_RESET, params)
            }
        }
    }

    /**
     * 设置/修改设备密码（需要旧密码）。返回 false 表示参数格式不合法。
     */
    fun setDevicePassword(address: Int, oldPwd: String, newPwd: String): Boolean {
        if (MeshState.isControlOnly) {
            return false
        }
        if (!isValidPassword(oldPwd) || !isValidPassword(newPwd)) {
            return false
        }
        val params = oldPwd.toByteArray(Charsets.US_ASCII) + newPwd.toByteArray(Charsets.US_ASCII)
        sendPasswordCommand(address, OP_PWD_SET, PWD_OP_SET, params)
        return true
    }

    /**
     * 发送密码命令（统一处理 pending 与超时）
     * @param op PWD_OP_GET / PWD_OP_SET / PWD_OP_RESET
     */
    private fun sendPasswordCommand(address: Int, opcode: Int, op: Int, params: ByteArray?) {
        val network = MeshState.meshNetWork ?: run {
            MeshState.statusText.postValue("错误：网络未加载")
            MeshState.passwordOpResult.postValue(Event(Triple(address, op, PWD_RC_SEND_FAIL)))
            return
        }
        val appKey = network.appKeys.firstOrNull() ?: run {
            MeshState.statusText.postValue("错误：未找到 App Key")
            MeshState.passwordOpResult.postValue(Event(Triple(address, op, PWD_RC_SEND_FAIL)))
            return
        }

        MeshState.pendingPwdTimeout?.let { MeshState.mainHandler.removeCallbacks(it) }
        MeshState.pendingPwdOp = op
        MeshState.pendingPwdAddress = address
        val timeout = Runnable {
            if (MeshState.pendingPwdOp == op && MeshState.pendingPwdAddress == address) {
                MeshState.pendingPwdOp = PWD_OP_NONE
                MeshState.pendingPwdTimeout = null
                MeshState.passwordOpResult.postValue(Event(Triple(address, op, PWD_RC_TIMEOUT)))
            }
        }
        MeshState.pendingPwdTimeout = timeout
        MeshState.mainHandler.postDelayed(timeout, PWD_TIMEOUT_MS)

        try {
            val msg = VendorModelMessageUnacked(appKey, DEVINFO_MODEL_ID, DEVINFO_CID, opcode, params)
            MeshState.meshManagerApi.createMeshPdu(address, msg)
            Log.d("MeshApp", "密码命令已发送: op=$op, opcode=0x${opcode.toString(16)}, addr=0x${address.toString(16)}")
        } catch (e: Exception) {
            MeshState.mainHandler.removeCallbacks(timeout)
            MeshState.pendingPwdTimeout = null
            MeshState.pendingPwdOp = PWD_OP_NONE
            Log.e("MeshApp", "发送密码命令失败: ${e.message}")
            MeshState.passwordOpResult.postValue(Event(Triple(address, op, PWD_RC_SEND_FAIL)))
        }
    }

    /**
     * 设置中继转发参数
     * @param address 设备单播地址
     * @param relay 中继状态 (0=禁用, 1=启用, 2=不支持)
     * @param retransmitCount 重发次数 (0-7，实际次数 = 值+1)
     * @param retransmitIntervalSteps 重发间隔步长 (0-31，实际间隔 = (步长+1)*10ms)
     */
    fun setRelayConfig(address: Int, relay: Int, retransmitCount: Int, retransmitIntervalSteps: Int) {
        if (MeshState.isControlOnly) {
            MeshState.statusText.postValue("本机为控制端，不能修改设备配置")
            return
        }
        try {
            MeshState.meshManagerApi.createMeshPdu(address, ConfigRelaySet(relay, retransmitCount, retransmitIntervalSteps))
            Log.d("MeshApp", "ConfigRelaySet: relay=$relay, count=$retransmitCount, interval=$retransmitIntervalSteps")
            MeshState.statusText.postValue("中继转发设置已发送")
        } catch (e: Exception) {
            Log.e("MeshApp", "设置中继转发失败: ${e.message}")
            MeshState.statusText.postValue("设置失败: ${e.message}")
        }
    }

    /**
     * 设置网络发送参数（设备发出消息的重发次数）
     * @param address 设备单播地址
     * @param transmitCount 发送次数 (0-7，实际次数 = 值+1)
     * @param intervalSteps 发送间隔步长 (0-31，实际间隔 = (步长+1)*10ms)
     */
    fun setNetworkTransmit(address: Int, transmitCount: Int, intervalSteps: Int) {
        if (MeshState.isControlOnly) {
            MeshState.statusText.postValue("本机为控制端，不能修改设备配置")
            return
        }
        try {
            MeshState.meshManagerApi.createMeshPdu(address, ConfigNetworkTransmitSet(transmitCount, intervalSteps))
            Log.d("MeshApp", "ConfigNetworkTransmitSet: count=$transmitCount, interval=$intervalSteps")
            MeshState.statusText.postValue("网络发送设置已发送")
        } catch (e: Exception) {
            Log.e("MeshApp", "设置网络发送失败: ${e.message}")
            MeshState.statusText.postValue("设置失败: ${e.message}")
        }
    }

    /**
     * 解析设备信息回包： [type(2B LE), major, minor, patch, build]
     */
    private fun parseDeviceInfoStatus(src: Int, data: ByteArray?) {
        if (data == null || data.size < 6) {
            Log.w("MeshApp", "设备信息回包长度异常: ${data?.size ?: 0}")
            return
        }
        val typeId = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val version = "${data[2].toInt() and 0xFF}.${data[3].toInt() and 0xFF}." +
                "${data[4].toInt() and 0xFF}+${data[5].toInt() and 0xFF}"
        Log.d("MeshApp", "设备信息: type=0x${typeId.toString(16)}, 固件版本=v$version (src=0x${src.toString(16)})")
        MeshState.firmwareVersionUpdates.postValue(Pair(src, version))
    }

    /**
     * 解析设备密码状态回包： [rc(1B), is_set(1B)]
     */
    private fun parsePasswordStatus(src: Int, data: ByteArray?) {
        if (data == null || data.size < 2) return
        val rc = data[0].toInt() and 0xFF
        val isSet = data[1].toInt() and 0xFF
        Log.d("MeshApp", "设备密码状态: rc=$rc, is_set=$isSet (src=0x${src.toString(16)})")
        MeshState.passwordStatusUpdates.postValue(Pair(src, isSet))

        val op = MeshState.pendingPwdOp
        if (op != PWD_OP_NONE && MeshState.pendingPwdAddress == src) {
            MeshState.pendingPwdTimeout?.let { MeshState.mainHandler.removeCallbacks(it) }
            MeshState.pendingPwdTimeout = null
            MeshState.pendingPwdOp = PWD_OP_NONE
            MeshState.passwordOpResult.postValue(Event(Triple(src, op, rc)))
        }
    }

    /**
     * 解析工作模式状态回包： [mode(1B)]
     * mode: 0=关闭, 1=雷达, 2=定时, 3=光敏
     */
    private fun parseWorkModeStatus(src: Int, data: ByteArray?) {
        if (data == null || data.isEmpty()) {
            Log.w("MeshApp", "工作模式回包长度异常")
            return
        }
        var mode = data[0].toInt() and 0xFF
        if (mode > WORK_MODE_AUTO_LIGHT) mode = WORK_MODE_OFF
        Log.d("MeshApp", "工作模式: mode=$mode (src=0x${src.toString(16)})")
        MeshState.workModeUpdates.postValue(Pair(src, mode))
    }

    /**
     * 收到设备消息时标记设备为在线
     */
    private fun markDeviceOnline(src: Int) {
        try {
            MeshState.lastMessageTime[src] = System.currentTimeMillis()
            val repo = DeviceRepository(getApplication())
            val devices = repo.getAllDevices()
            val device = devices.find { it.address == src }
            if (device != null && !device.isOnline) {
                device.isOnline = true
                repo.updateDevice(device)
                Log.d("MeshApp", "设备 0x${src.toString(16)} 标记为在线")
                MeshState.deviceOnlineUpdates.postValue(Unit)
            }
        } catch (e: Exception) {
            Log.e("MeshApp", "标记设备在线失败: ${e.message}")
        }
    }

    /**
     * 定期检查设备在线状态，超时未收到消息的标记为离线
     */
    private fun checkDevicesOnlineStatus() {
        try {
            val repo = DeviceRepository(getApplication())
            val devices = repo.getAllDevices()
            if (devices.isEmpty()) return
            val now = System.currentTimeMillis()
            var changed = false

            for (device in devices) {
                if (!device.isOnline) continue
                val lastTime = MeshState.lastMessageTime[device.address]
                if (lastTime == null || now - lastTime > ONLINE_TIMEOUT_MS) {
                    device.isOnline = false
                    repo.updateDevice(device)
                    MeshState.lastMessageTime.remove(device.address)
                    changed = true
                    Log.d("MeshApp", "设备 0x${device.address.toString(16)} 离线（超时 ${(now - (lastTime ?: 0)) / 1000}s）")
                }
            }

            if (changed) {
                MeshState.deviceOnlineUpdates.postValue(Unit)
            }
        } catch (e: Exception) {
            Log.e("MeshApp", "检查在线状态失败: ${e.message}")
        }
    }

    /**
     * 从 SharedPreferences 读取设备对应的 BLE MAC 地址
     * 与 DeviceDetailActivity.getDeviceMac() 使用相同的 key
     */
    private fun getDeviceMacFromPrefs(address: Int): String? {
        val prefs = getApplication<Application>().getSharedPreferences("DevicePrefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("device_mac_0x${address.toString(16)}", null)
    }

    companion object {
        const val CONFIG_IDLE = 0
        const val CONFIG_WAIT_COMPOSITION = 1
        const val CONFIG_WAIT_APPKEY = 2
        const val CONFIG_BINDING = 3
        const val ONLINE_TIMEOUT_MS = 300_000L       // 5 分钟无消息标记离线
        const val ONLINE_CHECK_INTERVAL_MS = 30_000L  // 每 30 秒检查一次

        // Vendor model constants (雷达)
        const val RADAR_CID = 0x07D7
        const val RADAR_VENDOR_MODEL_ID = 0x0002
        const val OP_RADAR_TIMES_GET = 0x01
        const val OP_RADAR_TIMES_STATUS = 0x02
        const val OP_RADAR_NIGHT_HOURS_GET = 0x03
        const val OP_RADAR_NIGHT_HOURS_SET = 0x04
        const val OP_RADAR_SOUND_GET = 0x05
        const val OP_RADAR_SOUND_SET = 0x06

        // Vendor model constants (设备信息 / 密码) —— 模型 0x0003
        // 注意：nRF Mesh 库对厂商消息使用 6-bit 字段（发送时自动补 0xC0 + CID），
        // 所以这里必须是 0x10..0x13，而不是完整 24-bit 值。
        const val DEVINFO_CID = 0x07D7
        const val DEVINFO_MODEL_ID = 0x0003

        // 厂商模型在 nRF Mesh 库中的完整 ID：(CID << 16) | ModelID
        // 只有用完整 ID 调 ConfigModelAppBind，库才会按 Vendor Model App Bind 编码
        const val RADAR_VENDOR_MODEL_KEY = 0x07D70002
        const val DEVINFO_MODEL_KEY = 0x07D70003
        const val OP_PWD_GET = 0x10
        const val OP_PWD_SET = 0x11
        const val OP_PWD_RESET = 0x12
        const val OP_PWD_STATUS = 0x13

        // 工作模式（同一模型 0x0003）：[mode(1B)]
        const val OP_WORK_MODE_GET = 0x20
        const val OP_WORK_MODE_SET = 0x21
        const val OP_WORK_MODE_STATUS = 0x22

        // 工作模式取值（与固件 work_mode_t 一致）
        const val WORK_MODE_OFF = 0
        const val WORK_MODE_RADAR = 1
        const val WORK_MODE_TIMER = 2
        const val WORK_MODE_AUTO_LIGHT = 3

        // 设备信息（软件版本）：[type(2B LE), major, minor, patch, build]
        // 注意 0x01/0x02 与雷达模型的 GET/STATUS 相同，接收时必须先用
        // VendorModelMessageStatus.modelIdentifier 区分模型（0x0003 vs 0x0002）
        const val OP_DEV_INFO_GET = 0x01
        const val OP_DEV_INFO_STATUS = 0x02

        // 密码操作类型
        const val PWD_OP_NONE = 0
        const val PWD_OP_GET = 1
        const val PWD_OP_SET = 2
        const val PWD_OP_RESET = 3

        // 结果码（与固件一致；0xFD 起为 App 本地错误）
        const val PWD_RC_OK = 0
        const val PWD_RC_WRONG = 1
        const val PWD_RC_FORMAT = 2
        const val PWD_RC_LOCKED = 3
        const val PWD_RC_FORBIDDEN = 0xFD
        const val PWD_RC_SEND_FAIL = 0xFE
        const val PWD_RC_TIMEOUT = 0xFF

        const val PWD_TIMEOUT_MS = 5000L

        // 本机角色持久化
        const val ROLE_PREFS = "MeshRolePrefs"
        const val ROLE_KEY_CONTROL_ONLY = "is_control_only"

        /** 4 位数字/字母（与固件校验一致） */
        fun isValidPassword(s: String): Boolean {
            if (s.length != 4) return false
            return s.all { c ->
                (c in '0'..'9') || (c in 'A'..'Z') || (c in 'a'..'z')
            }
        }

        fun mapBrightnessForOC6701(uiBrightness: Int): Int =
            com.example.ble_device_mesh.data.BrightnessMap.map(uiBrightness)

        /**
         * 反向映射：设备里保存的亮度（已做 OC6701 补偿）→ UI 百分比。
         */
        fun unmapBrightnessForOC6701(deviceBrightness: Int): Int =
            com.example.ble_device_mesh.data.BrightnessMap.unmap(deviceBrightness)

        /**
         * Mesh Level（int16, -32768..32767）→ 百分比 0-100。
         * 与固件 mesh_level_to_percent 的线性映射保持一致：-32768 → 0%，32767 → 100%。
         */
        fun meshLevelToPercent(meshLevel: Int): Int {
            val clamped = meshLevel.coerceIn(-32768, 32767)
            return (((clamped + 32768).toLong() * 100L) / 65535L).toInt().coerceIn(0, 100)
        }
    }
}