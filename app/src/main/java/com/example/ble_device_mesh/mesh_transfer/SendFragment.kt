package com.example.ble_device_mesh.mesh_transfer

import android.app.AlertDialog
import android.bluetooth.le.ScanResult
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.ble_device_mesh.MeshViewModel
import com.example.ble_device_mesh.R
import org.json.JSONObject
import javax.crypto.spec.SecretKeySpec

class SendFragment : Fragment() {

    private val viewModel: MeshViewModel get() = (requireActivity() as TransferActivity).getMeshViewModel()

    private lateinit var bleManager: BleDiscoveryManager
    private var wifiDirectManager: WifiDirectManager? = null

    private lateinit var tvNetworkInfo: TextView
    private lateinit var tvNetworkDetail: TextView
    private lateinit var btnStartSend: Button
    private lateinit var tvSearchStatus: TextView
    private lateinit var layoutPeers: LinearLayout
    private lateinit var layoutPair: View
    private lateinit var tvPairCode: TextView
    private lateinit var btnConfirmPair: Button
    private lateinit var btnCancelPair: Button
    private lateinit var layoutProgress: View
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvProgressStatus: TextView

    private val foundDevices = LinkedHashMap<String, ScanResult>()
    private var pairCode = ""
    private var salt = ByteArray(0)
    private var sessionKey: SecretKeySpec? = null
    private var peerConfirmed = false
    private var localConfirmed = false
    private var payloadJson: String? = null
    private var activeTransfer = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_send, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvNetworkInfo = view.findViewById(R.id.tvNetworkInfo)
        tvNetworkDetail = view.findViewById(R.id.tvNetworkDetail)
        btnStartSend = view.findViewById(R.id.btnStartSend)
        tvSearchStatus = view.findViewById(R.id.tvSearchStatus)
        layoutPeers = view.findViewById(R.id.layoutPeers)
        layoutPair = view.findViewById(R.id.layoutPair)
        tvPairCode = view.findViewById(R.id.tvPairCode)
        btnConfirmPair = view.findViewById(R.id.btnConfirmPair)
        btnCancelPair = view.findViewById(R.id.btnCancelPair)
        layoutProgress = view.findViewById(R.id.layoutProgress)
        progressBar = view.findViewById(R.id.progressBar)
        tvProgress = view.findViewById(R.id.tvProgress)
        tvProgressStatus = view.findViewById(R.id.tvProgressStatus)

        showConfigInfo()

