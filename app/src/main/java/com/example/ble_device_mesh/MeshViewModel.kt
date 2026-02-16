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

class MeshViewModel(application: Application): AndroidViewModel(application) {
    private val meshManagerApi = MeshManagerApi(application)
    private var meshNetWork: MeshNetwork?=null
    val statusText = MutableLiveData<String>()
    
    // BLE 扫描管理器
    private val bleScanner = BleScannerManager(application)
    val scannedDevices = MutableLiveData<List<ScanResult>>(emptyList())
    val isScanning = MutableLiveData<Boolean>(false)
    
    // BLE 连接管理器
    private val bleConnection = BleConnectionManager(application)
    val isConnected = MutableLiveData<Boolean>(false)
    val connectedDeviceAddress = MutableLiveData<String?>(null)
    private var currentDevice: ScanResult? = null
    private var connectionRetryCount = 0
    private val maxRetries = 3
    private var currentTid = 0

    init {
        statusText.postValue("正在初始化MESH...")
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
    
    // 连接到设备
    fun connectToDevice(device: ScanResult) {
        currentDevice = device
        connectionRetryCount = 0
        attemptConnection()
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