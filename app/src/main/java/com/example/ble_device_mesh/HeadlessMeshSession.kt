package com.example.ble_device_mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import no.nordicsemi.android.mesh.ApplicationKey
import no.nordicsemi.android.mesh.MeshManagerApi
import no.nordicsemi.android.mesh.MeshManagerCallbacks
import no.nordicsemi.android.mesh.MeshNetwork
import no.nordicsemi.android.mesh.MeshStatusCallbacks
import no.nordicsemi.android.mesh.MeshTAITime
import no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode
import no.nordicsemi.android.mesh.transport.ControlMessage
import no.nordicsemi.android.mesh.transport.MeshMessage
import no.nordicsemi.android.mesh.transport.TimeGet
import no.nordicsemi.android.mesh.transport.TimeSet
import no.nordicsemi.android.mesh.transport.TimeStatus

/**
 * 无界面（headless）Mesh 会话。
 *
 * 用于 App 进程刚被闹钟唤醒、界面没有启动过的场景：
 * 自己建一套 [MeshManagerApi] + [BleConnectionManager]，从本地数据库加载网络，
 * 直接连接已知的 proxy MAC（不扫描，规避后台扫描限流），发完指令后断开。
 *
 * 注意：只在 [MeshViewModel.MeshState] 尚未连接时使用，避免和界面那套连接打架。
 */
class HeadlessMeshSession(private val context: Context) {

