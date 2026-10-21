package com.example.ble_device_mesh.mesh_transfer

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.ble_device_mesh.MeshViewModel
import com.example.ble_device_mesh.R
import org.json.JSONObject
import javax.crypto.spec.SecretKeySpec

class ReceiveFragment : Fragment() {

    private val viewModel: MeshViewModel get() = (requireActivity() as TransferActivity).getMeshViewModel()

    private lateinit var bleManager: BleDiscoveryManager
    private var wifiDirectManager: WifiDirectManager? = null

    private lateinit var btnStartReceive: Button
    private lateinit var tvWaitStatus: TextView
    private lateinit var layoutPair: View
    private lateinit var tvRequesterName: TextView
    private lateinit var tvPairCode: TextView
    private lateinit var btnConfirmPair: Button
    private lateinit var btnCancelPair: Button
    private lateinit var layoutProgress: View
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvProgressStatus: TextView

    private var sessionKey: SecretKeySpec? = null
    private var receivedMeshJson: String? = null
    private var activeTransfer = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_receive, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnStartReceive = view.findViewById(R.id.btnStartReceive)
        tvWaitStatus = view.findViewById(R.id.tvWaitStatus)
        layoutPair = view.findViewById(R.id.layoutPair)
        tvRequesterName = view.findViewById(R.id.tvRequesterName)
        tvPairCode = view.findViewById(R.id.tvPairCode)
        btnConfirmPair = view.findViewById(R.id.btnConfirmPair)
        btnCancelPair = view.findViewById(R.id.btnCancelPair)
        layoutProgress = view.findViewById(R.id.layoutProgress)
        progressBar = view.findViewById(R.id.progressBar)
        tvProgress = view.findViewById(R.id.tvProgress)
        tvProgressStatus = view.findViewById(R.id.tvProgressStatus)

