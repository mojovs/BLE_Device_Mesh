package com.example.ble_device_mesh

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Observer
import com.example.ble_device_mesh.data.DeviceRepository
import com.example.ble_device_mesh.data.MeshDevice
import no.nordicsemi.android.mesh.MeshTAITime
import no.nordicsemi.android.mesh.transport.TimeGet
import no.nordicsemi.android.mesh.transport.TimeSet
import kotlin.math.abs

/**
 * 时间同步引擎。
 *
 * 一次同步的流程：
 *   1. （必要时）临时连接一台 proxy 节点
 *   2. 串行给每台设备发 TimeSet，间隔 300ms（规避 MIUI 写特征值不回调）
 *   3. 等设备的 TimeStatus 回执（固件收到 TimeSet 会立即回一条）
 *   4. 没回执的设备补发 TimeGet 复检
 *   5. 逐台比对偏差，汇总结果写通知
 *
 * 整条链路都不需要界面：进程被闹钟唤醒时用 [HeadlessMeshSession]，
 * App 已连接时直接复用现有连接。
 */
object TimeSyncEngine {

    private const val TAG = "MeshTimeSync"

    /** 设备串行发送间隔 */
    private const val SEND_INTERVAL_MS = 300L
    /** 全部发完后等待回执的时间 */
    private const val STATUS_WAIT_MS = 2_500L
    /** 补发 TimeGet 的间隔 */
    private const val VERIFY_INTERVAL_MS = 300L
    /** 补发后等待回执的时间 */
    private const val VERIFY_WAIT_MS = 1_800L
    /** 复检允许的最大偏差（秒） */
    private const val DEVIATION_TOLERANCE_SEC = 5L
    /** 单次任务总超时 */
    private const val JOB_TIMEOUT_MS = 90_000L
    /** 后台直连最多尝试几个 proxy（每个 10s 超时，避免把任务耗光） */
    private const val MAX_PROXY_ATTEMPTS = 4

    private const val TAI_EPOCH_OFFSET = 946_684_800L
    private const val TAI_UTC_DELTA = 37L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isRunning: Boolean = false
        private set

    // ---------------------------------------------------------------- 对外入口

    /**
     * 执行一次时间同步。
     * @param onDone 结束回调（一定会被调用一次，已经切到主线程）
     */
    fun run(context: Context, reason: String, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        mainHandler.post {
            if (isRunning) {
                Log.d(TAG, "已有同步任务在运行，跳过本次（$reason）")
                onDone?.invoke()
                return@post
            }
            isRunning = true
            TimeSyncNotifier.ensureChannel(app)
            SyncJob(app, reason, onDone).start()
        }
    }

    /** 距上次成功同步超过 1 小时才跑（App 连接成功后调用这条） */
    fun runIfStale(context: Context, reason: String) {
        if (!TimeSyncScheduler.isStale(context)) {
            Log.d(TAG, "距上次成功同步不足 1 小时，跳过补同步")
            return
        }
        run(context, reason)
    }

    // ---------------------------------------------------------------- 时间换算

    /** 当前 TAI 秒（Mesh Time 模型纪元：2000-01-01） */
    fun currentTaiSeconds(): Int =
        ((System.currentTimeMillis() / 1000) - TAI_EPOCH_OFFSET + TAI_UTC_DELTA).toInt()

    /** TAI 秒 → Unix 秒 */
    fun taiToUnix(taiSeconds: Long): Long = taiSeconds + TAI_EPOCH_OFFSET - TAI_UTC_DELTA

    // ---------------------------------------------------------------- 任务

