package com.example.ble_device_mesh

import android.bluetooth.le.ScanResult
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.ble_device_mesh.data.DeviceRepository
import com.example.ble_device_mesh.data.DeviceType
import com.example.ble_device_mesh.data.MeshDevice

/**
 * 设备地址 / MAC 扫描器。
 *
 * 用途：设备卡片丢了、备份里也没有这个节点时，靠这个页面把设备找回来。
 *
 * 原理：
 *  - Mesh 控制只需要 NetKey + AppKey + 单播地址，不需要设备密钥（DevKey）。
 *  - 逐个单播地址发 GenericOnOffGet，谁用我们的 AppKey 回包，源地址就是活着的节点地址。
 *  - 单播地址是配网时分配的，和 BLE MAC 没有换算关系，只能靠「闪一下」用眼睛确认是哪盏灯。
 */
class DeviceFinderActivity : AppCompatActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private lateinit var deviceRepository: DeviceRepository
    private val handler = Handler(Looper.getMainLooper())

    // 扫描结果：地址 -> 第一次收到回包的时间
    private val foundAddresses = linkedMapOf<Int, Long>()
    private var addressScanStart = 0L
    private var isScanningAddress = false

    // MAC 扫描结果：MAC -> ScanResult（保留最强信号）
    private val foundMacs = linkedMapOf<String, ScanResult>()
    private var isScanningMac = false
    private var pendingMac: String? = null

    private lateinit var etAddrFrom: EditText
    private lateinit var etAddrTo: EditText
    private lateinit var tvAddrProgress: TextView
    private lateinit var llAddrResults: LinearLayout
    private lateinit var btnScanAddress: Button
    private lateinit var btnStopScanAddress: Button

    private lateinit var tvMacStatus: TextView
    private lateinit var llMacResults: LinearLayout
    private lateinit var btnScanMac: Button
    private lateinit var btnStopScanMac: Button

    private val pollRunnable = object : Runnable {
        override fun run() {
            collectAddressReplies()
            if (isScanningAddress) handler.postDelayed(this, 500)
        }
    }

    private val macStopRunnable = Runnable { stopMacScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_finder)
        deviceRepository = DeviceRepository(this)

        etAddrFrom = findViewById(R.id.etAddrFrom)
        etAddrTo = findViewById(R.id.etAddrTo)
        tvAddrProgress = findViewById(R.id.tvAddrProgress)
        llAddrResults = findViewById(R.id.llAddrResults)
        btnScanAddress = findViewById(R.id.btnScanAddress)
        btnStopScanAddress = findViewById(R.id.btnStopScanAddress)

        tvMacStatus = findViewById(R.id.tvMacStatus)
        llMacResults = findViewById(R.id.llMacResults)
        btnScanMac = findViewById(R.id.btnScanMac)
        btnStopScanMac = findViewById(R.id.btnStopScanMac)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        btnScanAddress.setOnClickListener { startAddressScan() }
        btnStopScanAddress.setOnClickListener { stopAddressScan("已停止") }

        btnScanMac.setOnClickListener { startMacScan() }
        btnStopScanMac.setOnClickListener { stopMacScan() }

        // 预填上一次用过的地址范围
        val prefs = getSharedPreferences("DeviceFinder", MODE_PRIVATE)
        etAddrFrom.setText(prefs.getString("from", "0001"))
        etAddrTo.setText(prefs.getString("to", "00FF"))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAddressScan(null)
        stopMacScan()
        handler.removeCallbacksAndMessages(null)
    }

    // ==================== Mesh 地址扫描 ====================

    private fun startAddressScan() {
        val from = parseHex(etAddrFrom.text.toString())
        val to = parseHex(etAddrTo.text.toString())
        if (from == null || to == null || from < 1 || to > 0xFFFF || from > to) {
            Toast.makeText(this, "地址范围不对（十六进制，如 0001 ~ 00FF）", Toast.LENGTH_SHORT).show()
            return
        }
        if (!MeshViewModel.MeshState.isInitialized) {
            Toast.makeText(this, "Mesh 还没初始化，请先回首页", Toast.LENGTH_SHORT).show()
            return
        }
        if (!MeshViewModel.MeshState.bleConnection.isConnected()) {
            Toast.makeText(this, "未连接 Proxy，无法扫描。请先回首页连接一台灯", Toast.LENGTH_LONG).show()
            return
        }

        getSharedPreferences("DeviceFinder", MODE_PRIVATE).edit()
            .putString("from", etAddrFrom.text.toString())
            .putString("to", etAddrTo.text.toString())
            .apply()

        foundAddresses.clear()
        renderAddresses()
        addressScanStart = System.currentTimeMillis()

        val started = viewModel.startAddressScan(
            from, to,
            onProgress = { scanned, total ->
                tvAddrProgress.text = "已扫描 $scanned/$total · 发现 ${foundAddresses.size} 个在线"
            },
            onDone = {
                isScanningAddress = false
                collectAddressReplies()
                tvAddrProgress.text =
                    "扫描完成 · 发现 ${foundAddresses.size} 个在线节点（共 ${to - from + 1} 个地址）"
            }
        )
        if (!started) {
            Toast.makeText(this, "无法开始扫描：请确认已连接 Proxy", Toast.LENGTH_SHORT).show()
            return
        }

        isScanningAddress = true
        btnScanAddress.isEnabled = false
        tvAddrProgress.text = "正在扫描 ${to - from + 1} 个地址…"
        handler.postDelayed(pollRunnable, 400)
    }

    private fun stopAddressScan(message: String?) {
        viewModel.stopAddressScan()
        isScanningAddress = false
        handler.removeCallbacks(pollRunnable)
        btnScanAddress.isEnabled = true
        if (message != null) tvAddrProgress.text = message
    }

    /** 从 MeshState 里挑出本次扫描期间回包的源地址 */
    private fun collectAddressReplies() {
        val snapshot = try {
            HashMap(MeshViewModel.MeshState.lastMessageTime)
        } catch (e: Exception) {
            return
        }
        var changed = false
        for ((addr, time) in snapshot) {
            if (time >= addressScanStart && addr !in foundAddresses) {
                foundAddresses[addr] = time
                changed = true
            }
        }
        if (changed) renderAddresses()
    }

    private fun renderAddresses() {
        llAddrResults.removeAllViews()
        if (foundAddresses.isEmpty()) return

        val known = deviceRepository.getAllDevices().associateBy { it.address }
        for (addr in foundAddresses.keys.sorted()) {
            val device = known[addr]

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }

            val label = TextView(this).apply {
                // 已在设备卡片里的直接显示名称，不在的标未知设备
                val name = device?.name ?: "未知设备"
                text = "0x${"%02X".format(addr)}  $name"
                textSize = 14f
                setTextColor(if (device != null) 0xFF111827.toInt() else 0xFFD97706.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)

            val btnBlink = Button(this).apply {
                text = "闪一下"
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(34))
                setOnClickListener { viewModel.blinkAddress(addr) }
            }
            row.addView(btnBlink)

            if (device == null) {
                val btnAdd = Button(this).apply {
                    text = "加为设备"
                    textSize = 12f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(10), 0, dp(10), 0)
                    layoutParams = LinearLayout.LayoutParams(dp(80), dp(34)).apply {
                        marginStart = dp(6)
                    }
                    setOnClickListener { showAddDeviceDialog(addr) }
                }
                row.addView(btnAdd)
            }

            llAddrResults.addView(row)
        }
    }

    // ==================== BLE MAC 扫描 ====================

    private fun startMacScan() {
        if (!MeshViewModel.MeshState.isInitialized) {
            Toast.makeText(this, "Mesh 还没初始化，请先回首页", Toast.LENGTH_SHORT).show()
            return
        }
        foundMacs.clear()
        pendingMac = null
        renderMacs()
        tvMacStatus.text = "扫描中…"
        isScanningMac = true
        btnScanMac.isEnabled = false

        try {
            MeshViewModel.MeshState.bleScanner.startScan(object : BleScannerManager.ScanListener {
                override fun onDeviceFound(device: ScanResult) {
                    val mac = try {
                        device.device.address
                    } catch (e: Exception) {
                        null
                    } ?: return
                    handler.post {
                        val old = foundMacs[mac]
                        if (old == null || device.rssi > old.rssi) {
                            foundMacs[mac] = device
                            renderMacs()
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        tvMacStatus.text = if (errorCode == -1) "扫描失败：缺少蓝牙权限" else "扫描失败：$errorCode"
                        stopMacScan()
                    }
                }
            })
        } catch (e: Exception) {
            tvMacStatus.text = "扫描失败：${e.message}"
            stopMacScan()
            return
        }

        handler.postDelayed(macStopRunnable, 12000)
    }

    private fun stopMacScan() {
        handler.removeCallbacks(macStopRunnable)
        if (!isScanningMac) return
        isScanningMac = false
        btnScanMac.isEnabled = true
        try {
            MeshViewModel.MeshState.bleScanner.stopScan()
        } catch (_: Exception) {
        }
        tvMacStatus.text = "扫描结束 · 发现 ${foundMacs.size} 个节点"
    }

    private fun renderMacs() {
        llMacResults.removeAllViews()
        if (foundMacs.isEmpty()) return

        val macToDevice = HashMap<String, MeshDevice>()
        val devicePrefs = getSharedPreferences("DevicePrefs", MODE_PRIVATE)
        for (device in deviceRepository.getAllDevices()) {
            // 卡片里存的 MAC
            device.bluetoothMac?.takeIf { it.isNotBlank() }?.let {
                macToDevice[it.uppercase()] = device
            }
            // 历史 MAC 记录（DevicePrefs）也认
            devicePrefs.getString("device_mac_0x${device.address.toString(16)}", null)
                ?.takeIf { it.isNotBlank() }
                ?.let { macToDevice[it.uppercase()] = device }
        }

        val sorted = foundMacs.entries.sortedByDescending { it.value.rssi }
        for ((mac, result) in sorted) {
            val device = macToDevice[mac.uppercase()]
            // 已在设备卡片里的用卡片名称，不在的标未知设备
            val name = device?.name ?: "未知设备"

            val tv = TextView(this).apply {
                val line1 = name + if (device != null) "  (0x${"%02X".format(device.address)})" else ""
                text = "$line1\n$mac   ${result.rssi} dBm"
                textSize = 13f
                setTextColor(if (device != null) 0xFF111827.toInt() else 0xFFD97706.toInt())
                setPadding(dp(4), dp(8), dp(4), dp(8))
                if (mac == pendingMac) setBackgroundColor(0xFFFFE0B2.toInt())
                setOnClickListener {
                    pendingMac = mac
                    renderMacs()
                    Toast.makeText(
                        this@DeviceFinderActivity,
                        "已选中 $mac，点地址列表的「加为设备」会自动带上",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            llMacResults.addView(tv)
        }
    }

    // ==================== 加为设备 ====================

    private fun showAddDeviceDialog(address: Int) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val etName = EditText(this).apply {
            hint = "设备名称"
            setText("设备${"%02X".format(address)}")
            selectAll()
        }
        val etMac = EditText(this).apply {
            hint = "BLE MAC（可留空）"
            setText(pendingMac ?: "")
            textSize = 13f
        }
        val tip = TextView(this).apply {
            text = "地址 0x${"%02X".format(address)}\n填了 MAC，之后手机就能自动连它当 Proxy。"
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, dp(8), 0, 0)
        }

        box.addView(etName)
        box.addView(etMac)
        box.addView(tip)

        AlertDialog.Builder(this)
            .setTitle("加为设备")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim().ifBlank { "设备${"%02X".format(address)}" }
                val mac = etMac.text.toString().trim().uppercase()
                val id = "mesh_$address"
                deviceRepository.addDevice(
                    MeshDevice(
                        id = id,
                        name = name,
                        address = address,
                        type = DeviceType.LIGHT,
                        brightness = 0,
                        bluetoothMac = mac.ifBlank { null }
                    )
                )
                deviceRepository.removeFromRecycleBin(id)
                if (mac.isNotBlank()) {
                    getSharedPreferences("DevicePrefs", MODE_PRIVATE)
                        .edit()
                        .putString("device_mac_0x${address.toString(16)}", mac)
                        .apply()
                }
                Toast.makeText(this, "已添加 $name", Toast.LENGTH_SHORT).show()
                renderAddresses()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 工具 ====================

    private fun parseHex(text: String): Int? {
        val cleaned = text.trim().removePrefix("0x").removePrefix("0X")
        if (cleaned.isEmpty()) return null
        return try {
            cleaned.toInt(16)
        } catch (e: Exception) {
            null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