        btnStartReceive.setOnClickListener {
            val activity = activity
            if (activity is TransferActivity) {
                activity.requestTransferPermissions { onStartReceive() }
            } else {
                onStartReceive()
            }
        }
        btnConfirmPair.setOnClickListener { onConfirmPair() }
        btnCancelPair.setOnClickListener { onRejectPair() }
    }

    private fun onStartReceive() {
        resetUi()
        bleManager = BleDiscoveryManager(requireContext())
        bleManager.startReceiver(receiveListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            if (::bleManager.isInitialized) bleManager.release()
        } catch (_: Exception) {
        }
    }

    private val receiveListener = object : BleDiscoveryManager.Listener {
        override fun onDeviceFound(device: android.bluetooth.le.ScanResult) {}

        override fun onScanFailed(message: String) {}

        override fun onConnected() {
            onUi { tvWaitStatus.text = "已连接发送端，等待发送..." }
        }

        override fun onMessage(message: String) {
            handleMessage(message)
        }

        override fun onAdvertiseFailed(message: String) {
            onUi { Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show() }
        }

        override fun onServerFailed(message: String) {
            onUi { Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show() }
        }

        override fun onDisconnected() {
            onUi {
                if (activeTransfer) {
                    onError("连接已断开")
                }
            }
        }

        override fun onLog(message: String) {
            onUi { tvWaitStatus.text = message }
        }

        override fun onError(message: String) {
            onUi { onError(message) }
        }
    }

    private fun handleMessage(message: String) {
        try {
            val obj = JSONObject(message)
            when (obj.optString("type")) {
                "pair" -> showPairRequest(obj)
                "reject" -> onUi {
                    Toast.makeText(requireContext(), "对方已取消发送", Toast.LENGTH_SHORT).show()
                    resetUi()
                }
                "group" -> {
                    val ssid = obj.getString("ssid")
                    val passphrase = obj.getString("passphrase")
                    val ip = obj.getString("ip")
                    joinAndReceive(ssid, passphrase, ip)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showPairRequest(obj: JSONObject) {
        val name = obj.optString("name", "未知设备")
        val code = obj.optString("code", "")
        val saltB64 = obj.optString("salt", "")
        if (code.isEmpty() || saltB64.isEmpty()) {
            onUi { onError("配对信息异常") }
            return
        }
        try {
            val salt = CryptoManager.base64Decode(saltB64)
            sessionKey = CryptoManager.deriveSessionKey(code, salt)
        } catch (e: Exception) {
            onUi { onError("密钥派生失败: ${e.message}") }
            return
        }
        onUi {
            activeTransfer = false
            tvWaitStatus.visibility = View.GONE
            tvRequesterName.text = "来自: $name"
            tvPairCode.text = code
            layoutPair.visibility = View.VISIBLE
            btnConfirmPair.isEnabled = true
            btnCancelPair.isEnabled = true
        }
    }

    private fun onConfirmPair() {
        btnConfirmPair.isEnabled = false
        bleManager.sendMessage("""{"type":"confirm"}""")
        onUi {
            layoutPair.visibility = View.GONE
            tvWaitStatus.visibility = View.VISIBLE
            tvWaitStatus.text = "已确认，正在连接传输通道..."
        }
    }

    private fun onRejectPair() {
        bleManager.sendMessage("""{"type":"reject"}""")
        resetUi()
    }

    private fun joinAndReceive(ssid: String, passphrase: String, ownerIp: String) {
        activeTransfer = true
        onUi { showTransferring(0, "正在加入 WiFi Direct 网络...") }
        wifiDirectManager = WifiDirectManager(requireContext())
        wifiDirectManager?.joinGroup(ssid, passphrase, object : WifiDirectManager.Callback {
            override fun onJoined(connectionIp: String) {
                onUi {
                    showTransferring(0, "已连接，正在接收...")
                    receiveData(ownerIp)
                }
            }

            override fun onJoinFailed(message: String) {
                onUi { onError(message) }
            }

            override fun onGroupReady(ssid: String, passphrase: String, ownerIp: String) {}
            override fun onGroupFailed(message: String) {}
            override fun onGroupRemoved() {}
        })
    }

    private fun receiveData(ownerIp: String) {
        val key = sessionKey ?: return
        TransferTcp.receiveFile(
            ownerIp,
            WifiDirectManager.TRANSFER_PORT,
            onProgress = { p -> onUi { updateProgress(p) } },
            onDone = { ok, data, msg ->
                onUi {
                    if (ok && data != null) {
                        try {
                            val plain = CryptoManager.decrypt(key, data)
                            val envelope = String(plain, Charsets.UTF_8)
                            val payload = JsonTransferProtocol.unwrap(envelope)
                            JSONObject(payload.meshJson)
                            receivedMeshJson = payload.meshJson
                            updateProgress(100)
                            finishReceiveSuccess(payload.senderName)
                        } catch (e: JsonTransferProtocol.TransferException) {
                            onError(e.message ?: "配置文件损坏，无法导入")
                        } catch (e: Exception) {
                            onError("接收数据异常: ${e.message}")
                        }
                    } else {
                        onError(msg?.let { "接收失败: $it" } ?: "接收失败")
                    }
                }
            }
        )
    }

    private fun finishReceiveSuccess(senderName: String) {
        tvProgress.text = "100%"
        tvProgressStatus.text = "接收成功"
        activeTransfer = false
        AlertDialog.Builder(requireContext())
            .setTitle("接收成功")
            .setMessage("来自 $senderName 的 Mesh 配网配置已接收。\n是否立即导入？")
            .setPositiveButton("导入") { _, _ -> doImport() }
            .setNegativeButton("取消") { _, _ -> cleanupAndReset() }
            .setCancelable(false)
            .show()
    }

    private fun doImport() {
        val json = receivedMeshJson
        if (json.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "配置数据为空", Toast.LENGTH_SHORT).show()
            cleanupAndReset()
            return
        }
        try {
            viewModel.importMeshNetworkWithDevices(json)
            AlertDialog.Builder(requireContext())
                .setTitle("导入成功")
                .setMessage("Mesh 配网配置已导入，返回设备列表即可查看。")
                .setPositiveButton("确定") { _, _ -> cleanupAndReset() }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            onError("导入失败: ${e.message}")
            cleanupAndReset()
        }
    }

    private fun updateProgress(percent: Int) {
        progressBar.progress = percent
        tvProgress.text = "$percent%"
        tvProgressStatus.text = "正在接收... $percent%"
    }

    private fun showTransferring(percent: Int, status: String) {
        layoutPair.visibility = View.GONE
        layoutProgress.visibility = View.VISIBLE
        progressBar.progress = percent
        tvProgress.text = "$percent%"
        tvProgressStatus.text = status
    }

    private fun onError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        tvProgressStatus.text = message
    }

    private fun cleanupAndReset() {
        try {
            if (::bleManager.isInitialized) bleManager.release()
            wifiDirectManager?.cleanupJoinNetwork()
            wifiDirectManager?.dispose()
            wifiDirectManager = null
        } catch (_: Exception) {
        }
        activeTransfer = false
        sessionKey = null
        receivedMeshJson = null
        layoutPair.visibility = View.GONE
        layoutProgress.visibility = View.GONE
        tvWaitStatus.visibility = View.VISIBLE
        tvWaitStatus.text = ""
        tvProgress.text = ""
        tvProgressStatus.text = ""
        tvRequesterName.text = ""
        tvPairCode.text = ""
        btnConfirmPair.isEnabled = true
    }

    private fun resetUi() {
        layoutPair.visibility = View.GONE
        layoutProgress.visibility = View.GONE
        tvWaitStatus.visibility = View.VISIBLE
        tvWaitStatus.text = "等待附近设备发送配置..."
    }

    private fun onUi(block: () -> Unit) {
        view?.post { block() }
    }
}