    private class SyncJob(
        private val context: Context,
        private val reason: String,
        private val onDone: (() -> Unit)?
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val repository = DeviceRepository(context)

        private var devices: List<MeshDevice> = emptyList()
        /** address → 本次下发的 TAI 秒 */
        private val expectedTai = mutableMapOf<Int, Int>()
        /** address → 设备回执的 Unix 秒 */
        private val reportedUnix = mutableMapOf<Int, Long>()

        private var headless: HeadlessMeshSession? = null
        private var reusingConnection = false
        private var statusObserver: Observer<Pair<Int, Long>>? = null

        private var sendIndex = 0
        private var verifyIndex = 0
        private var verifyList: List<MeshDevice> = emptyList()
        private var finished = false
        /** 权限等环境告警，失败时附加到提示里便于排查 */
        private var permissionWarning: String? = null

        private val startedAt = System.currentTimeMillis()

        fun start() {
            devices = repository.getAllDevices()
            if (devices.isEmpty()) {
                Log.d(TAG, "没有设备需要同步")
                TimeSyncNotifier.notifyResult(context, "时间同步", "没有设备需要同步", keep = false)
                finish()
                return
            }

            if (!hasBluetoothPermission()) {
                // 部分国产 ROM（如 MIUI）即使系统里显示未授予，BLE 仍可正常使用，
                // 所以这里不做硬拦截，只记录并继续尝试；真的连不上会走超时/失败分支。
                Log.w(TAG, "系统未授予 BLUETOOTH_CONNECT（MIUI 上仍可能可连），继续尝试")
                permissionWarning = "系统未授予蓝牙权限"
            }
            if (!isBluetoothEnabled()) {
                fail("蓝牙未开启，无法同步")
                return
            }

            // 总超时保护：任何一步卡住都能收尾
            handler.postDelayed(watchdog, JOB_TIMEOUT_MS)

            if (MeshViewModel.MeshState.isInitialized && MeshViewModel.MeshState.isConnected.value == true) {
                reusingConnection = true
                attachStatusObserver()
                Log.d(TAG, "复用已连接的 Proxy，开始同步 ${devices.size} 台设备")
                beginSend()
            } else {
                startHeadless()
            }
        }

        // ------------------------------------------------------------ 连接

        private fun startHeadless() {
            val prefs = context.getSharedPreferences("MeshPrefs", Context.MODE_PRIVATE)
            val last = prefs.getString("last_proxy_address", null)
            val history = prefs.getStringSet("proxy_address_history", emptySet())?.toList() ?: emptyList()
            val macs = (listOfNotNull(last) + history).distinct().take(MAX_PROXY_ATTEMPTS)
            Log.d(TAG, "建立临时连接，候选 Proxy: $macs")

            val session = HeadlessMeshSession(context)
            headless = session
            session.onTimeStatus = { src, unix -> onTimeStatus(src, unix) }
            session.start(
                proxyMacs = macs,
                onReady = {
                    if (!finished) {
                        Log.d(TAG, "临时连接就绪，开始同步 ${devices.size} 台设备")
                        beginSend()
                    }
                },
                onFail = { message -> fail(message) }
            )
        }

        private fun attachStatusObserver() {
            val observer = Observer<Pair<Int, Long>> { pair ->
                onTimeStatus(pair.first, pair.second)
            }
            statusObserver = observer
            MeshViewModel.MeshState.timeUpdates.observeForever(observer)
        }

        // ------------------------------------------------------------ 下发

        private fun beginSend() {
            if (finished) return
            Log.d(TAG, "开始同步 ${devices.size} 台设备的时间")
            sendIndex = 0
            handler.post(sendNext)
        }

        private val sendNext = object : Runnable {
            override fun run() {
                if (finished) return
                if (sendIndex >= devices.size) {
                    handler.postDelayed({ startVerify() }, STATUS_WAIT_MS)
                    return
                }

                val device = devices[sendIndex++]
                val tai = TimeSyncEngine.currentTaiSeconds()
                expectedTai[device.address] = tai

                val ok = sendTimeSet(device.address, tai)
                Log.d(
                    TAG,
                    "TimeSet → ${device.name}(0x${device.address.toString(16)}) " +
                        "tai=$tai ${if (ok) "已发送" else "发送失败"}"
                )
                handler.postDelayed(this, SEND_INTERVAL_MS)
            }
        }

        // ------------------------------------------------------------ 复检

        private fun startVerify() {
            if (finished) return
            verifyList = devices.filter { it.address !in reportedUnix }
            if (verifyList.isEmpty()) {
                finishSummary()
                return
            }
            Log.d(TAG, "复检：${verifyList.size} 台设备没有回执，补发 TimeGet")
            verifyIndex = 0
            handler.post(verifyNext)
        }

        private val verifyNext = object : Runnable {
            override fun run() {
                if (finished) return
                if (verifyIndex >= verifyList.size) {
                    handler.postDelayed({ finishSummary() }, VERIFY_WAIT_MS)
                    return
                }
                val device = verifyList[verifyIndex++]
                sendTimeGet(device.address)
                handler.postDelayed(this, VERIFY_INTERVAL_MS)
            }
        }

        // ------------------------------------------------------------ 汇总

        private fun finishSummary() {
            if (finished) return

            val okNames = mutableListOf<String>()
            val failNames = mutableListOf<String>()
            val details = mutableListOf<String>()

            devices.forEach { device ->
                val tai = expectedTai[device.address]
                val expectedUnix = tai?.let { TimeSyncEngine.taiToUnix(it.toLong()) }
                val reported = reportedUnix[device.address]

                when {
                    reported == null -> {
                        failNames += device.name
                        details += "${device.name}(0x${device.address.toString(16)}) 无响应"
                    }
                    expectedUnix != null && abs(reported - expectedUnix) > DEVIATION_TOLERANCE_SEC -> {
                        failNames += device.name
                        details += "${device.name}(0x${device.address.toString(16)}) 偏差 ${reported - expectedUnix}s"
                    }
                    else -> {
                        okNames += device.name
                        device.deviceTime = reported
                        repository.updateDevice(device)
                    }
                }
            }

            val total = devices.size
            val success = okNames.size
            val stamp = TimeSyncScheduler.formatTime(startedAt)

            if (failNames.isEmpty()) {
                val text = "全部 $total 台设备时间已校准（$stamp）"
                Log.d(TAG, "同步完成：$text")
                TimeSyncNotifier.notifyResult(context, "时间同步完成", text, keep = false)
                TimeSyncScheduler.saveSuccess(context, text)
            } else {
                val title = "时间同步完成（$success/$total）"
                val text = "未完成：${failNames.joinToString("、")}"
                Log.w(TAG, "同步完成但有异常：$text；明细：${details.joinToString("；")}")
                TimeSyncNotifier.notifyResult(context, title, text, keep = true)
                TimeSyncScheduler.saveFailure(context, "$stamp $title · $text")
            }

            closeTransport()
            finish()
        }

        private fun fail(message: String) {
            if (finished) return
            val text = permissionWarning?.let { "$message（$it）" } ?: message
            Log.e(TAG, "同步失败：$text")
            TimeSyncNotifier.notifyResult(context, "时间同步失败", text, keep = true)
            TimeSyncScheduler.saveFailure(
                context,
                "${TimeSyncScheduler.formatTime(System.currentTimeMillis())} 失败 · $text"
            )
            closeTransport()
            finish()
        }

        // ------------------------------------------------------------ 发送

        private fun sendTimeSet(address: Int, taiSeconds: Int): Boolean {
            headless?.let { return it.sendTimeSet(address, taiSeconds) }

            val network = MeshViewModel.MeshState.meshNetWork ?: return false
            val appKey = network.appKeys.firstOrNull() ?: return false
            return try {
                val taiTime = MeshTAITime(taiSeconds, 0, 0, false, 37, 32)
                MeshViewModel.MeshState.meshManagerApi.createMeshPdu(address, TimeSet(appKey, taiTime))
                true
            } catch (e: Exception) {
                Log.e(TAG, "发送 TimeSet 到 0x${address.toString(16)} 失败: ${e.message}")
                false
            }
        }

        private fun sendTimeGet(address: Int): Boolean {
            headless?.let { return it.sendTimeGet(address) }

            val network = MeshViewModel.MeshState.meshNetWork ?: return false
            val appKey = network.appKeys.firstOrNull() ?: return false
            return try {
                MeshViewModel.MeshState.meshManagerApi.createMeshPdu(address, TimeGet(appKey))
                true
            } catch (e: Exception) {
                Log.e(TAG, "发送 TimeGet 到 0x${address.toString(16)} 失败: ${e.message}")
                false
            }
        }

        // ------------------------------------------------------------ 回执

        private fun onTimeStatus(src: Int, unixSeconds: Long) {
            // 只接受本次任务窗口内、并且我们已经下发过时间的设备
            if (!expectedTai.containsKey(src)) return
            reportedUnix[src] = unixSeconds
            Log.d(TAG, "TimeStatus ← 0x${src.toString(16)} unix=$unixSeconds")
        }

        // ------------------------------------------------------------ 收尾

        private val watchdog = Runnable {
            if (finished) return@Runnable
            Log.w(TAG, "同步任务超时（${JOB_TIMEOUT_MS / 1000}s），强制收尾")
            fail("同步超时，请检查手机与 Proxy 的距离")
        }

        private fun closeTransport() {
            headless?.close()
            headless = null
            statusObserver?.let {
                try {
                    MeshViewModel.MeshState.timeUpdates.removeObserver(it)
                } catch (e: Exception) {
                    Log.w(TAG, "移除时间观察者失败: ${e.message}")
                }
            }
            statusObserver = null
            // 复用现有连接的情况不能断开，界面还要继续用
            if (reusingConnection) {
                Log.d(TAG, "保留已有连接（复用了界面的 Proxy 连接）")
            }
        }

        private fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            TimeSyncEngine.isRunning = false
            onDone?.invoke()
        }

        // ------------------------------------------------------------ 环境检查

        private fun hasBluetoothPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                // Android 11 及以下用的是旧版蓝牙权限，BLUETOOTH_CONNECT 在这类系统上不存在
                context.checkSelfPermission(Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }

        private fun isBluetoothEnabled(): Boolean {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter?.isEnabled == true
        }
    }
}
