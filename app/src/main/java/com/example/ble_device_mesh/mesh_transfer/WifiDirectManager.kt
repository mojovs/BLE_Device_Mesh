package com.example.ble_device_mesh.mesh_transfer

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * WiFi Direct 管理：
 * - 发送端 createGroup 成为 GO（Group Owner），获取 SSID/Passphrase/IP
 * - 接收端 joinGroup 通过 WifiManager.addNetwork + enableNetwork 加入群组
 */
class WifiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectManager"
        const val TRANSFER_PORT = 40123
    }

    interface Callback {
        fun onGroupReady(ssid: String, passphrase: String, ownerIp: String)
        fun onGroupFailed(message: String)
        fun onJoined(ownerIp: String)
        fun onJoinFailed(message: String)
        fun onGroupRemoved()
    }

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val p2pManager =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = p2pManager.initialize(context, Looper.getMainLooper(), null)
    private val handler = Handler(Looper.getMainLooper())

    private var callback: Callback? = null
    private var joinNetId = -1

    // ============ 发送端：创建群组（GO） ============

    fun createGroup(callback: Callback) {
        this.callback = callback
        p2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                handler.postDelayed({ fetchGroupInfo() }, 800)
            }

            override fun onFailure(reason: Int) {
                callback.onGroupFailed("创建群组失败 (reason=$reason)")
            }
        })
    }

    private fun fetchGroupInfo() {
        p2pManager.requestGroupInfo(channel) { group ->
            val ssid = group.networkName
            val passphrase = group.passphrase
            p2pManager.requestConnectionInfo(channel) { info ->
                val ip = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                callback?.onGroupReady(ssid, passphrase, ip)
            }
        }
    }

    // ============ 接收端：加入群组 ============

    @Suppress("DEPRECATION")
    fun joinGroup(ssid: String, passphrase: String, callback: Callback) {
        this.callback = callback
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$passphrase\""
            status = WifiConfiguration.Status.ENABLED
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            allowedProtocols.set(WifiConfiguration.Protocol.WPA)
            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
        }
        val netId = wifiManager.addNetwork(config)
        if (netId == -1) {
            callback.onJoinFailed("无法加入群组 (addNetwork 失败)")
            return
        }
        joinNetId = netId
        wifiManager.disconnect()
        wifiManager.enableNetwork(netId, true)
        wifiManager.reconnect()
        waitForJoin(netId, ssid, System.currentTimeMillis())
    }

    private fun waitForJoin(netId: Int, ssid: String, startMs: Long) {
        val timeout = 15_000L
        val info = wifiManager.connectionInfo
        if (info != null && info.networkId == netId && info.ipAddress != 0) {
            val ip = intToIp(info.ipAddress)
            callback?.onJoined(ip)
            return
        }
        if (System.currentTimeMillis() - startMs > timeout) {
            cleanupJoinNetwork()
            callback?.onJoinFailed("加入群组超时")
            return
        }
        handler.postDelayed({ waitForJoin(netId, ssid, startMs) }, 300)
    }

    fun cleanupJoinNetwork() {
        if (joinNetId != -1) {
            wifiManager.removeNetwork(joinNetId)
            joinNetId = -1
            wifiManager.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun intToIp(ip: Int): String {
        return "${(ip and 0xFF)}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    fun removeGroup() {
        try {
            p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    callback?.onGroupRemoved()
                }

                override fun onFailure(reason: Int) {
                }
            })
        } catch (_: Exception) {
        }
    }

    fun dispose() {
        callback = null
    }
}