    companion object {
        private const val TAG = "MeshTimeSync"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        /** 服务就绪后再留一点余量才写特征值（MIUI 上服务刚发现完立刻写容易失败） */
        private const val READY_SETTLE_MS = 300L
        private const val TAI_EPOCH_OFFSET = 946_684_800L
        private const val TAI_UTC_DELTA = 37L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ble = BleConnectionManager(context)

    private var api: MeshManagerApi? = null
    private var network: MeshNetwork? = null
    private var appKey: ApplicationKey? = null

    private var proxyMacs: List<String> = emptyList()
    private var macIndex = 0
    private var connectTimeout: Runnable? = null
    private var closed = false
    private var readyNotified = false

    /** 收到设备上报的时间：(src, unix 秒) */
    var onTimeStatus: ((Int, Long) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onFail: ((String) -> Unit)? = null

    // ---------------------------------------------------------------- 生命周期

    fun start(proxyMacs: List<String>, onReady: () -> Unit, onFail: (String) -> Unit) {
        this.proxyMacs = proxyMacs
        this.onReady = onReady
        this.onFail = onFail

        if (proxyMacs.isEmpty()) {
            fail("没有历史 Proxy 记录，请先打开 App 连接一次")
            return
        }

        val manager = MeshManagerApi(context)
        api = manager
        manager.setMeshManagerCallbacks(managerCallbacks)
        manager.setMeshStatusCallbacks(statusCallbacks)
        Log.d(TAG, "headless: 加载本地 Mesh 网络")
        manager.loadMeshNetwork()
    }

    fun close() {
        if (closed) return
        closed = true
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
        try {
            ble.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "断开临时连接失败: ${e.message}")
        }
        Log.d(TAG, "headless: 会话已关闭")
    }

    // ---------------------------------------------------------------- 发送

    fun sendTimeSet(address: Int, taiSeconds: Int): Boolean {
        val key = appKey ?: return false
        val manager = api ?: return false
        val taiTime = MeshTAITime(taiSeconds, 0, 0, false, 37, 32)
        return try {
            manager.createMeshPdu(address, TimeSet(key, taiTime))
            true
        } catch (e: Exception) {
            Log.e(TAG, "headless: 发送 TimeSet 到 0x${address.toString(16)} 失败: ${e.message}")
            false
        }
    }

    fun sendTimeGet(address: Int): Boolean {
        val key = appKey ?: return false
        val manager = api ?: return false
        return try {
            manager.createMeshPdu(address, TimeGet(key))
            true
        } catch (e: Exception) {
            Log.e(TAG, "headless: 发送 TimeGet 到 0x${address.toString(16)} 失败: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------- 网络回调

    private val managerCallbacks = object : MeshManagerCallbacks {
        override fun onNetworkLoaded(meshNetwork: MeshNetwork?) {
            if (closed) return
            if (meshNetwork == null) {
                fail("本地没有 Mesh 网络数据")
                return
            }
            network = meshNetwork
            applyProvisionerAddress(meshNetwork)
            ProvisionerSeq.ensureForward(context, meshNetwork, TAG)
            appKey = meshNetwork.appKeys.firstOrNull()
            if (appKey == null) {
                fail("网络中没有 AppKey")
                return
            }
            Log.d(TAG, "headless: 网络已加载 name=${meshNetwork.meshName}, appKey=${appKey?.keyIndex}")
            connectNext()
        }

        override fun onNetworkUpdated(meshNetwork: MeshNetwork?) {
            network = meshNetwork
        }

        override fun onNetworkLoadFailed(error: String?) {
            fail("加载 Mesh 网络失败: ${error ?: "未知错误"}")
        }

        override fun onNetworkImported(meshNetwork: MeshNetwork?) {}

        override fun onNetworkImportFailed(error: String?) {}

        override fun sendProvisioningPdu(meshNode: UnprovisionedMeshNode?, pdu: ByteArray?) {}

        override fun onMeshPduCreated(pdu: ByteArray?) {
            if (pdu == null || closed) return
            val manager = api ?: return
            if (!ble.isConnected()) {
                Log.w(TAG, "headless: 未连接，丢弃 PDU")
                return
            }
            ble.sendData(pdu, forceReliable = false)
            // 与 MeshViewModel 一致：无论 sendData 返回什么，都推进库的写回调状态机
            manager.handleWriteCallbacks(ble.mtuSize, pdu)
        }

        override fun getMtu(): Int = ble.mtuSize
    }

    private val statusCallbacks = object : MeshStatusCallbacks {
        override fun onTransactionFailed(dst: Int, hasIncompleteTimerExpired: Boolean) {
            Log.w(TAG, "headless: 事务失败 dst=0x${dst.toString(16)} timeout=$hasIncompleteTimerExpired")
        }

        override fun onUnknownPduReceived(src: Int, accessPayload: ByteArray?) {}

        override fun onBlockAcknowledgementProcessed(dst: Int, message: ControlMessage) {}

        override fun onBlockAcknowledgementReceived(src: Int, message: ControlMessage) {}

        override fun onHeartbeatMessageReceived(src: Int, message: ControlMessage) {}

        override fun onMeshMessageProcessed(dst: Int, meshMessage: MeshMessage) {}

        override fun onMeshMessageReceived(src: Int, meshMessage: MeshMessage) {
            if (meshMessage is TimeStatus) {
                val tai = meshMessage.taiSeconds ?: return
                val unix = tai.toLong() + TAI_EPOCH_OFFSET - TAI_UTC_DELTA
                Log.d(TAG, "headless: TimeStatus src=0x${src.toString(16)} unix=$unix")
                onTimeStatus?.invoke(src, unix)
            }
        }

        override fun onMessageDecryptionFailed(meshLayer: String?, errorMessage: String?) {
            Log.w(TAG, "headless: 解密失败 layer=$meshLayer err=$errorMessage")
        }
    }

    // ---------------------------------------------------------------- 连接

    private fun connectNext() {
        if (closed) return
        if (macIndex >= proxyMacs.size) {
            fail("无法连接任何 Proxy 节点（手机可能不在附近）")
            return
        }

        val mac = proxyMacs[macIndex]
        Log.d(TAG, "headless: 连接 Proxy $mac (${macIndex + 1}/${proxyMacs.size})")

        connectTimeout?.let { handler.removeCallbacks(it) }
        val timeout = Runnable {
            if (closed) return@Runnable
            Log.w(TAG, "headless: 连接 $mac 超时")
            ble.disconnect()
            macIndex++
            connectNext()
        }
        connectTimeout = timeout
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)

        ble.connect(mac, connectionListener)
    }

    private val connectionListener = object : BleConnectionManager.ConnectionListener {
        override fun onConnected() {
            Log.d(TAG, "headless: GATT 已连接，等待服务发现")
        }

        override fun onDisconnected() {
            Log.d(TAG, "headless: GATT 已断开")
        }

        override fun onServicesDiscovered() {
            if (closed || readyNotified) return
            readyNotified = true
            connectTimeout?.let { handler.removeCallbacks(it) }
            connectTimeout = null
            Log.d(TAG, "headless: Proxy 服务就绪 (mtu=${ble.mtuSize})")
            handler.postDelayed({
                if (!closed) onReady?.invoke()
            }, READY_SETTLE_MS)
        }

        override fun onDataReceived(data: ByteArray) {
            api?.handleNotifications(ble.mtuSize, data)
        }

        override fun onDataSent(data: ByteArray) {}

        override fun onMeshMessageReceived(src: Int, data: ByteArray) {}

        override fun onRssiRead(rssi: Int) {}

        override fun onError(error: String) {
            Log.w(TAG, "headless: 连接错误 $error")
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 把网络的 Provisioner 地址改成这台手机上用的地址。
     * 导入网络后 App 会基于 Android ID 生成地址，但库加载的是 DB 里的原始值，
     * 不覆盖的话源地址会对不上。
     */
    private fun applyProvisionerAddress(meshNetwork: MeshNetwork) {
        try {
            val provisioner = meshNetwork.selectedProvisioner ?: return
            val prefs = context.getSharedPreferences("mesh_config", Context.MODE_PRIVATE)
            val saved = prefs.getInt("provisioner_address", -1)
            val target = if (saved > 0) saved else (provisioner.provisionerAddress ?: return)
            provisioner.setProvisionerAddress(target)
            meshNetwork.getNode(provisioner.provisionerUuid)?.setUnicastAddress(target)
            Log.d(TAG, "headless: Provisioner 地址 0x${target.toString(16)}")
        } catch (e: Exception) {
            Log.w(TAG, "headless: 设置 Provisioner 地址失败: ${e.message}")
        }
    }

    private fun fail(message: String) {
        if (closed) return
        Log.e(TAG, "headless: $message")
        closed = true
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
        try {
            ble.disconnect()
        } catch (_: Exception) {
        }
        onFail?.invoke(message)
    }
}