        btnStartSend.setOnClickListener {
            val activity = activity
            if (activity is TransferActivity) {
                activity.requestTransferPermissions { onStartSend() }
            } else {
                onStartSend()
            }
        }
        btnConfirmPair.setOnClickListener { onConfirmPair() }
        btnCancelPair.setOnClickListener { sendRejectAndReset() }
    }

    private fun showConfigInfo() {
        val json = viewModel.exportMeshNetworkWithDevices()
        payloadJson = json
        if (json == null) {
            tvNetworkInfo.text = "未加载配置"
            tvNetworkDetail.text = "请先在设置中导入或创建 Mesh 网络配置"
        } else {
            val (name, count) = parseConfigInfo(json)
            tvNetworkInfo.text = if (name.isEmpty()) "Mesh 网络" else name
            tvNetworkDetail.text = "包含设备: $count 个"
        }
    }

    private fun parseConfigInfo(json: String): Pair<String, Int> {
        return try {
            val root = JSONObject(json)
            var count = 0
            var name = ""
            if (root.has("devices")) {
                count = root.optJSONArray("devices")?.length() ?: 0
            }
            if (root.has("meshNetwork")) {
                try {
                    val mesh = JSONObject(root.getString("meshNetwork"))
                    name = mesh.optString("meshName", "")
                } catch (_: Exception) {
                }
            }
            name to count
        } catch (_: Exception) {
            "Mesh 网络" to 0
        }
    }

    private fun onStartSend() {
        if (payloadJson == null) {
            Toast.makeText(requireContext(), "未加载 Mesh 配置，无法发送", Toast.LENGTH_SHORT).show()
            return
        }
        resetUi()
        bleManager = BleDiscoveryManager(requireContext())
        bleManager.startScanning(sendListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            if (::bleManager.isInitialized) bleManager.stopScan()
        } catch (_: Exception) {
        }
    }

    private val sendListener = object : BleDiscoveryManager.Listener {
        override fun onDeviceFound(device: ScanResult) {
            val name = device.scanRecord?.deviceName ?: device.device.name ?: "未知设备"
            addPeerToUi(name, device)
        }

        override fun onScanFailed(message: String) {
            onUi { Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show() }
        }

        override fun onConnected() {
            onUi { sendPairCode() }
        }

        override fun onMessage(message: String) {
            handleMessage(message)
        }

        override fun onAdvertiseFailed(message: String) {}
        override fun onServerFailed(message: String) {}

        override fun onDisconnected() {
            onUi {
                if (activeTransfer) {
                    onError("传输连接已断开")
                }
            }
        }

        override fun onLog(message: String) {
            onUi { tvSearchStatus.text = message }
        }

        override fun onError(message: String) {
            onUi { onError(message) }
        }
    }

    private fun addPeerToUi(name: String, result: ScanResult) {
        val address = result.device.address ?: return
        if (foundDevices.containsKey(address)) return
        foundDevices[address] = result
        onUi {
            tvSearchStatus.visibility = View.VISIBLE
            layoutPeers.visibility = View.VISIBLE
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val nameTv = TextView(requireContext()).apply {
                text = name
                textSize = 15f
                setTextColor(requireContext().getColor(android.R.color.black))
            }
            nameTv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val sendBtn = Button(requireContext()).apply {
                text = "发送"
                setTextColor(android.graphics.Color.WHITE)
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.primary))
            }
            row.addView(nameTv)
            row.addView(sendBtn)
            sendBtn.setOnClickListener { askRoleThenSelectPeer(result) }
            layoutPeers.addView(row)
        }
    }

    /**
     * 选择共享权限后重新生成 payload：
     * - 仅控制：剥离所有节点的 Device Key，对方只能开关灯/调光
     * - 管理员：包含 Device Key，对方可清除配网/改配置
     */
    private fun askRoleThenSelectPeer(result: ScanResult) {
        // 控制端本机没有 Device Key，只能分享“仅控制”
        if (viewModel.isControlOnly()) {
            startSelectPeer(result, controlOnly = true)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("共享权限")
            .setMessage("选择对方手机对设备的权限。\n\n仅控制：可正常开关灯、调光，但不能清除配网。\n管理员：拥有全部权限，可清除配网。")
            .setPositiveButton("仅控制（推荐）") { _, _ ->
                startSelectPeer(result, controlOnly = true)
            }
            .setNeutralButton("管理员") { _, _ ->
                startSelectPeer(result, controlOnly = false)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startSelectPeer(result: ScanResult, controlOnly: Boolean) {
        val json = viewModel.exportMeshNetworkWithDevices(controlOnly)
        if (json == null) {
            Toast.makeText(requireContext(), "导出配置失败", Toast.LENGTH_SHORT).show()
            return
        }
        payloadJson = json
        onSelectPeer(result)
    }

    private fun onSelectPeer(result: ScanResult) {
        bleManager.stopScan()
        tvSearchStatus.text = "连接中..."
        bleManager.connectClient(result.device, sendListener)
    }

    private fun sendPairCode() {
        try {
            pairCode = CryptoManager.generatePairCode()
            salt = CryptoManager.generateSalt()
            sessionKey = CryptoManager.deriveSessionKey(pairCode, salt)
            peerConfirmed = false
            localConfirmed = false
            val msg = JSONObject()
                .put("type", "pair")
                .put("name", Build.MODEL)
                .put("code", pairCode)
                .put("salt", CryptoManager.base64Encode(salt))
                .toString()
            bleManager.sendMessage(msg)
            showPairingUi()
        } catch (e: Exception) {
            onError("生成配对码失败: ${e.message}")
        }
    }

    private fun showPairingUi() {
        tvSearchStatus.text = "等待对方确认..."
        layoutPeers.visibility = View.GONE
        tvPairCode.text = pairCode
        layoutPair.visibility = View.VISIBLE
        btnConfirmPair.isEnabled = true
        btnConfirmPair.text = "确认一致"
        btnCancelPair.isEnabled = true
    }

    private fun onConfirmPair() {
        localConfirmed = true
        btnConfirmPair.isEnabled = false
        if (peerConfirmed) {
            createGroupAndSend()
        } else {
            tvSearchStatus.text = "已确认，等待对方确认..."
        }
    }

    private fun handleMessage(message: String) {
        try {
            val obj = JSONObject(message)
            when (obj.optString("type")) {
                "confirm" -> {
                    peerConfirmed = true
                    onUi {
                        if (localConfirmed) {
                            createGroupAndSend()
                        } else {
                            tvSearchStatus.text = "对方已确认，请确认验证码一致"
                        }
                    }
                }
                "reject" -> {
                    onUi {
                        onError("对方已拒绝接收")
                        resetUi()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun createGroupAndSend() {
        activeTransfer = true
        showTransferring(0, "正在建立 WiFi Direct 连接...")
        wifiDirectManager = WifiDirectManager(requireContext())
        wifiDirectManager?.createGroup(object : WifiDirectManager.Callback {
            override fun onGroupReady(ssid: String, passphrase: String, ownerIp: String) {
                onUi {
                    showTransferring(0, "连接已建立，正在传输...")
                    val msg = JSONObject()
                        .put("type", "group")
                        .put("ssid", ssid)
                        .put("passphrase", passphrase)
                        .put("ip", ownerIp)
                        .toString()
                    bleManager.sendMessage(msg)
                    sendEncrypted(ownerIp)
                }
            }

            override fun onGroupFailed(message: String) {
                onUi { onError(message) }
            }

            override fun onJoined(ownerIp: String) {}
            override fun onJoinFailed(message: String) {}
            override fun onGroupRemoved() {}
        })
    }

    private fun sendEncrypted(ip: String) {
        val json = payloadJson ?: return
        val key = sessionKey ?: return
        try {
            val envelope = JsonTransferProtocol.wrap(json, Build.MODEL, appVersion())
            val encrypted = CryptoManager.encrypt(key, envelope.toByteArray(Charsets.UTF_8))
            showTransferring(0, "正在发送...")
            TransferTcp.sendFile(
                ip,
                WifiDirectManager.TRANSFER_PORT,
                encrypted,
                onProgress = { p -> onUi { updateProgress(p) } },
                onDone = { ok, msg ->
                    onUi {
                        if (ok) {
                            updateProgress(100)
                            finishSendSuccess()
                        } else {
                            onError(msg)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            onUi { onError("加密失败: ${e.message}") }
        }
    }

    private fun finishSendSuccess() {
        tvProgress.text = "100%"
        tvProgressStatus.text = "发送成功"
        activeTransfer = false
        AlertDialog.Builder(requireContext())
            .setTitle("发送成功")
            .setMessage("Mesh 配网配置已成功发送给对方。")
            .setPositiveButton("完成") { _, _ -> cleanupAndReset() }
            .setCancelable(false)
            .show()
    }

    private fun updateProgress(percent: Int) {
        progressBar.progress = percent
        tvProgress.text = "$percent%"
        tvProgressStatus.text = "正在发送... $percent%"
    }

    private fun showTransferring(percent: Int, status: String) {
        layoutPair.visibility = View.GONE
        layoutProgress.visibility = View.VISIBLE
        progressBar.progress = percent
        tvProgress.text = "$percent%"
        tvProgressStatus.text = status
    }

    private fun sendRejectAndReset() {
        bleManager.sendMessage("""{"type":"reject"}""")
        cleanupAndReset()
    }

    private fun onError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        tvProgressStatus.text = message
    }

    private fun cleanupAndReset() {
        try {
            if (::bleManager.isInitialized) {
                bleManager.stopScan()
                bleManager.release()
            }
            wifiDirectManager?.cleanupJoinNetwork()
            wifiDirectManager?.dispose()
            wifiDirectManager = null
        } catch (_: Exception) {
        }
        activeTransfer = false
        foundDevices.clear()
        layoutPeers.removeAllViews()
        layoutPair.visibility = View.GONE
        layoutProgress.visibility = View.GONE
        tvSearchStatus.text = ""
        tvPairCode.text = ""
        btnConfirmPair.isEnabled = true
        payloadJson = viewModel.exportMeshNetworkWithDevices()
        showConfigInfo()
    }

    private fun appVersion(): String {
        return try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    private fun resetUi() {
        layoutPair.visibility = View.GONE
        layoutProgress.visibility = View.GONE
        layoutPeers.removeAllViews()
        foundDevices.clear()
        tvSearchStatus.visibility = View.VISIBLE
        tvSearchStatus.text = ""
    }

    private fun onUi(block: () -> Unit) {
        view?.post { block() }
    }
}