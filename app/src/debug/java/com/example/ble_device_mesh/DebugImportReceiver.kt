package com.example.ble_device_mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.ble_device_mesh.data.DeviceRepository
import java.io.File

/**
 * 仅 debug 构建：从 App 私有目录导入合并格式的 Mesh 配置（网络 + 设备元数据）。
 *
 * 用法：
 *   adb shell "run-as com.example.ble_device_mesh sh -c 'cat > files/mesh_import.json'" < mesh.json
 *   adb shell am broadcast -a com.example.ble_device_mesh.DEBUG_IMPORT
 *
 * 验证完成后应删除本文件及 debug Manifest 中的对应条目。
 */
class DebugImportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as android.app.Application
        val main = Handler(Looper.getMainLooper())
        val file = File(app.filesDir, intent.getStringExtra("file") ?: "mesh_import.json")

        Log.d("MeshApp", "DEBUG_IMPORT 收到请求, 文件=${file.absolutePath} 存在=${file.exists()}")

        // 1) 先让 MeshViewModel 初始化并把现有网络加载完
        main.post {
            MeshViewModel(app)
            Log.d("MeshApp", "DEBUG_IMPORT ViewModel 已创建，等待网络加载")
        }

        // 2) 3 秒后导入
        main.postDelayed({
            try {
                val json = file.readText()
                Log.d("MeshApp", "DEBUG_IMPORT 开始导入, ${json.length} 字符")
                MeshViewModel(app).importMeshNetworkWithDevices(json)
            } catch (e: Exception) {
                Log.e("MeshApp", "DEBUG_IMPORT 读文件失败: ${e.message}")
            }
        }, 3000)

        // 3) 12 秒后汇报结果
        main.postDelayed({
            try {
                val network = MeshViewModel.MeshState.meshNetWork
                val devices = DeviceRepository(app).getAllDevices()
                Log.d(
                    "MeshApp",
                    "DEBUG_IMPORT 结果: network=${network != null}, " +
                        "meshName=${network?.meshName}, nodes=${network?.nodes?.size}, " +
                        "provisioner=0x${network?.selectedProvisioner?.provisionerAddress?.toString(16)}, " +
                        "devices=${devices.size}"
                )
                devices.forEach {
                    Log.d("MeshApp", "DEBUG_IMPORT 设备: ${it.name} 0x${it.address.toString(16)} ${it.bluetoothMac}")
                }
            } catch (e: Exception) {
                Log.e("MeshApp", "DEBUG_IMPORT 汇报失败: ${e.message}")
            } finally {
                pending.finish()
            }
        }, 12000)
    }
}

